package com.orailnoor.droiddesk.runtime

enum class ProotContainerState {
    ABSENT,
    LEGACY_MINIMAL,
    ACTIVE_INCOMPLETE,
    ACTIVE_READY,
    STAGING_INTERRUPTED,
}

data class ProotToolchainVersions(
    val proot: String,
    val prootDistro: String,
)

data class ProotImageIdentity(
    val reference: String,
    val suite: String,
    val digest: String,
)

data class ProotVerification(
    val architecture: String,
    val desktopReady: Boolean,
    val missingPackages: List<String>,
) {
    val valid: Boolean
        get() = architecture == "arm64" && desktopReady && missingPackages.isEmpty()
}

data class ProotReadyMarker(
    val schema: Int,
    val suite: String,
    val imageReference: String,
    val imageDigest: String,
    val packageSetVersion: Int,
    val prootVersion: String,
    val prootDistroVersion: String,
)

interface ProotProvisioningHost {
    fun availableBytes(): Long

    fun ensureToolchain(
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<ProotToolchainVersions>

    fun containerState(): ProotContainerState
    fun discardContainer(name: String): Result<Unit>

    fun installContainer(
        imageReference: String,
        name: String,
        onLog: (String) -> Unit,
    ): Result<ProotImageIdentity>

    fun runRoot(
        container: String,
        command: String,
        onLog: (String) -> Unit,
    ): Result<Unit>

    fun installDesktopPackages(
        container: String,
        packages: List<String>,
        onLog: (String) -> Unit,
    ): Result<Unit>

    fun configureDesktopUser(
        container: String,
        onLog: (String) -> Unit,
    ): Result<Unit>

    fun verify(container: String): Result<ProotVerification>
    fun identity(container: String): Result<ProotImageIdentity>
    fun activate(container: String): Result<Unit>
    fun writeReadyMarker(marker: ProotReadyMarker): Result<Unit>
}

class ProotDebianProvisioner(
    private val host: ProotProvisioningHost,
    private val requiredBytes: Long = DEFAULT_REQUIRED_BYTES,
) {
    fun provision(
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = runCatching {
        require(host.availableBytes() >= requiredBytes) {
            "Insufficient transaction storage: $requiredBytes bytes are required"
        }

        onProgress(0.05, "Preparing PRoot toolchain")
        val toolchain = host.ensureToolchain(onProgress, onLog).getOrThrow()
        val state = host.containerState()
        val working = when (state) {
            ProotContainerState.ABSENT -> installStaging(onProgress, onLog)
            ProotContainerState.STAGING_INTERRUPTED -> {
                host.discardContainer(STAGING_CONTAINER).getOrThrow()
                installStaging(onProgress, onLog)
            }
            ProotContainerState.LEGACY_MINIMAL -> LEGACY_CONTAINER
            ProotContainerState.ACTIVE_INCOMPLETE,
            ProotContainerState.ACTIVE_READY,
            -> ACTIVE_CONTAINER
        }

        onProgress(0.35, "Repairing Debian package state")
        host.runRoot(working, "dpkg --configure -a", onLog).getOrThrow()
        host.runRoot(working, "apt-get install -f -y", onLog).getOrThrow()

        onProgress(0.5, "Installing XFCE and desktop tools")
        host.installDesktopPackages(working, DESKTOP_PACKAGES, onLog).getOrThrow()

        onProgress(0.75, "Configuring desktop user")
        host.configureDesktopUser(working, onLog).getOrThrow()

        onProgress(0.88, "Verifying Debian desktop")
        val verification = host.verify(working).getOrThrow()
        check(verification.valid) {
            buildString {
                append("Debian verification failed")
                if (verification.architecture != "arm64") append(": architecture=${verification.architecture}")
                if (!verification.desktopReady) append(": xfce4-session is missing")
                if (verification.missingPackages.isNotEmpty()) {
                    append(": missing=${verification.missingPackages.joinToString(",")}")
                }
            }
        }
        val identity = host.identity(working).getOrThrow()

        onProgress(0.95, "Activating Debian desktop")
        host.activate(working).getOrThrow()
        host.writeReadyMarker(
            ProotReadyMarker(
                schema = READY_SCHEMA,
                suite = identity.suite,
                imageReference = identity.reference,
                imageDigest = identity.digest,
                packageSetVersion = PACKAGE_SET_VERSION,
                prootVersion = toolchain.proot,
                prootDistroVersion = toolchain.prootDistro,
            ),
        ).getOrThrow()
        onProgress(1.0, "Debian XFCE desktop is ready")
    }

    private fun installStaging(
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): String {
        onProgress(0.15, "Downloading Debian ARM64 image")
        host.installContainer(IMAGE_REFERENCE, STAGING_CONTAINER, onLog).getOrThrow()
        return STAGING_CONTAINER
    }

    companion object {
        const val READY_SCHEMA = 1
        const val PACKAGE_SET_VERSION = 1
        const val IMAGE_REFERENCE =
            "debian@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3"
        const val ACTIVE_CONTAINER = "droiddesk-debian"
        const val STAGING_CONTAINER = "droiddesk-debian-staging"
        const val LEGACY_CONTAINER = "debian"
        const val DEFAULT_REQUIRED_BYTES = 6L * 1024L * 1024L * 1024L

        val DESKTOP_PACKAGES = listOf(
            "xfce4",
            "xfce4-terminal",
            "xfce4-whiskermenu-plugin",
            "thunar",
            "mousepad",
            "dbus-x11",
            "xclip",
            "sudo",
            "ca-certificates",
            "curl",
            "wget",
            "git",
            "nano",
            "htop",
            "python3",
            "python3-pip",
            "fonts-noto-core",
            "papirus-icon-theme",
        )
    }
}
