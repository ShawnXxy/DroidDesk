package com.orailnoor.droiddesk.display.refresh

import com.orailnoor.droiddesk.display.capability.DisplayModeEvidence
import kotlin.math.abs

data class RefreshRequest(
    val preferredRefreshRate: Float?,
    val preferredModeId: Int?,
    val reason: String,
)

object DisplayRefreshPolicy {
    private const val DEFAULT_TARGET_FPS = 60f
    private const val REFRESH_RATE_TOLERANCE_HZ = 0.5f

    @JvmOverloads
    fun resolve(
        current: DisplayModeEvidence?,
        supported: List<DisplayModeEvidence>,
        targetFps: Float = DEFAULT_TARGET_FPS,
    ): RefreshRequest {
        if (current == null) {
            return RefreshRequest(
                preferredRefreshRate = null,
                preferredModeId = null,
                reason = "missing current display mode",
            )
        }
        if (supported.isEmpty()) {
            return RefreshRequest(
                preferredRefreshRate = null,
                preferredModeId = null,
                reason = "missing supported display modes",
            )
        }
        if (!targetFps.isFinite() || targetFps <= 0f) {
            return RefreshRequest(
                preferredRefreshRate = null,
                preferredModeId = null,
                reason = "invalid target refresh rate $targetFps",
            )
        }

        val exactResolutionModes = supported.filter {
            it.width == current.width && it.height == current.height
        }
        val maxExactResolutionRate = exactResolutionModes
            .maxOfOrNull { it.refreshRate }
            ?.let { maxOf(current.refreshRate, it) }
            ?: current.refreshRate
        val preferredRefreshRate = minOf(targetFps, maxExactResolutionRate)
        val selectedMode = exactResolutionModes
            .filter { abs(it.refreshRate - targetFps) <= REFRESH_RATE_TOLERANCE_HZ }
            .minWithOrNull(
                compareBy<DisplayModeEvidence> { abs(it.refreshRate - targetFps) }
                    .thenBy { it.modeId != current.modeId }
                    .thenBy { it.modeId },
            )

        val reason = when {
            selectedMode != null ->
                "selected exact-resolution mode ${selectedMode.modeId} " +
                    "(${selectedMode.width}x${selectedMode.height}@${selectedMode.refreshRate}Hz) " +
                    "for target ${targetFps}Hz"
            maxExactResolutionRate < targetFps ->
                "capped refresh rate to ${preferredRefreshRate}Hz " +
                    "(${current.width}x${current.height} max ${maxExactResolutionRate}Hz " +
                    "below target ${targetFps}Hz); no exact-resolution target mode"
            else ->
                "requested ${preferredRefreshRate}Hz without mode switch " +
                    "(no ${current.width}x${current.height} mode near ${targetFps}Hz; " +
                    "max ${maxExactResolutionRate}Hz)"
        }

        return RefreshRequest(
            preferredRefreshRate = preferredRefreshRate,
            preferredModeId = selectedMode?.modeId,
            reason = reason,
        )
    }
}
