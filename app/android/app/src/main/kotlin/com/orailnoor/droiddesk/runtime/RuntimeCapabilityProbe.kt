package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

class RuntimeCapabilityProbe(
    private val source: RuntimeCapabilitySource,
    private val requiredBytes: Long = DEFAULT_REQUIRED_BYTES,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
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
        try {
            val host = hostEvidence()
            val deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val localExecutionFuture = executor.submit(Callable(source::probeLocalExecution))
            val prootFuture = executor.submit(Callable(source::probeProot))
            val x11Future = executor.submit(Callable(source::probeX11))
            val audioFuture = executor.submit(Callable(source::probeAudio))
            val clipboardFuture = executor.submit(Callable(source::probeClipboard))
            val chrootFuture = executor.submit(Callable(source::probeChroot))
            val rendererFuture = executor.submit(Callable(source::probeRenderer))
            val localExecution = awaitEvidence(
                localExecutionFuture,
                deadlineNanos,
                "App-private ELF",
            )
            val proot = awaitEvidence(prootFuture, deadlineNanos, "PRoot")
            val x11 = awaitEvidence(x11Future, deadlineNanos, "X11")
            val audio = awaitEvidence(audioFuture, deadlineNanos, "PulseAudio")
            val clipboard = awaitEvidence(clipboardFuture, deadlineNanos, "Clipboard")
            val chroot = awaitEvidence(chrootFuture, deadlineNanos, "chroot")
            val rendererProbe = awaitRenderer(rendererFuture, deadlineNanos)
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
        future: Future<CapabilityEvidence>,
        deadlineNanos: Long,
        name: String,
    ): CapabilityEvidence = await(
        future = future,
        deadlineNanos = deadlineNanos,
        name = name,
        fallback = {
            CapabilityEvidence(EvidenceState.FAILED_SELF_TEST, it)
        },
    )

    private fun awaitRenderer(
        future: Future<RendererProbe>,
        deadlineNanos: Long,
    ): RendererProbe = await(
        future = future,
        deadlineNanos = deadlineNanos,
        name = "Renderer",
        fallback = {
            RendererProbe(
                CapabilityEvidence(EvidenceState.FAILED_SELF_TEST, it),
                RendererBackend.UNKNOWN,
            )
        },
    )

    private fun <T> await(
        future: Future<T>,
        deadlineNanos: Long,
        name: String,
        fallback: (String) -> T,
    ): T {
        val remainingNanos = maxOf(0L, deadlineNanos - System.nanoTime())
        return try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            fallback("$name probe timed out after ${timeoutMillis}ms")
        } catch (error: Exception) {
            fallback("$name probe failed: ${error.cause?.message ?: error.message ?: "unknown error"}")
        }
    }

    companion object {
        const val MIN_API = 28
        const val REQUIRED_ABI = "arm64-v8a"
        const val LOCAL_LINUX_TARGET_SDK = 28
        const val DEFAULT_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_REQUIRED_BYTES = 2L * 1024L * 1024L * 1024L
        const val PENDING_REASON = "Capability probe is pending"
    }
}

internal class RuntimeCapabilityCache(
    private val load: () -> RuntimeCapabilities,
    initial: RuntimeCapabilities,
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RuntimeCapabilityProbe").apply { isDaemon = true }
    },
    private val refreshIntervalMillis: Long = 10_000L,
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
                runCatching(load).onSuccess {
                    cached = it
                }
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
