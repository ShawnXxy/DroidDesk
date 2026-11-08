package com.orailnoor.droiddesk.packages

internal sealed interface PackageRecipeSource {
    data object DebianBookworm : PackageRecipeSource

    data class SignedAptRepository(
        val sourceId: String,
        val sourceUri: String,
        val suite: String,
        val components: List<String>,
        val keyUri: String,
        val keyFingerprint: String,
        val packageName: String,
        val version: String,
        val architecture: String,
        val sha256: String,
    ) : PackageRecipeSource {
        init {
            require(sourceId.matches(ID)) { "Invalid package source ID" }
            require(sourceUri.startsWith("https://")) { "Package source must use HTTPS" }
            require(keyUri.startsWith("https://")) { "Package key source must use HTTPS" }
            require(suite.isNotBlank()) { "Package source suite must not be blank" }
            require(components.isNotEmpty() && components.none(String::isBlank)) {
                "Package source components must not be empty"
            }
            require(keyFingerprint.matches(FINGERPRINT)) {
                "Package source key fingerprint must be 40 hexadecimal characters"
            }
            require(packageName.matches(PACKAGE_NAME)) { "Invalid repository package name" }
            require(version.isNotBlank()) { "Repository package version must not be blank" }
            require(architecture.isNotBlank()) { "Repository package architecture is required" }
            require(sha256.matches(SHA256)) {
                "Repository package SHA-256 must be 64 hexadecimal characters"
            }
        }
    }

    data class PinnedDeb(
        val uri: String,
        val version: String,
        val architecture: String,
        val sha256: String,
    ) : PackageRecipeSource {
        init {
            require(uri.startsWith("https://")) { "Pinned package must use HTTPS" }
            require(version.isNotBlank()) { "Pinned package version must not be blank" }
            require(architecture == "arm64") { "Pinned package architecture must be arm64" }
            require(sha256.matches(SHA256)) {
                "Pinned package SHA-256 must be 64 hexadecimal characters"
            }
        }
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9-]*")
        private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]*")
        private val FINGERPRINT = Regex("[A-Fa-f0-9]{40}")
        private val SHA256 = Regex("[A-Fa-f0-9]{64}")
    }
}

internal data class PackageLaunchPolicy(
    val executable: String,
    val arguments: List<String> = emptyList(),
) {
    init {
        require(executable.matches(PACKAGE_NAME)) {
            "Launch executable must be a fixed recipe-owned name"
        }
        require(arguments.none { it.contains('\n') || it.contains('\r') }) {
            "Launch arguments must not contain line breaks"
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9+._-]*")
    }
}

internal data class PackageRecipe(
    val entryId: String,
    val displayName: String,
    val summary: String,
    val aliases: Set<String>,
    val category: String,
    val iconNames: List<String>,
    val gui: Boolean,
    val supportLevel: PackageSupportLevel,
    val source: PackageRecipeSource,
    val packageNames: List<String>,
    val primaryPackage: String,
    val launchPolicy: PackageLaunchPolicy?,
    val limitations: List<String>,
    val removable: Boolean = true,
    val verificationExecutables: List<String> =
        launchPolicy?.let { listOf(it.executable) }.orEmpty(),
) {
    init {
        require(entryId.matches(ENTRY_ID)) { "Invalid package recipe entry ID: $entryId" }
        require(displayName.isNotBlank()) { "Recipe display name must not be blank" }
        require(summary.isNotBlank()) { "Recipe summary must not be blank" }
        require(aliases.isNotEmpty() && aliases.none(String::isBlank)) {
            "Recipe aliases must not be empty"
        }
        require(category.isNotBlank()) { "Recipe category must not be blank" }
        require(iconNames.isNotEmpty() && iconNames.none(String::isBlank)) {
            "Recipe icon names must not be empty"
        }
        require(packageNames.isNotEmpty() && packageNames.all(PACKAGE_NAME::matches)) {
            "Recipe package names are invalid"
        }
        require(primaryPackage in packageNames) {
            "Recipe primary package must be included in package names"
        }
        require(limitations.none(String::isBlank)) { "Recipe limitations must not be blank" }
        require(verificationExecutables.isNotEmpty()) {
            "Recipe executable verification probes must not be empty"
        }
    }

    private companion object {
        val ENTRY_ID = Regex("(app|tool)\\.[a-z0-9-]+")
        val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+.-]*")
    }
}

internal object ProotPackageRecipes {
    val all: List<PackageRecipe> = listOf(
        PackageRecipe(
            entryId = "app.firefox",
            displayName = "Firefox ESR",
            summary = "Extended-support desktop web browser.",
            aliases = setOf("browser", "web", "mozilla"),
            category = "Internet",
            iconNames = listOf("firefox-esr", "firefox"),
            gui = true,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("firefox-esr"),
            primaryPackage = "firefox-esr",
            launchPolicy = PackageLaunchPolicy("firefox-esr"),
            limitations = listOf("Software rendering is the supported baseline."),
        ),
        PackageRecipe(
            entryId = "app.libreoffice",
            displayName = "LibreOffice",
            summary = "Documents, spreadsheets, and presentations.",
            aliases = setOf("office", "writer", "calc", "documents"),
            category = "Office",
            iconNames = listOf("libreoffice-startcenter", "libreoffice-main"),
            gui = true,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("libreoffice"),
            primaryPackage = "libreoffice",
            launchPolicy = PackageLaunchPolicy("libreoffice"),
            limitations = emptyList(),
        ),
        PackageRecipe(
            entryId = "app.gimp",
            displayName = "GIMP",
            summary = "Image editing and design tools.",
            aliases = setOf("image", "photo", "graphics", "editor"),
            category = "Graphics",
            iconNames = listOf("gimp"),
            gui = true,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("gimp"),
            primaryPackage = "gimp",
            launchPolicy = PackageLaunchPolicy("gimp"),
            limitations = emptyList(),
        ),
        PackageRecipe(
            entryId = "app.vlc",
            displayName = "VLC",
            summary = "Audio and video player.",
            aliases = setOf("media", "video", "audio", "player"),
            category = "Multimedia",
            iconNames = listOf("vlc"),
            gui = true,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("vlc"),
            primaryPackage = "vlc",
            launchPolicy = PackageLaunchPolicy("vlc"),
            limitations = listOf("Playback depends on codecs available in Debian Bookworm."),
        ),
        PackageRecipe(
            entryId = "tool.nodejs",
            displayName = "Node.js + npm",
            summary = "JavaScript runtime and package manager.",
            aliases = setOf("javascript", "js", "npm", "development"),
            category = "Development",
            iconNames = listOf("nodejs", "node"),
            gui = false,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("nodejs", "npm"),
            primaryPackage = "nodejs",
            launchPolicy = null,
            limitations = emptyList(),
            verificationExecutables = listOf("node", "npm"),
        ),
        PackageRecipe(
            entryId = "tool.python",
            displayName = "Python",
            summary = "Python, pip, and isolated virtual environments.",
            aliases = setOf("python3", "pip", "venv", "development"),
            category = "Development",
            iconNames = listOf("python", "python3"),
            gui = false,
            supportLevel = PackageSupportLevel.SUPPORTED,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("python3", "python3-pip", "python3-venv"),
            primaryPackage = "python3",
            launchPolicy = null,
            limitations = emptyList(),
            removable = false,
            verificationExecutables = listOf("python3", "pip3"),
        ),
        PackageRecipe(
            entryId = "app.chromium",
            displayName = "Chromium",
            summary = "Open-source Chromium web browser.",
            aliases = setOf("browser", "web", "chrome"),
            category = "Internet",
            iconNames = listOf("chromium"),
            gui = true,
            supportLevel = PackageSupportLevel.EXPERIMENTAL,
            source = PackageRecipeSource.DebianBookworm,
            packageNames = listOf("chromium"),
            primaryPackage = "chromium",
            launchPolicy = PackageLaunchPolicy(
                executable = "chromium",
                arguments = listOf("--disable-gpu", "--disable-dev-shm-usage"),
            ),
            limitations = listOf("Chromium sandbox validation is required before installation."),
        ),
        PackageRecipe(
            entryId = "app.chrome",
            displayName = "Google Chrome",
            summary = "Google Chrome stable for ARM64 Linux.",
            aliases = setOf("browser", "web", "google"),
            category = "Internet",
            iconNames = listOf("google-chrome"),
            gui = true,
            supportLevel = PackageSupportLevel.EXPERIMENTAL,
            source = PackageRecipeSource.SignedAptRepository(
                sourceId = "google-chrome",
                sourceUri = "https://dl.google.com/linux/chrome/deb/",
                suite = "stable",
                components = listOf("main"),
                keyUri = "https://dl.google.com/linux/linux_signing_key.pub",
                keyFingerprint = "EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796",
                packageName = "google-chrome-stable",
                version = "157.0.8103.38-1",
                architecture = "arm64",
                sha256 = "ea1b98eda5ff948be07e52237e07bbcc8f7dedc3460b693f6bf29e62e89c3bd6",
            ),
            packageNames = listOf("google-chrome-stable"),
            primaryPackage = "google-chrome-stable",
            launchPolicy = PackageLaunchPolicy(
                executable = "google-chrome-stable",
                arguments = listOf("--disable-gpu", "--disable-dev-shm-usage"),
            ),
            limitations = listOf(
                "The Chromium sandbox must pass on both acceptance devices.",
            ),
        ),
        PackageRecipe(
            entryId = "app.vscode",
            displayName = "Visual Studio Code",
            summary = "Official Microsoft ARM64 desktop code editor.",
            aliases = setOf("code", "editor", "ide", "development"),
            category = "Development",
            iconNames = listOf("visual-studio-code", "vscode", "code"),
            gui = true,
            supportLevel = PackageSupportLevel.EXPERIMENTAL,
            source = PackageRecipeSource.PinnedDeb(
                uri = "https://packages.microsoft.com/repos/code/pool/main/c/code/" +
                    "code_1.145.2-1793945203_arm64.deb",
                version = "1.145.2-1793945203",
                architecture = "arm64",
                sha256 = "a224279b3651eac641bae9e9c9bd3977d93c46af799393f3f368aa1ec55c7c7f",
            ),
            packageNames = listOf("code"),
            primaryPackage = "code",
            launchPolicy = PackageLaunchPolicy(
                executable = "code",
                arguments = listOf("--disable-gpu", "--disable-dev-shm-usage"),
            ),
            limitations = listOf(
                "Electron sandbox and memory behavior require device acceptance.",
            ),
        ),
    )

    private val byId = all.associateBy(PackageRecipe::entryId)

    init {
        require(byId.size == all.size) { "Package recipe IDs must be unique" }
    }

    fun find(entryId: String): PackageRecipe? = byId[entryId]

    fun require(entryId: String): PackageRecipe =
        checkNotNull(find(entryId)) { "Unknown package recipe: $entryId" }

    fun search(query: String): List<PackageRecipe> {
        val terms = query.trim().lowercase()
        if (terms.isEmpty()) return all
        return all.filter { recipe ->
            sequenceOf(
                recipe.entryId,
                recipe.displayName,
                recipe.summary,
                recipe.category,
                recipe.aliases.joinToString(" "),
            ).any { it.lowercase().contains(terms) }
        }
    }
}
