package com.orailnoor.droiddesk.display.placement

import android.content.pm.ActivityInfo
import com.orailnoor.droiddesk.display.DesktopDisplay
import com.orailnoor.droiddesk.display.DesktopLaunchRequest
import com.orailnoor.droiddesk.display.DesktopStartupTarget
import com.orailnoor.droiddesk.display.DisplayEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlacementStrategyTest {
    private val phone = DesktopDisplay(
        id = 0,
        key = "phone",
        name = "Phone",
        isDefault = true,
        eligible = true,
        reason = null,
    )
    private val external = DesktopDisplay(
        id = 7,
        key = "external",
        name = "External",
        isDefault = false,
        eligible = true,
        reason = null,
    )

    @Test
    fun movableAdapterRoutesLaunchFocusAndCancelThroughTaskPlacement() {
        val adapter = MovableSecondaryDisplayAdapter(movableSelection())
        val port = RecordingPlacementPort(taskFocusResult = true)
        val request = launchRequest(external)

        adapter.launch(port, request)
        assertTrue(adapter.focus(port, request.requestId))
        adapter.cancel(port, request.requestId)

        assertEquals(
            listOf(
                "launchOnDisplay:7",
                "focusTask:41",
                "cancelTask:41",
            ),
            port.calls,
        )
    }

    @Test
    fun movableAdapterAlwaysAllowsManualMovesAndPreservesAutomaticMoveSelection() {
        val automaticSelection = movableSelection(allowAutomaticMoves = true)
        val guardedSelection = movableSelection(allowAutomaticMoves = false)
        val automatic = MovableSecondaryDisplayAdapter(automaticSelection)
        val guarded = MovableSecondaryDisplayAdapter(guardedSelection)

        assertEquals(automaticSelection, automatic.selection)
        assertTrue(automatic.allowsManualMoves)
        assertTrue(automatic.allowsAutomaticMoves)
        assertEquals(guardedSelection, guarded.selection)
        assertTrue(guarded.allowsManualMoves)
        assertFalse(guarded.allowsAutomaticMoves)
    }

    @Test
    fun movableAdapterMapsGenericExternalWithoutChangingOrientation() {
        val adapter = MovableSecondaryDisplayAdapter(movableSelection())

        assertEquals(DisplayEnvironment.PHONE, adapter.environmentFor(phone))
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, adapter.environmentFor(external))
        assertEquals(37, adapter.requestedOrientation(external, originalOrientation = 37))
    }

    @Test
    fun movableAdapterPreservesTntMetadataWithoutAutoLaunchingIt() {
        val tnt = external.copy(id = 100003, key = "tnt", name = "TNT")
        val adapter = MovableSecondaryDisplayAdapter(
            movableSelection(tntDisplayIds = setOf(tnt.id)),
        )

        assertEquals(DisplayEnvironment.TNT, adapter.environmentFor(tnt))
        assertEquals(37, adapter.requestedOrientation(tnt, originalOrientation = 37))
        assertFalse(adapter.shouldAutoLaunchCurrentDisplay(tnt, installed = true))
    }

    @Test
    fun movableAdapterAutoLaunchesOnlyAnInstalledCurrentExternalDesktop() {
        val adapter = MovableSecondaryDisplayAdapter(movableSelection())

        assertTrue(adapter.shouldAutoLaunchCurrentDisplay(external, installed = true))
        assertFalse(adapter.shouldAutoLaunchCurrentDisplay(external, installed = false))
        assertFalse(adapter.shouldAutoLaunchCurrentDisplay(phone, installed = true))
    }

    @Test
    fun movableAdapterNeverWarnsOrRestartsForEnvironmentChanges() {
        val adapter = MovableSecondaryDisplayAdapter(movableSelection())
        val active = startupTarget(phone, DisplayEnvironment.PHONE)
        val next = startupTarget(external, DisplayEnvironment.OTHER_EXTERNAL)

        assertNull(adapter.environmentRestartWarning)
        assertFalse(adapter.requiresSessionRestart(active, next))
        assertFalse(adapter.requiresSessionRestart(active = null, next))
    }

    @Test
    fun movableAdapterCanFallbackToPreviousDisplayOrPhone() {
        val adapter = MovableSecondaryDisplayAdapter(movableSelection())
        val previous = external.copy(id = 8, key = "previous")
        val failed = external.copy(id = 9, key = "failed")

        assertEquals(
            previous,
            adapter.fallbackTarget(
                displays = listOf(phone, previous),
                previous = previous,
                failed = failed,
            ),
        )
        assertEquals(
            phone,
            adapter.fallbackTarget(
                displays = listOf(phone),
                previous = previous,
                failed = failed,
            ),
        )
        assertNull(
            adapter.fallbackTarget(
                displays = listOf(phone),
                previous = null,
                failed = phone,
            ),
        )
    }

    @Test
    fun displayLocalAdapterRoutesLaunchFocusAndCancelThroughLocalPlacement() {
        val adapter = DisplayLocalRestartAdapter(displayLocalSelection())
        val port = RecordingPlacementPort(localFocusResult = true)
        val request = launchRequest(external)

        adapter.launch(port, request)
        assertTrue(adapter.focus(port, request.requestId))
        adapter.cancel(port, request.requestId)

        assertEquals(
            listOf(
                "launchLocal:7",
                "focusLocal:41",
                "cancelLocal:41",
            ),
            port.calls,
        )
    }

    @Test
    fun displayLocalAdapterRejectsMovesAndLeavesStartupToTheLauncher() {
        val selection = displayLocalSelection()
        val adapter = DisplayLocalRestartAdapter(selection)

        assertEquals(selection, adapter.selection)
        assertFalse(adapter.allowsManualMoves)
        assertFalse(adapter.allowsAutomaticMoves)
        assertFalse(adapter.shouldAutoLaunchCurrentDisplay(external, installed = true))
    }

    @Test
    fun tntQuirkRequestsLandscapeAndOtherExternalPreservesOrientation() {
        val tnt = external.copy(id = 100003, key = "tnt", name = "TNT")
        val adapter = DisplayLocalRestartAdapter(
            displayLocalSelection(tntDisplayIds = setOf(tnt.id)),
        )
        val withoutQuirk = DisplayLocalRestartAdapter(displayLocalSelection())

        assertEquals(DisplayEnvironment.PHONE, adapter.environmentFor(phone))
        assertEquals(DisplayEnvironment.TNT, adapter.environmentFor(tnt))
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, adapter.environmentFor(external))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            adapter.requestedOrientation(tnt, originalOrientation = 37),
        )
        assertEquals(37, adapter.requestedOrientation(external, originalOrientation = 37))
        assertEquals(DisplayEnvironment.OTHER_EXTERNAL, withoutQuirk.environmentFor(tnt))
        assertEquals(37, withoutQuirk.requestedOrientation(tnt, originalOrientation = 37))
    }

    @Test
    fun onlyDisplayLocalAdapterPublishesRestartWarning() {
        val displayLocal = DisplayLocalRestartAdapter(displayLocalSelection())
        val movable = MovableSecondaryDisplayAdapter(movableSelection())

        assertEquals(
            "Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first.",
            displayLocal.environmentRestartWarning,
        )
        assertNull(movable.environmentRestartWarning)
    }

    @Test
    fun displayLocalAdapterRestartsOnlyWhenEnvironmentChanges() {
        val adapter = DisplayLocalRestartAdapter(displayLocalSelection())
        val phoneTarget = startupTarget(phone, DisplayEnvironment.PHONE)
        val externalTarget = startupTarget(external, DisplayEnvironment.OTHER_EXTERNAL)
        val replacementExternal = startupTarget(
            external.copy(id = 8, key = "replacement"),
            DisplayEnvironment.OTHER_EXTERNAL,
        )

        assertFalse(adapter.requiresSessionRestart(active = null, next = externalTarget))
        assertTrue(adapter.requiresSessionRestart(active = phoneTarget, next = externalTarget))
        assertFalse(
            adapter.requiresSessionRestart(
                active = externalTarget,
                next = replacementExternal,
            ),
        )
    }

    @Test
    fun displayLocalAdapterNeverFallsBackAfterDisconnect() {
        val adapter = DisplayLocalRestartAdapter(displayLocalSelection())

        assertNull(
            adapter.fallbackTarget(
                displays = listOf(phone),
                previous = external,
                failed = external,
            ),
        )
    }

    private fun movableSelection(
        allowAutomaticMoves: Boolean = true,
        tntDisplayIds: Set<Int> = emptySet(),
    ) = PlacementSelection(
        mode = com.orailnoor.droiddesk.display.capability.PlacementMode.MOVABLE,
        source = PlacementSelectionSource.RUNTIME_EVIDENCE,
        allowAutomaticMoves = allowAutomaticMoves,
        reason = "test",
        tntDisplayIds = tntDisplayIds,
    )

    private fun displayLocalSelection(
        tntDisplayIds: Set<Int> = emptySet(),
    ) = PlacementSelection(
        mode = com.orailnoor.droiddesk.display.capability.PlacementMode.DISPLAY_LOCAL,
        source = PlacementSelectionSource.DEVICE_QUIRK,
        allowAutomaticMoves = false,
        reason = "test",
        tntDisplayIds = tntDisplayIds,
    )

    private fun launchRequest(display: DesktopDisplay) = DesktopLaunchRequest(
        display = display,
        requestId = 41,
        sessionGeneration = 3,
        mode = "termux",
        desktopEnv = "xfce4",
    )

    private fun startupTarget(
        display: DesktopDisplay,
        environment: DisplayEnvironment,
    ) = DesktopStartupTarget(
        displayId = display.id,
        displayKey = display.key,
        environment = environment,
    )
}

private class RecordingPlacementPort(
    private val localFocusResult: Boolean = false,
    private val taskFocusResult: Boolean = false,
) : DesktopPlacementPort {
    val calls = mutableListOf<String>()

    override fun launchLocal(request: DesktopLaunchRequest) {
        calls += "launchLocal:${request.display.id}"
    }

    override fun launchOnDisplay(request: DesktopLaunchRequest) {
        calls += "launchOnDisplay:${request.display.id}"
    }

    override fun focusLocal(requestId: Long): Boolean {
        calls += "focusLocal:$requestId"
        return localFocusResult
    }

    override fun focusTask(requestId: Long): Boolean {
        calls += "focusTask:$requestId"
        return taskFocusResult
    }

    override fun cancelLocal(requestId: Long) {
        calls += "cancelLocal:$requestId"
    }

    override fun cancelTask(requestId: Long) {
        calls += "cancelTask:$requestId"
    }
}
