package com.orailnoor.droiddesk.packages

import com.orailnoor.droiddesk.runtime.ExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxCapabilityProbeTest {

    @Test
    fun sandboxedLaunchMayBeLimitedOrSupported() {
        val port = FakeSandboxProbePort(sandboxedResult = ExecutionResult(0, "ok"))
        val result = DefaultSandboxCapabilityProbe(port).probe(
            experimentalRecipe("app.chrome"),
        )

        assertEquals(SandboxProbeState.SANDBOXED, result.state)
        assertTrue(result.sandboxed)
        assertNull(result.blockingReason)
    }

    @Test
    fun noSandboxOnlyLaunchRemainsExperimental() {
        val port = FakeSandboxProbePort(
            sandboxedResult = ExecutionResult(1, "sandbox unavailable"),
            unsafeResult = ExecutionResult(0, "ok"),
        )
        val result = DefaultSandboxCapabilityProbe(port).probe(
            experimentalRecipe("app.chrome"),
        )

        assertEquals(SandboxProbeState.NO_SANDBOX_ONLY, result.state)
        assertFalse(result.sandboxed)
        assertTrue(result.blockingReason!!.contains("--no-sandbox"))
    }

    @Test
    fun experimentalInstallRequiresExplicitRiskAcceptance() {
        val recipe = experimentalRecipe("app.chrome")
        val probe = SandboxProbeResult(
            state = SandboxProbeState.NO_SANDBOX_ONLY,
            sandboxed = false,
            blockingReason = "Only --no-sandbox launch succeeded",
        )

        val rejected = ExperimentalRecipeGate.validateInstall(
            recipe = recipe,
            probe = probe,
            acceptExperimentalRisk = false,
        )
        val accepted = ExperimentalRecipeGate.validateInstall(
            recipe = recipe,
            probe = probe,
            acceptExperimentalRisk = true,
        )

        assertEquals("EXPERIMENTAL_CONFIRMATION_REQUIRED", rejected!!.code)
        assertNull(accepted)
    }

    @Test
    fun flutterCannotSupplyLaunchFlags() {
        val recipe = experimentalRecipe("app.vscode")
        val port = FakeSandboxProbePort(sandboxedResult = ExecutionResult(0, "ok"))

        DefaultSandboxCapabilityProbe(port).probe(recipe)

        assertEquals(recipe.launchPolicy!!.executable, port.calls.single().first)
        assertEquals(
            recipe.launchPolicy!!.arguments + DefaultSandboxCapabilityProbe.SANDBOX_TEST_ARGUMENTS,
            port.calls.single().second,
        )
    }

    @Test
    fun failedProbeLeavesRecipeUnavailableWithReason() {
        val recipe = experimentalRecipe("app.chrome")
        val probe = SandboxProbeResult(
            state = SandboxProbeState.FAILED,
            sandboxed = false,
            blockingReason = "Chrome did not start",
        )

        val entry = ExperimentalRecipeGate.toEntry(recipe, probe)

        assertEquals(PackageEntryState.UNAVAILABLE, entry.state)
        assertTrue(entry.limitations.contains("Chrome did not start"))
    }
}

private class FakeSandboxProbePort(
    private val sandboxedResult: ExecutionResult,
    private val unsafeResult: ExecutionResult = ExecutionResult(1, "failed"),
) : SandboxProbePort {
    val calls = mutableListOf<Pair<String, List<String>>>()

    override fun run(executable: String, arguments: List<String>): ExecutionResult {
        calls += executable to arguments
        return if ("--no-sandbox" in arguments) unsafeResult else sandboxedResult
    }
}

private fun experimentalRecipe(entryId: String): PackageRecipe {
    val recipe = ProotPackageRecipes.find(entryId)
    if (recipe != null) return recipe
    return PackageRecipe(
        entryId = entryId,
        displayName = entryId,
        summary = "Experimental application",
        aliases = setOf("experimental"),
        category = "Development",
        iconNames = listOf(entryId.substringAfter('.')),
        gui = true,
        supportLevel = PackageSupportLevel.EXPERIMENTAL,
        source = PackageRecipeSource.DebianBookworm,
        packageNames = listOf(entryId.substringAfter('.')),
        primaryPackage = entryId.substringAfter('.'),
        launchPolicy = PackageLaunchPolicy(
            executable = entryId.substringAfter('.'),
            arguments = listOf("--disable-gpu"),
        ),
        limitations = emptyList(),
    )
}
