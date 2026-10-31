package app.logdate

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests [ReleaseSigningPolicy]: which key each build type signs with, and when a release package must
 * not be built.
 */
class ReleaseSigningPolicyTest {
    @Test
    fun `release and dogfood sign with the upload key when this machine has it`() {
        assertEquals(SigningKey.UPLOAD, ReleaseSigningPolicy.keyFor("release", hasUploadKey = true))
        assertEquals(SigningKey.UPLOAD, ReleaseSigningPolicy.keyFor("dogfood", hasUploadKey = true))
    }

    @Test
    fun `without the upload key every build type signs with the debug key`() {
        listOf("release", "dogfood", "debug", "benchmark").forEach { buildType ->
            assertEquals(SigningKey.DEBUG, ReleaseSigningPolicy.keyFor(buildType, hasUploadKey = false), buildType)
        }
    }

    @Test
    fun `debug, benchmark and baseline profile builds always sign with the debug key`() {
        listOf("debug", "benchmark", "nonMinifiedRelease", "benchmarkRelease").forEach { buildType ->
            assertEquals(SigningKey.DEBUG, ReleaseSigningPolicy.keyFor(buildType, hasUploadKey = true), buildType)
        }
    }

    @Test
    fun `a release package may be built with the upload key`() {
        assertNull(ReleaseSigningPolicy.releaseBlocker(hasUploadKey = true, allowDebugReleaseSigning = false))
    }

    @Test
    fun `a release package may be debug signed only when that is explicitly allowed`() {
        assertNull(ReleaseSigningPolicy.releaseBlocker(hasUploadKey = false, allowDebugReleaseSigning = true))

        val blocker = assertNotNull(ReleaseSigningPolicy.releaseBlocker(hasUploadKey = false, allowDebugReleaseSigning = false))
        assertContains(blocker, "LOGDATE_RELEASE_STORE_FILE")
        assertContains(blocker, "-Plogdate.allowDebugReleaseSigning=true")
    }

    @Test
    fun `the keystore script's env file is read from the signing directory`() {
        assertEquals(
            "/Users/someone/.logdate-signing/production-upload.env",
            ReleaseSigningPolicy.envFilePath(signingDirectory = null, userHome = "/Users/someone"),
        )
        assertEquals(
            "/keys/production-upload.env",
            ReleaseSigningPolicy.envFilePath(signingDirectory = "/keys", userHome = "/Users/someone"),
        )
    }

    @Test
    fun `signing values resolve from lookups as they do from a map`() {
        val environment = mapOf("LOGDATE_RELEASE_STORE_FILE" to "/env.jks", "LOGDATE_RELEASE_STORE_PASSWORD" to "p1")
        val properties = mapOf("logdate.release.keyAlias" to "upload")
        val envFile = mapOf("LOGDATE_RELEASE_KEY_PASSWORD" to "p2")

        assertEquals(
            ReleaseSigning("/env.jks", "p1", "upload", "p2"),
            ReleaseSigningResolver.resolve(environment::get, properties::get, envFile),
        )
    }
}
