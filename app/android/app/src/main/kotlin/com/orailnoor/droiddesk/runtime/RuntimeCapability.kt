package com.orailnoor.droiddesk.runtime

enum class EvidenceState {
    SUPPORTED_AND_PROBED,
    PRESENT_UNVERIFIED,
    UNAVAILABLE,
    BLOCKED_BY_PERMISSION,
    BLOCKED_BY_POLICY,
    FAILED_SELF_TEST,
}

data class CapabilityEvidence(
    val state: EvidenceState,
    val reason: String? = null,
) {
    val blocksProvisioning: Boolean
        get() = when (state) {
            EvidenceState.SUPPORTED_AND_PROBED,
            EvidenceState.PRESENT_UNVERIFIED,
            -> false
            EvidenceState.UNAVAILABLE,
            EvidenceState.BLOCKED_BY_PERMISSION,
            EvidenceState.BLOCKED_BY_POLICY,
            EvidenceState.FAILED_SELF_TEST,
            -> true
        }

    fun toMap(): Map<String, Any?> = mapOf(
        "state" to state.name,
        "reason" to reason,
    )
}

enum class RendererBackend(val wireValue: String) {
    NATIVE_TURNIP_KGSL("nativeTurnipKgsl"),
    PROOT_DRM_MSM("prootDrmMsm"),
    PROOT_VIRGL_VTEST("prootVirglVtest"),
    SOFTWARE_LLVMPIPE("softwareLlvmPipe"),
    UNKNOWN("unknown"),
}

data class RendererProbe(
    val evidence: CapabilityEvidence,
    val backend: RendererBackend,
)

enum class DistributionChannel(val wireValue: String) {
    SIDELOAD_LOCAL_LINUX("sideloadLocalLinux"),
}

data class DistributionContract(
    val channel: DistributionChannel,
    val targetSdk: Int,
    val requiresAppPrivateElfExecution: Boolean,
)

data class RuntimeCapabilities(
    val hostApiLevel: Int,
    val abi: String,
    val host: CapabilityEvidence,
    val localExecution: CapabilityEvidence,
    val proot: CapabilityEvidence,
    val x11: CapabilityEvidence,
    val audio: CapabilityEvidence,
    val clipboard: CapabilityEvidence,
    val chroot: CapabilityEvidence,
    val avfFeature: CapabilityEvidence,
    val avfPermission: CapabilityEvidence,
    val renderer: CapabilityEvidence,
    val rendererBackend: RendererBackend,
    val storage: CapabilityEvidence,
    val availableBytes: Long,
    val requiredBytes: Long,
    val distribution: DistributionContract,
) {
    val prootCandidate: Boolean
        get() = listOf(
            host,
            localExecution,
            proot,
            x11,
            audio,
            clipboard,
            renderer,
            storage,
        )
            .none { it.blocksProvisioning }

    val avfAdapterAvailable: Boolean
        get() = avfFeature.state == EvidenceState.SUPPORTED_AND_PROBED &&
            avfPermission.state == EvidenceState.SUPPORTED_AND_PROBED

    val hardwareAccelerationVerified: Boolean
        get() = renderer.state == EvidenceState.SUPPORTED_AND_PROBED &&
            rendererBackend != RendererBackend.SOFTWARE_LLVMPIPE &&
            rendererBackend != RendererBackend.UNKNOWN

    fun toMap(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "hostApiLevel" to hostApiLevel,
        "abi" to abi,
        "host" to host.toMap(),
        "localExecution" to localExecution.toMap(),
        "proot" to proot.toMap(),
        "x11" to x11.toMap(),
        "audio" to audio.toMap(),
        "clipboard" to clipboard.toMap(),
        "chroot" to chroot.toMap(),
        "avfFeature" to avfFeature.toMap(),
        "avfPermission" to avfPermission.toMap(),
        "renderer" to renderer.toMap(),
        "rendererBackend" to rendererBackend.wireValue,
        "storage" to storage.toMap(),
        "availableBytes" to availableBytes,
        "requiredBytes" to requiredBytes,
        "prootCandidate" to prootCandidate,
        "avfAdapterAvailable" to avfAdapterAvailable,
        "hardwareAccelerationVerified" to hardwareAccelerationVerified,
        "distribution" to mapOf(
            "channel" to distribution.channel.wireValue,
            "targetSdk" to distribution.targetSdk,
            "requiresAppPrivateElfExecution" to distribution.requiresAppPrivateElfExecution,
        ),
    )
}
