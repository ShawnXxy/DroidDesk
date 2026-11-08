package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface RuntimeCapabilitySource {
    val apiLevel: Int
    val abi: String
    val targetSdk: Int
    val availableBytes: Long

    fun probeLocalExecution(): CapabilityEvidence
    fun probeProot(): CapabilityEvidence
    fun probeX11(): CapabilityEvidence
    fun probeAudio(): CapabilityEvidence
    fun probeClipboard(): CapabilityEvidence
    fun probeChroot(): CapabilityEvidence
    fun probeRenderer(): RendererProbe
    fun hasAvfFeature(): Boolean
    fun hasVmPermissions(): Boolean
}

class RuntimeCapabilityProbe private constructor(
    private val source: RuntimeCapabilitySource,
    private val requiredBytes: Long,
    private val timeouts: ProbeTimeouts,
    private val hooks: ProbeExecutionHooks,
) {
    constructor(
        source: RuntimeCapabilitySource,
        requiredBytes: Long = DEFAULT_REQUIRED_BYTES,
        timeouts: ProbeTimeouts = ProbeTimeouts(),
    ) : this(
        source = source,
        requiredBytes = requiredBytes,
        timeouts = timeouts,
        hooks = ProbeExecutionHooks(),
    )

    constructor(
        source: RuntimeCapabilitySource,
        requiredBytes: Long,
        timeoutMillis: Long,
    ) : this(
        source = source,
        requiredBytes = requiredBytes,
        timeouts = ProbeTimeouts(
            localExecutionMs = timeoutMillis,
            prootMs = timeoutMillis,
            x11Ms = timeoutMillis,
            audioMs = timeoutMillis,
            clipboardMs = timeoutMillis,
            chrootMs = timeoutMillis,
            rendererMs = timeoutMillis,
        ),
        hooks = ProbeExecutionHooks(),
    )

    constructor(
        source: RuntimeCapabilitySource,
        timeoutMillis: Long,
    ) : this(
        source = source,
        requiredBytes = DEFAULT_REQUIRED_BYTES,
        timeoutMillis = timeoutMillis,
    )

    internal constructor(
        source: RuntimeCapabilitySource,
        requiredBytes: Long,
        timeouts: ProbeTimeouts,
        timeoutExecutorFactory: () -> ScheduledExecutorService,
        beforeOutcomePublication: () -> Unit = {},
    ) : this(
        source = source,
        requiredBytes = requiredBytes,
        timeouts = timeouts,
        hooks = ProbeExecutionHooks(
            timeoutExecutorFactory = timeoutExecutorFactory,
            beforeOutcomePublication = beforeOutcomePublication,
        ),
    )

    fun pending(): RuntimeCapabilities {
        val pending = CapabilityEvidence(
            EvidenceState.PRESENT_UNVERIFIED,
            PENDING_REASON,
        )
        val avfPresent = source.hasAvfFeature()
        val avfFeature = if (avfPresent) {
            CapabilityEvidence(
                EvidenceState.PRESENT_UNVERIFIED,
                "Android Virtualization Framework is present but is not a DroidDesk runtime",
            )
        } else {
            CapabilityEvidence(EvidenceState.UNAVAILABLE, "AVF system feature is absent")
        }
        val avfPermission = when {
            !avfPresent -> CapabilityEvidence(
                EvidenceState.UNAVAILABLE,
                "AVF system feature is absent",
            )
            source.hasVmPermissions() -> CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
            else -> CapabilityEvidence(
                EvidenceState.BLOCKED_BY_PERMISSION,
                "The app lacks platform VM management permissions",
            )
        }
        return RuntimeCapabilities(
            hostApiLevel = source.apiLevel,
            abi = source.abi,
            host = hostEvidence(),
            localExecution = pending,
            proot = pending,
            x11 = pending,
            audio = pending,
            clipboard = pending,
            chroot = pending,
            avfFeature = avfFeature,
            avfPermission = avfPermission,
            renderer = pending,
            rendererBackend = RendererBackend.UNKNOWN,
            storage = storageEvidence(),
            availableBytes = source.availableBytes,
            requiredBytes = requiredBytes,
            distribution = distributionContract(),
        )
    }

    fun probe(): RuntimeCapabilities {
        val executor = Executors.newCachedThreadPool()
        val timeoutExecutor = hooks.timeoutExecutorFactory()
        try {
            val host = hostEvidence()
            val localExecutionFuture = submit(
                executor,
                timeouts.localExecutionMs,
                source::probeLocalExecution,
            )
            val prootFuture = submit(
                executor,
                timeouts.prootMs,
                source::probeProot,
            )
            val x11Future = submit(
                executor,
                timeouts.x11Ms,
                source::probeX11,
            )
            val audioFuture = submit(
                executor,
                timeouts.audioMs,
                source::probeAudio,
            )
            val clipboardFuture = submit(
                executor,
                timeouts.clipboardMs,
                source::probeClipboard,
            )
            val chrootFuture = submit(
                executor,
                timeouts.chrootMs,
                source::probeChroot,
            )
            val rendererFuture = submit(
                executor,
                timeouts.rendererMs,
                source::probeRenderer,
            )
            listOf<TimedFuture<*>>(
                localExecutionFuture,
                prootFuture,
                x11Future,
                audioFuture,
                clipboardFuture,
                chrootFuture,
                rendererFuture,
            ).forEach { probe ->
                executor.execute {
                    registerTimeout(timeoutExecutor, probe)
                }
            }
            val localExecution = awaitEvidence(
                localExecutionFuture,
                "App-private ELF",
            )
            val proot = awaitEvidence(prootFuture, "PRoot")
            val x11 = awaitEvidence(x11Future, "X11")
            val audio = awaitEvidence(audioFuture, "PulseAudio")
            val clipboard = awaitEvidence(clipboardFuture, "Clipboard")
            val chroot = awaitEvidence(chrootFuture, "chroot")
            val rendererProbe = awaitRenderer(rendererFuture)
            val avfPresent = source.hasAvfFeature()
            val avfFeature = if (avfPresent) {
                CapabilityEvidence(
                    EvidenceState.PRESENT_UNVERIFIED,
                    "Android Virtualization Framework is present but is not a DroidDesk runtime",
                )
            } else {
                CapabilityEvidence(EvidenceState.UNAVAILABLE, "AVF system feature is absent")
            }
            val avfPermission = when {
                !avfPresent -> CapabilityEvidence(
                    EvidenceState.UNAVAILABLE,
                    "AVF system feature is absent",
                )
                source.hasVmPermissions() -> CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
                else -> CapabilityEvidence(
                    EvidenceState.BLOCKED_BY_PERMISSION,
                    "The app lacks platform VM management permissions",
                )
            }
            return RuntimeCapabilities(
                hostApiLevel = source.apiLevel,
                abi = source.abi,
                host = host,
                localExecution = localExecution,
                proot = proot,
                x11 = x11,
                audio = audio,
                clipboard = clipboard,
                chroot = chroot,
                avfFeature = avfFeature,
                avfPermission = avfPermission,
                renderer = rendererProbe.evidence,
                rendererBackend = rendererProbe.backend,
                storage = storageEvidence(),
                availableBytes = source.availableBytes,
                requiredBytes = requiredBytes,
                distribution = distributionContract(),
            )
        } finally {
            timeoutExecutor.shutdownNow()
            executor.shutdownNow()
        }
    }

    private fun hostEvidence(): CapabilityEvidence = when {
        source.apiLevel < MIN_API -> CapabilityEvidence(
            EvidenceState.BLOCKED_BY_POLICY,
            "DroidDesk local Linux requires API 28 or newer",
        )
        source.abi != REQUIRED_ABI -> CapabilityEvidence(
            EvidenceState.BLOCKED_BY_POLICY,
            "DroidDesk local Linux requires $REQUIRED_ABI",
        )
        source.targetSdk > LOCAL_LINUX_TARGET_SDK -> CapabilityEvidence(
            EvidenceState.BLOCKED_BY_POLICY,
            "App-private ELF execution is not supported by this target SDK contract",
        )
        else -> CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
    }

    private fun storageEvidence(): CapabilityEvidence =
        if (source.availableBytes >= requiredBytes) {
            CapabilityEvidence(EvidenceState.SUPPORTED_AND_PROBED)
        } else {
            CapabilityEvidence(
                EvidenceState.BLOCKED_BY_POLICY,
                "Requires $requiredBytes bytes but only ${source.availableBytes} are available",
            )
        }

    private fun distributionContract() = DistributionContract(
        channel = DistributionChannel.SIDELOAD_LOCAL_LINUX,
        targetSdk = source.targetSdk,
        requiresAppPrivateElfExecution = true,
    )

    private fun awaitEvidence(
        probe: TimedFuture<CapabilityEvidence>,
        name: String,
    ): CapabilityEvidence = await(
        probe = probe,
        name = name,
        fallback = {
            CapabilityEvidence(EvidenceState.FAILED_SELF_TEST, it)
        },
    )

    private fun awaitRenderer(
        probe: TimedFuture<RendererProbe>,
    ): RendererProbe = await(
        probe = probe,
        name = "Renderer",
        fallback = {
            RendererProbe(
                CapabilityEvidence(EvidenceState.FAILED_SELF_TEST, it),
                RendererBackend.UNKNOWN,
            )
        },
    )

    private fun <T> submit(
        executor: ExecutorService,
        timeoutMillis: Long,
        probe: () -> T,
    ): TimedFuture<T> {
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val terminal = ProbeTerminal<T>(deadlineNanos)
        val future = executor.submit(
            Callable {
                val candidate: ProbeOutcome<T> = try {
                    ProbeOutcome.Success(probe())
                } catch (error: Throwable) {
                    ProbeOutcome.Failure(error)
                }
                hooks.beforeOutcomePublication()
                terminal.publish(candidate)
            },
        )
        return TimedFuture(
            future = future,
            deadlineNanos = deadlineNanos,
            timeoutMillis = timeoutMillis,
            terminal = terminal,
        )
    }

    private fun registerTimeout(
        timeoutExecutor: ScheduledExecutorService,
        probe: TimedFuture<*>,
    ) {
        if (probe.terminal.current() != null) return
        try {
            timeoutExecutor.schedule(
                {
                    if (probe.terminal.timeout()) probe.future.cancel(true)
                },
                maxOf(0L, probe.deadlineNanos - System.nanoTime()),
                TimeUnit.NANOSECONDS,
            )
        } catch (error: RejectedExecutionException) {
            if (!timeoutExecutor.isShutdown) {
                Log.w(
                    "RuntimeCapabilityProbe",
                    "Timeout registration failed; collection will enforce the deadline",
                    error,
                )
            }
        }
    }

    private fun <T> await(
        probe: TimedFuture<T>,
        name: String,
        fallback: (String) -> T,
    ): T {
        if (probe.terminal.current() == null) {
            val remainingNanos = maxOf(0L, probe.deadlineNanos - System.nanoTime())
            val completed = try {
                probe.terminal.completed.await(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (error: InterruptedException) {
                return fallback(
                    "$name probe failed: ${error.message ?: "interrupted while awaiting evidence"}",
                )
            }
            if (!completed && probe.terminal.timeout()) probe.future.cancel(true)
        }
        return when (val outcome = probe.terminal.current()) {
            is ProbeOutcome.Success -> outcome.value
            is ProbeOutcome.Failure -> fallback(
                "$name probe failed: " +
                    (outcome.error.message ?: outcome.error.toString()),
            )
            ProbeOutcome.TimedOut ->
                fallback("$name probe timed out after ${probe.timeoutMillis}ms")
            null -> fallback("$name probe failed: unknown error")
        }
    }

    companion object {
        const val MIN_API = 28
        const val REQUIRED_ABI = "arm64-v8a"
        const val LOCAL_LINUX_TARGET_SDK = 28
        @Deprecated("Use ProbeTimeouts for per-capability budgets")
        const val DEFAULT_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_REQUIRED_BYTES = 2L * 1024L * 1024L * 1024L
        const val PENDING_REASON = "Capability probe is pending"
    }
}

private data class ProbeExecutionHooks(
    val timeoutExecutorFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "RuntimeCapabilityTimeouts").apply { isDaemon = true }
        }
    },
    val beforeOutcomePublication: () -> Unit = {},
)

private sealed interface ProbeOutcome<out T> {
    data class Success<T>(val value: T) : ProbeOutcome<T>
    data class Failure(val error: Throwable) : ProbeOutcome<Nothing>
    data object TimedOut : ProbeOutcome<Nothing>
}

private class ProbeTerminal<T>(
    private val deadlineNanos: Long,
) {
    val completed = CountDownLatch(1)

    @Volatile private var outcome: ProbeOutcome<T>? = null

    fun current(): ProbeOutcome<T>? = outcome

    fun publish(candidate: ProbeOutcome<T>) {
        synchronized(this) {
            if (outcome != null) return
            outcome = if (System.nanoTime() < deadlineNanos) {
                candidate
            } else {
                ProbeOutcome.TimedOut
            }
            completed.countDown()
        }
    }

    fun timeout(): Boolean = synchronized(this) {
        if (outcome != null) {
            false
        } else {
            outcome = ProbeOutcome.TimedOut
            completed.countDown()
            true
        }
    }
}

private data class TimedFuture<T>(
    val future: Future<*>,
    val deadlineNanos: Long,
    val timeoutMillis: Long,
    val terminal: ProbeTerminal<T>,
)

internal class RuntimeCapabilityCache(
    private val load: () -> RuntimeCapabilities,
    initial: RuntimeCapabilities,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RuntimeCapabilityProbe").apply { isDaemon = true }
    },
    private val refreshIntervalMillis: Long = 10_000L,
    private val onRefreshFailure: (Throwable) -> Unit = {
        Log.w(
            "RuntimeCapabilityProbe",
            "Capability refresh failed; retaining cached evidence",
            it,
        )
    },
) {
    @Volatile private var cached = initial
    @Volatile private var refreshedAt = 0L
    private val refreshInFlight = AtomicBoolean(false)

    init {
        refresh()
    }

    fun current(): RuntimeCapabilities {
        if (System.currentTimeMillis() - refreshedAt >= refreshIntervalMillis) {
            refresh()
        }
        return cached
    }

    fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                runCatching(load)
                    .onSuccess { cached = it }
                    .onFailure(onRefreshFailure)
            } finally {
                refreshedAt = System.currentTimeMillis()
                refreshInFlight.set(false)
            }
        }
    }
}

internal object RuntimeCapabilityRegistry {
    @Volatile private var cache: RuntimeCapabilityCache? = null

    fun get(
        context: Context,
        linuxRuntime: LinuxRuntime,
        chrootRuntime: ChrootRuntime,
    ): RuntimeCapabilityCache =
        cache ?: synchronized(this) {
            cache ?: RuntimeCapabilityProbe(
                AndroidRuntimeCapabilitySource(
                    context.applicationContext,
                    linuxRuntime,
                    chrootRuntime,
                ),
            ).let { probe ->
                RuntimeCapabilityCache(probe::probe, probe.pending())
            }.also { cache = it }
        }
}

class AndroidRuntimeCapabilitySource(
    context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val chrootRuntime: ChrootRuntime,
) : RuntimeCapabilitySource {
    private val appContext = context.applicationContext

    override val apiLevel: Int = Build.VERSION.SDK_INT
    override val abi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    override val targetSdk: Int = appContext.applicationInfo.targetSdkVersion
    override val availableBytes: Long
        get() = appContext.filesDir.usableSpace

    override fun probeLocalExecution(): CapabilityEvidence =
        linuxRuntime.probeAppPrivateExecution()

    override fun probeProot(): CapabilityEvidence = linuxRuntime.probeProot()

    override fun probeX11(): CapabilityEvidence = linuxRuntime.probeX11()

    override fun probeAudio(): CapabilityEvidence = linuxRuntime.probeAudio()

    override fun probeClipboard(): CapabilityEvidence = linuxRuntime.probeClipboard()

    override fun probeChroot(): CapabilityEvidence = chrootRuntime.probeFunctionality()

    override fun probeRenderer(): RendererProbe = linuxRuntime.probeRenderer()

    override fun hasAvfFeature(): Boolean =
        appContext.packageManager.hasSystemFeature("android.software.virtualization_framework")

    override fun hasVmPermissions(): Boolean =
        appContext.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") ==
            PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") ==
            PackageManager.PERMISSION_GRANTED
}
