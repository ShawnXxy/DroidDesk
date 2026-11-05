package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.orailnoor.droiddesk.display.DisplayProfile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun debianDesktopInstallCommand(packages: List<String>): String {
    require(packages.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]*")) })
    val packageList = packages.joinToString(" ")
    return """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        backup_dir=/etc/apt/droiddesk-source-backup
        mkdir -p "${'$'}backup_dir"
        if [ -f /etc/apt/sources.list ]; then
            if [ ! -f "${'$'}backup_dir/sources.list" ]; then
                cp -f /etc/apt/sources.list "${'$'}backup_dir/sources.list"
            fi
            sed -i \
                -e 's|http://mirrors.ustc.edu.cn|http://deb.debian.org|g' \
                -e 's|https://mirrors.ustc.edu.cn|https://deb.debian.org|g' \
                "${'$'}backup_dir/sources.list"
            sed -i \
                -e 's|http://deb.debian.org|http://mirrors.ustc.edu.cn|g' \
                -e 's|https://deb.debian.org|https://mirrors.ustc.edu.cn|g' \
                /etc/apt/sources.list
        fi
        if [ -f /etc/apt/sources.list.d/debian.sources ]; then
            if [ ! -f "${'$'}backup_dir/debian.sources" ]; then
                cp -f /etc/apt/sources.list.d/debian.sources "${'$'}backup_dir/debian.sources"
            fi
            sed -i \
                -e 's|http://mirrors.ustc.edu.cn|http://deb.debian.org|g' \
                -e 's|https://mirrors.ustc.edu.cn|https://deb.debian.org|g' \
                "${'$'}backup_dir/debian.sources"
            sed -i \
                -e 's|http://deb.debian.org|http://mirrors.ustc.edu.cn|g' \
                -e 's|https://deb.debian.org|https://mirrors.ustc.edu.cn|g' \
                /etc/apt/sources.list.d/debian.sources
        fi
        if ! apt-get update; then
            [ ! -f "${'$'}backup_dir/sources.list" ] ||
                cp -f "${'$'}backup_dir/sources.list" /etc/apt/sources.list
            [ ! -f "${'$'}backup_dir/debian.sources" ] ||
                cp -f "${'$'}backup_dir/debian.sources" /etc/apt/sources.list.d/debian.sources
            apt-get update
        fi
        apt-get install -y --no-install-recommends $packageList
    """.trimIndent()
}

internal const val PROOT_SESSION_BUS_FILE = "/tmp/runtime-droiddesk/dbus-address"

internal fun prootSessionLauncherScript(): String = """
    #!/bin/sh
    set -eu
    install -d -m 0700 /tmp/runtime-droiddesk
    rm -f $PROOT_SESSION_BUS_FILE
    exec dbus-run-session -- sh -c '
        umask 077
        printf "%s\n" "${'$'}DBUS_SESSION_BUS_ADDRESS" > $PROOT_SESSION_BUS_FILE
        exec startxfce4
    '
""".trimIndent() + "\n"

internal fun prootBusAwareCommand(command: String): String = """
    if [ -r $PROOT_SESSION_BUS_FILE ]; then
        export DBUS_SESSION_BUS_ADDRESS="${'$'}(cat $PROOT_SESSION_BUS_FILE)"
    fi
    $command
""".trimIndent()

interface ProotDebianHost : ProotProvisioningHost, ProotDesktopSessionHost {
    fun readReadyMarker(): ProotReadyMarker?
    fun isRunning(): Boolean
    fun rootfsPath(): File
    fun rootfsSizeBytes(): Long
    fun optionalAppsStatus(): Map<String, Boolean>
    fun executeActive(
        user: String,
        command: String,
        onOutput: ((String) -> Unit)? = null,
        interactive: Boolean = false,
        stdin: Boolean = false,
    ): ExecutionResult

    override fun isDesktopReady(): Boolean =
        readReadyMarker() != null && rootfsPath().isDirectory

    override fun launchCommand(profile: DisplayProfile): List<String> =
        throw UnsupportedOperationException("PRoot desktop launch is unavailable")

    override fun applyProfile(
        profile: DisplayProfile,
        guestHome: String,
        policy: ProotDesktopPolicy,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("PRoot display profiles are unavailable"),
    )

    override fun waitForDesktopReady(timeoutMs: Long): Boolean = false
    override fun desktopProcessesAlive(): Boolean = false
}

internal class ProotDebianEnvironment(
    private val host: ProotDebianHost,
    private val provisioner: ProotDebianProvisioner = ProotDebianProvisioner(host),
    processRunner: EnvironmentProcessRunner = UnavailableEnvironmentProcessRunner,
    sessionController: ProotDebianSessionController? = null,
) : EnvironmentAdapter {
    override val kind = EnvironmentKind.PROOT_DEBIAN
    private val session = sessionController ?: ProotDebianSessionController(host, processRunner)
    private val displayProfiles = DesktopProfileExecutor(
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ProotDesktopProfiles").apply { isDaemon = true }
        },
        { task -> Handler(Looper.getMainLooper()).post(task) },
    )

    override fun status(capabilities: RuntimeCapabilities): EnvironmentStatus {
        val marker = host.readReadyMarker()
        val rootfs = host.rootfsPath()
        return EnvironmentStatus(
            schemaVersion = 1,
            kind = kind,
            installed = marker != null && rootfs.isDirectory,
            running = session.isRunning(),
            provisioning = false,
            repairNeeded = marker == null && rootfs.exists(),
            distro = marker?.let { "debian-${it.suite}" } ?: "debian",
            desktop = if (marker != null) "xfce4" else "",
            rootfsSizeBytes = host.rootfsSizeBytes(),
            capabilities = capabilities,
            lastError = session.lastError,
            rootfsPath = rootfs.absolutePath,
        )
    }

    override fun provision(
        request: ProvisionRequest,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = provisioner.provision(onProgress, onLog)

    override fun start(request: StartRequest): Result<Unit> =
        session.start(
            request.displayProfile ?: com.orailnoor.droiddesk.display.DisplayProfilePolicy.phoneDefaultProfile(),
        )

    override fun stop(): Result<Unit> = session.stop()

    override fun execute(request: ExecutionRequest): ExecutionResult = when (request) {
        is ExecutionRequest.Shell -> host.executeActive(
            "root",
            request.command,
            request.onOutput,
            request.interactive,
            request.stdin,
        )
        is ExecutionRequest.Desktop -> host.executeActive("droiddesk", request.command)
    }

    override fun optionalAppsStatus(): Map<String, Boolean> = host.optionalAppsStatus()

    override fun waitForDesktopReady(desktop: String, timeoutMs: Long): Boolean =
        session.waitForDesktopReady(timeoutMs)

    override fun applyDisplayProfile(
        profile: DisplayProfile,
        onComplete: (Result<Unit>) -> Unit,
    ) = displayProfiles.apply({ session.applyProfile(profile) }, onComplete)

    override fun cancelDisplayProfile() {
        displayProfiles.cancel()
    }

    override fun readClipboard(): String? {
        val result = host.executeActive(
            "droiddesk",
            "DISPLAY=:0 xclip -selection clipboard -o",
        )
        return result.output.takeIf { result.exitCode == 0 }
    }

    override fun writeClipboard(value: String): Boolean {
        val encoded = android.util.Base64.encodeToString(
            value.toByteArray(),
            android.util.Base64.NO_WRAP,
        )
        return host.executeActive(
            "droiddesk",
            "printf '%s' '$encoded' | base64 -d | DISPLAY=:0 xclip -selection clipboard -in",
        ).exitCode == 0
    }

    override fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Debian optional app installation is not configured: $appId"),
    )

    override fun paths(): EnvironmentPaths {
        val rootfs = host.rootfsPath()
        val home = File(rootfs, "home/droiddesk")
        return EnvironmentPaths(
            home = home,
            applicationDirectories = listOf(
                File(rootfs, "usr/share/applications"),
                File(home, ".local/share/applications"),
            ),
            packageStatusFiles = listOf(File(rootfs, "var/lib/dpkg/status")),
            snapshotMode = "proot-debian",
            guestHome = "/home/droiddesk",
        )
    }
}

internal class AndroidProotDebianHost(
    context: Context,
    private val linuxRuntime: LinuxRuntime,
) : ProotDebianHost {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val prefixDir = File(filesDir, "usr")
    private val hostHome = File(filesDir, "home")
    private val tmpDir = File(filesDir, "tmp")
    private val runtimeDir = File(prefixDir, "var/lib/proot-distro")
    private val markerDir = File(filesDir, "environments/proot-debian")
    private val markerFile = File(markerDir, "ready.properties")
    private val statusPreferences = appContext.getSharedPreferences(
        "proot_debian_status",
        Context.MODE_PRIVATE,
    )
    @Volatile private var cachedRootfsSizeBytes =
        statusPreferences.getLong(KEY_ROOTFS_SIZE_BYTES, -1L).takeIf { it >= 0 }
    @Volatile private var rootfsSizeRefreshedAt =
        statusPreferences.getLong(KEY_ROOTFS_SIZE_REFRESHED_AT, 0L)
    private val rootfsSizeRefreshInFlight = AtomicBoolean(false)
    private val commandBuilder = ProotCommandBuilder(
        prootDistro = File(prefixDir, "bin/proot-distro"),
        prefixDir = prefixDir,
        hostHome = hostHome,
        tmpDir = tmpDir,
        packageName = appContext.packageName,
        approvedHostRoots = listOf(filesDir),
    )

    override fun availableBytes(): Long = filesDir.usableSpace

    override fun ensureToolchain(
        onProgress: (Double, String) -> Unit,
        onLog: (String) -> Unit,
    ): Result<ProotToolchainVersions> = linuxRuntime.ensureProotToolchain(onProgress, onLog)

    override fun prepareHostSession(): Result<Unit> = linuxRuntime.startPulseAudio()

    override fun stopHostSession(): Result<Unit> = runCatching {
        linuxRuntime.stopHostProcess("dbus-daemon").getOrThrow()
        File(tmpDir, "runtime-droiddesk/dbus-address").let { address ->
            check(!address.exists() || address.delete()) {
                "Could not remove the retired desktop bus address"
            }
        }
        linuxRuntime.stopPulseAudio().getOrThrow()
    }

    override fun containerState(): ProotContainerState = when {
        readReadyMarker() != null && containerRootfs(ProotDebianProvisioner.ACTIVE_CONTAINER).isDirectory ->
            ProotContainerState.ACTIVE_READY
        containerRootfs(ProotDebianProvisioner.ACTIVE_CONTAINER).isDirectory ->
            ProotContainerState.ACTIVE_INCOMPLETE
        containerRootfs(ProotDebianProvisioner.STAGING_CONTAINER).isDirectory ->
            ProotContainerState.STAGING_INTERRUPTED
        containerRootfs(ProotDebianProvisioner.LEGACY_CONTAINER).isDirectory ->
            ProotContainerState.LEGACY_MINIMAL
        else -> ProotContainerState.ABSENT
    }

    override fun discardContainer(name: String): Result<Unit> {
        if (!containerRootfs(name).exists()) return Result.success(Unit)
        return hostCommand(listOf("remove", name)).requireSuccess("Could not remove staging container")
    }

    override fun installContainer(
        imageReference: String,
        name: String,
        onLog: (String) -> Unit,
    ): Result<ProotImageIdentity> = runCatching {
        val result = hostCommand(
            listOf(
                "install",
                "--name",
                name,
                "--architecture",
                "aarch64",
                imageReference,
            ),
            onLog,
        )
        check(result.exitCode == 0) { result.output }
        check(containerRootfs(name).isDirectory) { "PRoot-Distro did not create $name" }
        identity(name).getOrThrow()
    }

    override fun runRoot(
        container: String,
        command: String,
        onLog: (String) -> Unit,
    ): Result<Unit> =
        executeContainer(container, "root", command, onLog).requireSuccess(command)

    override fun installDesktopPackages(
        container: String,
        packages: List<String>,
        onLog: (String) -> Unit,
    ): Result<Unit> = runRoot(
        container,
        debianDesktopInstallCommand(packages),
        onLog,
    )

    override fun configureDesktopUser(
        container: String,
        onLog: (String) -> Unit,
    ): Result<Unit> = runRoot(
        container,
        """
        set -e
        id -u droiddesk >/dev/null 2>&1 || useradd -m -s /bin/bash droiddesk
        install -d -m 0755 /etc/sudoers.d
        printf 'droiddesk ALL=(ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/droiddesk
        chmod 0440 /etc/sudoers.d/droiddesk
        install -d -o droiddesk -g droiddesk \
          /home/droiddesk/Downloads /home/droiddesk/Documents /home/droiddesk/Pictures \
          /home/droiddesk/.config /home/droiddesk/.local/share/applications
        """.trimIndent(),
        onLog,
    )

    override fun verify(container: String): Result<ProotVerification> = runCatching {
        val arch = executeContainer(container, "root", "dpkg --print-architecture").also {
            check(it.exitCode == 0) { it.output }
        }.output.trim()
        val rootfs = containerRootfs(container)
        val installed = installedPackages(rootfs)
        ProotVerification(
            architecture = arch,
            desktopReady = File(rootfs, "usr/bin/xfce4-session").canExecute(),
            missingPackages = ProotDebianProvisioner.DESKTOP_PACKAGES.filterNot(installed::contains),
        )
    }

    override fun identity(container: String): Result<ProotImageIdentity> = runCatching {
        val rootfs = containerRootfs(container)
        check(rootfs.isDirectory) { "Container rootfs is missing: $container" }
        val suite = File(rootfs, "etc/os-release").takeIf(File::isFile)
            ?.readLines()
            ?.firstOrNull { it.startsWith("VERSION_CODENAME=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?.takeIf(String::isNotBlank)
            ?: "bookworm"
        val manifest = File(runtimeDir, "containers/$container/manifest.json")
        val digestSource = if (manifest.isFile) manifest else File(rootfs, "etc/os-release")
        val pinnedDigest = ProotDebianProvisioner.IMAGE_REFERENCE
            .substringAfter('@', missingDelimiterValue = "")
        ProotImageIdentity(
            reference = ProotDebianProvisioner.IMAGE_REFERENCE,
            suite = suite,
            digest = pinnedDigest.ifEmpty { "sha256:${sha256(digestSource)}" },
        )
    }

    override fun activate(container: String): Result<Unit> {
        if (container == ProotDebianProvisioner.ACTIVE_CONTAINER) return Result.success(Unit)
        val backup = "${ProotDebianProvisioner.ACTIVE_CONTAINER}-backup-${System.currentTimeMillis()}"
        val activeExists = containerRootfs(ProotDebianProvisioner.ACTIVE_CONTAINER).isDirectory
        if (activeExists) {
            val preserved = hostCommand(
                listOf("rename", ProotDebianProvisioner.ACTIVE_CONTAINER, backup),
            )
            if (preserved.exitCode != 0) {
                return Result.failure(IllegalStateException(preserved.output))
            }
        }
        val activated = hostCommand(
            listOf("rename", container, ProotDebianProvisioner.ACTIVE_CONTAINER),
        )
        if (activated.exitCode == 0) return Result.success(Unit)
        if (activeExists) hostCommand(listOf("rename", backup, ProotDebianProvisioner.ACTIVE_CONTAINER))
        return Result.failure(IllegalStateException(activated.output))
    }

    override fun writeReadyMarker(marker: ProotReadyMarker): Result<Unit> = runCatching {
        markerDir.mkdirs()
        val temporary = File(markerDir, "ready.properties.tmp")
        val properties = Properties().apply {
            setProperty("schema", marker.schema.toString())
            setProperty("suite", marker.suite)
            setProperty("imageReference", marker.imageReference)
            setProperty("imageDigest", marker.imageDigest)
            setProperty("packageSetVersion", marker.packageSetVersion.toString())
            setProperty("prootVersion", marker.prootVersion)
            setProperty("prootDistroVersion", marker.prootDistroVersion)
        }
        FileOutputStream(temporary).use { properties.store(it, null) }
        check(temporary.renameTo(markerFile)) { "Could not activate Debian ready marker" }
        refreshRootfsSize(force = true)
    }

    override fun readReadyMarker(): ProotReadyMarker? = runCatching {
        if (!markerFile.isFile) return null
        val properties = Properties()
        FileInputStream(markerFile).use(properties::load)
        ProotReadyMarker(
            schema = properties.getProperty("schema").toInt(),
            suite = properties.getProperty("suite"),
            imageReference = properties.getProperty("imageReference"),
            imageDigest = properties.getProperty("imageDigest"),
            packageSetVersion = properties.getProperty("packageSetVersion").toInt(),
            prootVersion = properties.getProperty("prootVersion"),
            prootDistroVersion = properties.getProperty("prootDistroVersion"),
        ).takeIf {
            it.schema == ProotDebianProvisioner.READY_SCHEMA &&
                it.packageSetVersion == ProotDebianProvisioner.PACKAGE_SET_VERSION
        }
    }.getOrNull()

    override fun isRunning(): Boolean = false

    override fun rootfsPath(): File = containerRootfs(ProotDebianProvisioner.ACTIVE_CONTAINER)

    override fun rootfsSizeBytes(): Long {
        val rootfs = rootfsPath()
        if (!rootfs.isDirectory) {
            cachedRootfsSizeBytes = 0
            return 0
        }
        refreshRootfsSize()
        return cachedRootfsSizeBytes ?: 0
    }

    private fun refreshRootfsSize(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force &&
            cachedRootfsSizeBytes != null &&
            now - rootfsSizeRefreshedAt < ROOTFS_SIZE_CACHE_TTL_MS
        ) {
            return
        }
        if (!rootfsSizeRefreshInFlight.compareAndSet(false, true)) return
        ROOTFS_SIZE_EXECUTOR.execute {
            try {
                val size = rootfsPath()
                    .takeIf(File::isDirectory)
                    ?.walkTopDown()
                    ?.filter(File::isFile)
                    ?.sumOf(File::length)
                    ?: 0L
                val refreshedAt = System.currentTimeMillis()
                cachedRootfsSizeBytes = size
                rootfsSizeRefreshedAt = refreshedAt
                statusPreferences.edit()
                    .putLong(KEY_ROOTFS_SIZE_BYTES, size)
                    .putLong(KEY_ROOTFS_SIZE_REFRESHED_AT, refreshedAt)
                    .apply()
            } finally {
                rootfsSizeRefreshInFlight.set(false)
            }
        }
    }

    override fun optionalAppsStatus(): Map<String, Boolean> {
        val rootfs = rootfsPath()
        return mapOf(
            "firefox" to File(rootfs, "usr/bin/firefox").exists(),
            "code_oss" to (
                File(rootfs, "usr/bin/code").exists() ||
                    File(rootfs, "usr/bin/code-oss").exists()
                ),
            "nodejs" to File(rootfs, "usr/bin/node").exists(),
            "imagemagick" to (
                File(rootfs, "usr/bin/magick").exists() ||
                    File(rootfs, "usr/bin/convert").exists()
                ),
        )
    }

    override fun executeActive(
        user: String,
        command: String,
        onOutput: ((String) -> Unit)?,
        interactive: Boolean,
        stdin: Boolean,
    ): ExecutionResult =
        executeContainer(
            ProotDebianProvisioner.ACTIVE_CONTAINER,
            user,
            command,
            onOutput,
            interactive,
            stdin,
        )

    override fun launchCommand(profile: DisplayProfile): List<String> {
        val binds = sharedFolderBinds()
        return commandBuilder.login(
            container = ProotDebianProvisioner.ACTIVE_CONTAINER,
            arguments = listOf("/usr/local/bin/droiddesk-start-xfce"),
            environment = mapOf(
                "DISPLAY" to ":0",
                "PULSE_SERVER" to "unix:/tmp/pulse/native",
                "XDG_RUNTIME_DIR" to "/tmp/runtime-droiddesk",
                "LIBGL_ALWAYS_SOFTWARE" to "1",
            ),
            binds = binds,
            user = "droiddesk",
        )
    }

    override fun applyProfile(
        profile: DisplayProfile,
        guestHome: String,
        policy: ProotDesktopPolicy,
    ): Result<Unit> = runCatching {
        val rootfs = rootfsPath()
        val home = File(rootfs, guestHome.removePrefix("/"))
        File(rootfs, "usr/local/bin/droiddesk-start-xfce").apply {
            parentFile?.mkdirs()
            writeText(prootSessionLauncherScript())
            setExecutable(true, false)
        }
        val wallpaper = File(home, ".local/share/backgrounds/droiddesk-sunset-wave.jpg")
        check(
            XfceMobileProfile.install(
                context = appContext,
                homeDir = home,
                wallpaperFile = wallpaper,
                wallpaperPathInSession = "$guestHome/.local/share/backgrounds/${wallpaper.name}",
            ),
        ) { "Could not install Debian XFCE profile" }
        File(home, ".config/droiddesk/session-policy").apply {
            parentFile?.mkdirs()
            writeText(
                "maxRefreshHz=${policy.maxRefreshHz}\n" +
                    "compositorEnabled=${policy.compositorEnabled}\n",
            )
        }
        val profileScript = if (desktopProcessesAlive()) {
            XfceDisplayProfile.buildApplyScript(profile)
        } else {
            XfceDisplayProfile.buildStageScript(profile)
        }
        val staged = executeActive(
            "droiddesk",
            profileScript,
        )
        check(staged.exitCode == 0) { staged.output }
    }

    override fun waitForDesktopReady(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (desktopProcessesAlive()) return true
            Thread.sleep(100)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return false
    }

    override fun desktopProcessesAlive(): Boolean =
        linuxRuntime.hostProcessesAlive(listOf("xfce4-session", "xfce4-panel"))

    private fun executeContainer(
        container: String,
        user: String,
        command: String,
        onOutput: ((String) -> Unit)? = null,
        interactive: Boolean = false,
        stdin: Boolean = false,
    ): ExecutionResult {
        val binds = sharedFolderBinds()
        val args = commandBuilder.login(
            container = container,
            arguments = listOf("bash", "-lc", prootBusAwareCommand(command)),
            environment = mapOf(
                "DISPLAY" to ":0",
                "PULSE_SERVER" to "unix:/tmp/pulse/native",
                "XDG_RUNTIME_DIR" to "/tmp/runtime-droiddesk",
            ),
            binds = binds,
            user = user,
        )
        return linuxRuntime.executeCommandArgs(
            args,
            onOutput,
            interactive,
            command.takeIf { stdin },
        )
    }

    private fun sharedFolderBinds(): List<ProotBind> = listOf(
            "Downloads",
            "Documents",
            "Pictures",
        ).map { name ->
            File(hostHome, name).mkdirs()
            ProotBind(File(hostHome, name), "/home/droiddesk/$name")
        }

    private fun hostCommand(
        arguments: List<String>,
        onOutput: ((String) -> Unit)? = null,
    ): ExecutionResult = linuxRuntime.executeCommandArgs(commandBuilder.tool(arguments), onOutput)

    private fun ExecutionResult.requireSuccess(operation: String): Result<Unit> =
        if (exitCode == 0) Result.success(Unit)
        else Result.failure(IllegalStateException("$operation failed: $output"))

    private fun containerRootfs(name: String): File {
        val current = File(runtimeDir, "containers/$name/rootfs")
        if (current.exists()) return current
        return File(runtimeDir, "installed-rootfs/$name")
    }

    private companion object {
        const val KEY_ROOTFS_SIZE_BYTES = "rootfs_size_bytes"
        const val KEY_ROOTFS_SIZE_REFRESHED_AT = "rootfs_size_refreshed_at"
        const val ROOTFS_SIZE_CACHE_TTL_MS = 10 * 60 * 1000L
        val ROOTFS_SIZE_EXECUTOR = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ProotRootfsSize").apply { isDaemon = true }
        }
    }

    private fun installedPackages(rootfs: File): Set<String> {
        val status = File(rootfs, "var/lib/dpkg/status")
        if (!status.isFile) return emptySet()
        return status.readText().split("\n\n").mapNotNull { block ->
            val fields = block.lineSequence().mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null
                else line.substring(0, split) to line.substring(split + 1).trim()
            }.toMap()
            fields["Package"]?.takeIf { fields["Status"] == "install ok installed" }
        }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
