package com.orailnoor.droiddesk.display.readiness
import java.util.EnumSet
enum class ReadinessSignal { RENDERER_CLAIMED, SURFACE_READY, X11_CONNECTED, GEOMETRY_COMMITTED }
class DesktopReadinessGate(private val onReady: (generation: Long) -> Unit) {
    private var activeGeneration: Long? = null
    private val signals = EnumSet.noneOf(ReadinessSignal::class.java)
    private var completed = false
    fun reset(generation: Long) { require(generation > 0L) { "Readiness generation must be positive: $generation" }; activeGeneration = generation; signals.clear(); completed = false }
    fun signal(generation: Long, signal: ReadinessSignal) {
        if (generation <= 0L || activeGeneration != generation || completed) return
        signals.add(signal); if (signals.size == ReadinessSignal.entries.size) { completed = true; onReady(generation) }
    }
    fun revoke(generation: Long, signal: ReadinessSignal) { if (generation > 0L && activeGeneration == generation && !completed) signals.remove(signal) }
    fun cancel(generation: Long) { if (activeGeneration == generation) { activeGeneration = null; signals.clear(); completed = false } }
}
