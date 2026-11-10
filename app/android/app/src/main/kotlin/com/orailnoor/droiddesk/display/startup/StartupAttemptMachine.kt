package com.orailnoor.droiddesk.display.startup

import com.orailnoor.droiddesk.display.DesktopStartupTarget

internal data class StartupSpec(val mode: String, val desktopEnv: String)
internal data class StartupIdentity(
    val epoch: Long, val spec: StartupSpec, val target: DesktopStartupTarget? = null,
)
internal data class RendererStartup(val generation: Long, val launchIdentity: StartupIdentity)
internal data class PrepareStartup(val identity: StartupIdentity, val start: Boolean)
internal data class StartupSettlement<W>(
    val revision: Long,
    val identity: StartupIdentity,
    val result: Result<Unit>,
    val failure: Throwable?,
    val waiters: List<W>,
    val notifyObservers: Boolean,
)
internal data class StartupTransition<W>(
    val revision: Long,
    val prepare: PrepareStartup?,
    val settlement: StartupSettlement<W>?,
    val snapshot: StartupSnapshot,
)
internal sealed interface AwaitDecision {
    data class Queued(val revision: Long) : AwaitDecision
    data class Immediate(val result: Result<Unit>) : AwaitDecision
    data object NoAttempt : AwaitDecision
    data object StaleEpoch : AwaitDecision
}
internal data class StartupSnapshot(
    val revision: Long,
    val epoch: Long,
    val spec: StartupSpec,
    val reservedEpoch: Long?,
    val baseIdentity: StartupIdentity?,
    val selectedIdentity: StartupIdentity?,
    val terminalResult: Result<Unit>?,
    val terminalFailure: Throwable?,
    val claimState: String,
    val claimGeneration: Long?,
)
internal class StartupAttemptMachine<W>(initialEpoch: Long, initialSpec: StartupSpec) {
    private sealed interface Claim {
        data object None : Claim
        data class Reserved(val renderer: RendererStartup) : Claim
        data object Started : Claim
    }
    private data class Attempt<W>(
        val baseIdentity: StartupIdentity,
        var selectedIdentity: StartupIdentity? = null,
        val waiters: MutableList<W> = mutableListOf(),
        var terminalResult: Result<Unit>? = null,
        var terminalFailure: Throwable? = null,
        var claim: Claim = Claim.None,
    )
    private var revision = 0L
    private var currentEpoch = initialEpoch
    private var currentSpec = initialSpec
    private var reservedEpoch: Long? = null
    private var attempt: Attempt<W>? = null
    fun snapshot(): StartupSnapshot {
        val current = attempt
        val claim = current?.claim ?: Claim.None
        return StartupSnapshot(
            revision,
            currentEpoch,
            currentSpec,
            reservedEpoch,
            current?.baseIdentity,
            current?.selectedIdentity,
            current?.terminalResult,
            current?.terminalFailure,
            when (claim) {
                Claim.None -> "none"
                is Claim.Reserved -> "reserved"
                Claim.Started -> "started"
            },
            (claim as? Claim.Reserved)?.renderer?.generation,
        )
    }
    fun reserveEpoch(invalidatedResult: Result<Unit>): StartupTransition<W> {
        val nextRevision = ++revision
        val settlement = settleUnresolved(invalidatedResult, false, nextRevision)
        attempt = null
        reservedEpoch = ++currentEpoch
        return transition(nextRevision, settlement = settlement)
    }
    fun prepareStart(
        spec: StartupSpec, supersededResult: Result<Unit>, expectedEpoch: Long? = null,
    ): StartupTransition<W> {
        checkExpectedEpoch(expectedEpoch)
        val nextRevision = ++revision
        val settlement = settleUnresolved(supersededResult, false, nextRevision)
        val epoch = if (expectedEpoch != null && reservedEpoch == expectedEpoch) {
            expectedEpoch
        } else {
            currentEpoch + 1
        }
        currentEpoch = epoch
        currentSpec = spec
        reservedEpoch = null
        val identity = StartupIdentity(epoch, spec)
        attempt = Attempt(identity)
        return transition(
            nextRevision,
            PrepareStartup(identity, start = true),
            settlement,
        )
    }
    fun prepareNoStart(
        spec: StartupSpec, expectedEpoch: Long? = null,
    ): StartupTransition<W> {
        checkExpectedEpoch(expectedEpoch)
        val currentAttempt = attempt
        val failedAttempt = currentAttempt?.takeIf { it.terminalResult?.isFailure == true }
        check(failedAttempt != null || currentAttempt == null || currentAttempt.baseIdentity.spec == spec) {
            "Cannot reuse startup epoch $currentEpoch with a different startup spec"
        }
        val epoch = when {
            expectedEpoch != null && reservedEpoch == expectedEpoch -> expectedEpoch
            failedAttempt != null -> currentEpoch + 1
            else -> currentEpoch
        }
        val nextRevision = ++revision
        currentEpoch = epoch
        currentSpec = spec
        reservedEpoch = null
        if (failedAttempt != null) attempt = null
        return transition(
            nextRevision,
            PrepareStartup(StartupIdentity(epoch, spec), start = false),
        )
    }
    fun await(epoch: Long, waiter: W): AwaitDecision {
        if (epoch != currentEpoch) return AwaitDecision.StaleEpoch
        val current = attempt?.takeIf { it.baseIdentity.epoch == epoch }
            ?: return AwaitDecision.NoAttempt
        current.terminalResult?.let { return AwaitDecision.Immediate(it) }
        current.waiters += waiter
        return AwaitDecision.Queued(++revision)
    }
    fun selectTarget(target: DesktopStartupTarget): StartupIdentity? {
        val current = activeAttempt() ?: return null
        val selected = current.baseIdentity.copy(target = target)
        if (current.selectedIdentity != selected) {
            current.selectedIdentity = selected
            revision++
        }
        return selected
    }
    fun matches(renderer: RendererStartup): Boolean =
        activeAttempt()?.selectedIdentity == renderer.launchIdentity
    fun reserveClaim(renderer: RendererStartup): Boolean {
        val current = activeAttempt() ?: return false
        if (current.claim != Claim.None || current.selectedIdentity != renderer.launchIdentity) {
            return false
        }
        current.claim = Claim.Reserved(renderer)
        revision++
        return true
    }
    fun beginClaim(
        renderer: RendererStartup, pendingPreviousGeneration: Long? = null,
    ): Long? {
        val current = activeAttempt() ?: return null
        val reserved = current.claim as? Claim.Reserved ?: return null
        if (reserved.renderer != renderer) return null
        val authorized = current.selectedIdentity == renderer.launchIdentity ||
            renderer.generation == pendingPreviousGeneration
        if (!authorized) return null
        current.claim = Claim.Started
        revision++
        return current.baseIdentity.epoch
    }
    fun releaseClaim(generation: Long): Boolean {
        val current = activeAttempt() ?: return false
        val reserved = current.claim as? Claim.Reserved ?: return false
        if (reserved.renderer.generation != generation) return false
        current.claim = Claim.None
        revision++
        return true
    }
    fun settle(
        epoch: Long, result: Result<Unit>,
        failure: Throwable? = result.exceptionOrNull(),
        notifyObservers: Boolean = true,
    ): StartupSettlement<W>? {
        val current = activeAttempt()?.takeIf { it.baseIdentity.epoch == epoch }
            ?: return null
        return settleAttempt(current, result, failure, notifyObservers, ++revision)
    }
    fun invalidate(result: Result<Unit>): StartupTransition<W> {
        val nextRevision = ++revision
        val settlement = settleUnresolved(result, false, nextRevision)
        attempt = null
        reservedEpoch = null
        currentEpoch++
        return transition(nextRevision, settlement = settlement)
    }
    private fun activeAttempt(): Attempt<W>? =
        attempt?.takeIf {
            it.terminalResult == null && it.baseIdentity.epoch == currentEpoch
        }
    private fun checkExpectedEpoch(expectedEpoch: Long?) {
        check(expectedEpoch == null || expectedEpoch == currentEpoch) {
            "Expected startup epoch $expectedEpoch, current epoch is $currentEpoch"
        }
    }
    private fun settleUnresolved(
        result: Result<Unit>, notifyObservers: Boolean, settlementRevision: Long,
    ): StartupSettlement<W>? =
        attempt?.takeIf { it.terminalResult == null }?.let {
            settleAttempt(
                it,
                result,
                result.exceptionOrNull(),
                notifyObservers,
                settlementRevision,
            )
        }
    private fun settleAttempt(
        current: Attempt<W>, result: Result<Unit>, failure: Throwable?,
        notifyObservers: Boolean, settlementRevision: Long,
    ): StartupSettlement<W> {
        current.terminalResult = result
        current.terminalFailure = failure
        current.claim = Claim.None
        val waiters = current.waiters.toList()
        current.waiters.clear()
        return StartupSettlement(
            settlementRevision,
            current.selectedIdentity ?: current.baseIdentity,
            result,
            failure,
            waiters,
            notifyObservers,
        )
    }
    private fun transition(
        transitionRevision: Long, prepare: PrepareStartup? = null,
        settlement: StartupSettlement<W>? = null,
    ) = StartupTransition(
        transitionRevision,
        prepare,
        settlement,
        snapshot(),
    )
}
