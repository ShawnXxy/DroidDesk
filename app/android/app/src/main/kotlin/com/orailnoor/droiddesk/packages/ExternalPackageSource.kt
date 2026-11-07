package com.orailnoor.droiddesk.packages

import java.io.File
import java.security.MessageDigest

internal data class SourceVerification(
    val architecture: String,
    val version: String,
    val sha256: String?,
    val keyFingerprint: String?,
)

internal interface ExternalSourceInspector {
    fun download(uri: String): ByteArray
    fun keyFingerprints(keyData: ByteArray): Set<String>
    fun dearmor(keyData: ByteArray): ByteArray
    fun inspectDeb(packageData: ByteArray): SourceVerification
}

internal class ExternalPackageSourceException(
    val code: String,
    message: String,
) : IllegalStateException(message)

internal class ExternalPackageSource(
    private val rootfsDir: File,
    private val inspector: ExternalSourceInspector,
) {
    fun configureSignedRepository(
        source: PackageRecipeSource.SignedAptRepository,
    ) {
        verifyArm64(source.architecture)
        val keyData = inspector.download(source.keyUri)
        val fingerprints = inspector.keyFingerprints(keyData)
            .map(String::uppercase)
            .toSet()
        if (source.keyFingerprint.uppercase() !in fingerprints) {
            reject("Official signing key fingerprint did not match ${source.sourceId}")
        }
        val keyringPath = "/usr/share/keyrings/droiddesk-${source.sourceId}.gpg"
        atomicWrite(
            File(rootfsDir, keyringPath.removePrefix("/")),
            inspector.dearmor(keyData),
        )
        val sourceText = """
            Types: deb
            URIs: ${source.sourceUri}
            Suites: ${source.suite}
            Components: ${source.components.joinToString(" ")}
            Architectures: arm64
            Signed-By: $keyringPath
        """.trimIndent() + "\n"
        atomicWrite(
            File(
                rootfsDir,
                "etc/apt/sources.list.d/droiddesk-${source.sourceId}.sources",
            ),
            sourceText.toByteArray(),
        )
    }

    fun downloadAndVerifyPinnedDeb(
        source: PackageRecipeSource.PinnedDeb,
    ): File {
        verifyArm64(source.architecture)
        val packageData = inspector.download(source.uri)
        val actual = inspector.inspectDeb(packageData)
        verifyArm64(actual.architecture)
        if (actual.version != source.version) {
            reject(
                "Pinned package version mismatch: expected ${source.version}, " +
                    "received ${actual.version}",
            )
        }
        val digest = sha256(packageData)
        if (!digest.equals(source.sha256, ignoreCase = true)) {
            reject(
                "Pinned package SHA-256 mismatch: expected ${source.sha256}, received $digest",
            )
        }
        val fileName = source.uri.substringAfterLast('/').takeIf(String::isNotBlank)
            ?: reject("Pinned package URI has no file name")
        return File(rootfsDir, "var/cache/droiddesk-packages/$fileName").also { file ->
            atomicWrite(file, packageData)
        }
    }

    private fun verifyArm64(architecture: String) {
        if (architecture != "arm64") {
            reject("External package architecture must be arm64, received $architecture")
        }
    }

    private fun atomicWrite(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(data)
        if (file.exists() && !file.delete()) {
            temporary.delete()
            reject("Could not replace ${file.absolutePath}")
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            reject("Could not activate ${file.absolutePath}")
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun reject(message: String): Nothing =
        throw ExternalPackageSourceException("SOURCE_VERIFICATION_FAILED", message)
}
