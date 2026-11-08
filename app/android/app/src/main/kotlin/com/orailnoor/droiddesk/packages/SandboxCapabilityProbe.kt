package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.ExecutionResult

internal enum class SandboxProbeState {
    SANDBOXED,
    NO_SANDBOX_ONLY,
    FAILED,
}

internal data class SandboxProbeResult(
    val state: SandboxProbeState,
    val sandboxed: Boolean,
    val blockingReason: String?,
)

internal interface SandboxCapabilityProbe {
    fun probe(recipe: PackageRecipe): SandboxProbeResult
}

internal interface SandboxProbePort {
    fun run(executable: String, arguments: List<String>): ExecutionResult
}

internal class DefaultSandboxCapabilityProbe(
    private val port: SandboxProbePort,
) : SandboxCapabilityProbe {
    override fun probe(recipe: PackageRecipe): SandboxProbeResult {
        val launch = recipe.launchPolicy ?: return SandboxProbeResult(
            state = SandboxProbeState.FAILED,
            sandboxed = false,
            blockingReason = "The experimental recipe has no launch policy",
        )
        val sandboxed = port.run(
            launch.executable,
            launch.arguments + SANDBOX_TEST_ARGUMENTS,
        )
        if (sandboxed.exitCode == 0) {
            return SandboxProbeResult(
                state = SandboxProbeState.SANDBOXED,
                sandboxed = true,
                blockingReason = null,
            )
        }
        val unsafe = port.run(
            launch.executable,
            launch.arguments + SANDBOX_TEST_ARGUMENTS + "--no-sandbox",
        )
        if (unsafe.exitCode == 0) {
            return SandboxProbeResult(
                state = SandboxProbeState.NO_SANDBOX_ONLY,
                sandboxed = false,
                blockingReason = "Only --no-sandbox launch succeeded",
            )
        }
        return SandboxProbeResult(
            state = SandboxProbeState.FAILED,
            sandboxed = false,
            blockingReason = sandboxed.output.ifBlank {
                unsafe.output.ifBlank { "Sandbox capability probe failed" }
            },
        )
    }

    companion object {
        val SANDBOX_TEST_ARGUMENTS = listOf(
            "--headless",
            "--no-first-run",
            "--disable-gpu",
            "about:blank",
        )
    }
}

internal object UnavailableSandboxCapabilityProbe : SandboxCapabilityProbe {
    override fun probe(recipe: PackageRecipe): SandboxProbeResult =
        SandboxProbeResult(
            state = SandboxProbeState.FAILED,
            sandboxed = false,
            blockingReason = "Sandbox validation has not completed on both acceptance devices",
        )
}

internal object ExperimentalRecipeGate {
    fun validateInstall(
        recipe: PackageRecipe,
        probe: SandboxProbeResult,
        acceptExperimentalRisk: Boolean,
    ): PackageStoreError? {
        if (recipe.supportLevel != PackageSupportLevel.EXPERIMENTAL) return null
        if (probe.state == SandboxProbeState.FAILED) {
            return PackageStoreError(
                code = "STORE_UNAVAILABLE",
                message = probe.blockingReason ?: "Experimental compatibility probe failed",
                logTail = null,
            )
        }
        if (!acceptExperimentalRisk) {
            return PackageStoreError(
                code = "EXPERIMENTAL_CONFIRMATION_REQUIRED",
                message = "Explicit confirmation is required for ${recipe.displayName}",
                logTail = null,
            )
        }
        return null
    }

    fun toEntry(
        recipe: PackageRecipe,
        probe: SandboxProbeResult,
    ): PackageStoreEntry = PackageStoreEntry(
        entryId = recipe.entryId,
        displayName = recipe.displayName,
        summary = recipe.summary,
        category = recipe.category,
        iconNames = recipe.iconNames,
        gui = recipe.gui,
        supportLevel = PackageSupportLevel.EXPERIMENTAL,
        state = if (probe.state == SandboxProbeState.FAILED) {
            PackageEntryState.UNAVAILABLE
        } else {
            PackageEntryState.AVAILABLE
        },
        availableVersion = null,
        installedVersion = null,
        removable = true,
        limitations = recipe.limitations + listOfNotNull(probe.blockingReason),
        sourceLabel = when (val source = recipe.source) {
            PackageRecipeSource.DebianBookworm -> "Debian Bookworm"
            is PackageRecipeSource.SignedAptRepository -> source.sourceId
            is PackageRecipeSource.PinnedDeb -> "Official pinned ARM64 package"
        },
    )
}
