package com.orailnoor.droiddesk.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ExternalPackageSourceTest {

    @Test
    fun rejectsKeyFingerprintMismatch() {
        val rootfs = Files.createTempDirectory("external-source").toFile()
        val inspector = FakeExternalSourceInspector().apply {
            fingerprints = setOf("0000000000000000000000000000000000000000")
        }
        val installer = ExternalPackageSource(rootfs, inspector)

        val error = assertThrows(ExternalPackageSourceException::class.java) {
            installer.configureSignedRepository(googleSource())
        }

        assertEquals("SOURCE_VERIFICATION_FAILED", error.code)
        assertTrue(error.message!!.contains("fingerprint", ignoreCase = true))
    }

    @Test
    fun rejectsNonArm64RepositoryOrDeb() {
        val rootfs = Files.createTempDirectory("external-source").toFile()
        val inspector = FakeExternalSourceInspector().apply {
            fingerprints = setOf(GOOGLE_FINGERPRINT)
            debVerification = SourceVerification(
                architecture = "amd64",
                version = MICROSOFT_VERSION,
                sha256 = MICROSOFT_SHA256,
                keyFingerprint = null,
            )
        }
        val installer = ExternalPackageSource(rootfs, inspector)

        val repositoryError = assertThrows(ExternalPackageSourceException::class.java) {
            installer.configureSignedRepository(
                googleSource().copy(architecture = "amd64"),
            )
        }
        val debError = assertThrows(ExternalPackageSourceException::class.java) {
            installer.downloadAndVerifyPinnedDeb(microsoftSource())
        }

        assertEquals("SOURCE_VERIFICATION_FAILED", repositoryError.code)
        assertEquals("SOURCE_VERIFICATION_FAILED", debError.code)
    }

    @Test
    fun rejectsPinnedVersionMismatch() {
        val inspector = FakeExternalSourceInspector().apply {
            debVerification = SourceVerification(
                architecture = "arm64",
                version = "1.0.0",
                sha256 = MICROSOFT_SHA256,
                keyFingerprint = null,
            )
        }
        val installer = ExternalPackageSource(
            Files.createTempDirectory("external-source").toFile(),
            inspector,
        )

        val error = assertThrows(ExternalPackageSourceException::class.java) {
            installer.downloadAndVerifyPinnedDeb(microsoftSource())
        }

        assertEquals("SOURCE_VERIFICATION_FAILED", error.code)
        assertTrue(error.message!!.contains("version", ignoreCase = true))
    }

    @Test
    fun rejectsPinnedSha256Mismatch() {
        val inspector = FakeExternalSourceInspector().apply {
            downloadedBytes = "different package".toByteArray()
            debVerification = SourceVerification(
                architecture = "arm64",
                version = MICROSOFT_VERSION,
                sha256 = null,
                keyFingerprint = null,
            )
        }
        val installer = ExternalPackageSource(
            Files.createTempDirectory("external-source").toFile(),
            inspector,
        )

        val error = assertThrows(ExternalPackageSourceException::class.java) {
            installer.downloadAndVerifyPinnedDeb(microsoftSource())
        }

        assertEquals("SOURCE_VERIFICATION_FAILED", error.code)
        assertTrue(error.message!!.contains("SHA-256"))
    }

    @Test
    fun writesDedicatedSignedBySourceWithoutAptKey() {
        val rootfs = Files.createTempDirectory("external-source").toFile()
        val inspector = FakeExternalSourceInspector().apply {
            fingerprints = setOf(GOOGLE_FINGERPRINT)
        }
        val installer = ExternalPackageSource(rootfs, inspector)

        installer.configureSignedRepository(googleSource())

        val source = File(rootfs, "etc/apt/sources.list.d/droiddesk-google-chrome.sources")
        val keyring = File(rootfs, "usr/share/keyrings/droiddesk-google-chrome.gpg")
        assertTrue(source.isFile)
        assertTrue(keyring.isFile)
        assertTrue(source.readText().contains("Architectures: arm64"))
        assertTrue(
            source.readText().contains(
                "Signed-By: /usr/share/keyrings/droiddesk-google-chrome.gpg",
            ),
        )
        assertFalse(source.readText().contains("apt-key"))
    }

    @Test
    fun doesNotOverwriteUnrelatedDebianSources() {
        val rootfs = Files.createTempDirectory("external-source").toFile()
        val unrelated = File(rootfs, "etc/apt/sources.list.d/debian.sources").apply {
            parentFile!!.mkdirs()
            writeText("Types: deb\nURIs: https://deb.debian.org/debian\n")
        }
        val installer = ExternalPackageSource(
            rootfs,
            FakeExternalSourceInspector().apply {
                fingerprints = setOf(GOOGLE_FINGERPRINT)
            },
        )

        installer.configureSignedRepository(googleSource())

        assertEquals(
            "Types: deb\nURIs: https://deb.debian.org/debian\n",
            unrelated.readText(),
        )
    }

    private fun googleSource() = PackageRecipeSource.SignedAptRepository(
        sourceId = "google-chrome",
        sourceUri = "https://dl.google.com/linux/chrome/deb/",
        suite = "stable",
        components = listOf("main"),
        keyUri = "https://dl.google.com/linux/linux_signing_key.pub",
        keyFingerprint = GOOGLE_FINGERPRINT,
        packageName = "google-chrome-stable",
        version = GOOGLE_VERSION,
        architecture = "arm64",
        sha256 = GOOGLE_SHA256,
    )

    private fun microsoftSource() = PackageRecipeSource.PinnedDeb(
        uri = "https://packages.microsoft.com/repos/code/pool/main/c/code/" +
            "code_${MICROSOFT_VERSION}_arm64.deb",
        version = MICROSOFT_VERSION,
        architecture = "arm64",
        sha256 = MICROSOFT_SHA256,
    )

    private companion object {
        const val GOOGLE_FINGERPRINT = "EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796"
        const val GOOGLE_VERSION = "157.0.8103.38-1"
        const val GOOGLE_SHA256 =
            "ea1b98eda5ff948be07e52237e07bbcc8f7dedc3460b693f6bf29e62e89c3bd6"
        const val MICROSOFT_VERSION = "1.145.2-1793945203"
        const val MICROSOFT_SHA256 =
            "a224279b3651eac641bae9e9c9bd3977d93c46af799393f3f368aa1ec55c7c7f"
    }
}

private class FakeExternalSourceInspector : ExternalSourceInspector {
    var downloadedBytes = "official package".toByteArray()
    var fingerprints: Set<String> = emptySet()
    var debVerification = SourceVerification(
        architecture = "arm64",
        version = "1.145.2-1793945203",
        sha256 = null,
        keyFingerprint = null,
    )

    override fun download(uri: String): ByteArray = downloadedBytes

    override fun keyFingerprints(keyData: ByteArray): Set<String> = fingerprints

    override fun dearmor(keyData: ByteArray): ByteArray = "dearmored".toByteArray()

    override fun inspectDeb(packageData: ByteArray): SourceVerification =
        debVerification.copy(sha256 = sha256(packageData))

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
}
