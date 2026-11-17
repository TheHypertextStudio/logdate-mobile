package app.logdate.server.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [EncryptionService], the high-level orchestrator that applies
 * [EncryptionPolicy] to incoming and outgoing data streams.
 *
 * This suite validates the service's role as a policy enforcer:
 * - Correct application of "at-rest" and "end-to-end" encryption requirements
 *   during media and backup uploads.
 * - Verification of the "encrypted" metadata flag returned to clients after
 *   processing.
 * - Enforcement of rejection policies when plaintext is submitted to a
 *   required-encryption environment.
 * - Proper handling of decryption branches during data retrieval, ensuring
 *   that the active policy determines whether data is returned as-is or decrypted.
 */
class EncryptionServiceTest {
    @Test
    fun `encryption service exposes processed payload encryption flag`() {
        val policy =
            EncryptionPolicy(
                mode = EncryptionMode.AT_REST_ONLY,
                serverEncryptionEnabled = false,
                allowPassthroughClientCiphertext = true,
            )
        val service = EncryptionService(policy = policy, codec = PayloadCodec(NoOpKeyring))

        val processed = service.processMediaUpload("plain".toByteArray())
        assertFalse(processed.encrypted)
    }

    @Test
    fun `reject policy throws encryption policy exception`() {
        val policy =
            EncryptionPolicy(
                mode = EncryptionMode.E2EE_REQUIRED,
                serverEncryptionEnabled = true,
                allowPassthroughClientCiphertext = false,
            )
        val service = EncryptionService(policy = policy, codec = PayloadCodec(NoOpKeyring))

        val error =
            assertFailsWith<EncryptionPolicyException> {
                service.processMediaUpload("plain".toByteArray())
            }
        assertEquals("E2EE required: plaintext not allowed", error.message)
    }

    @Test
    fun `backup upload and download branches preserve plaintext when decrypt disabled`() {
        val policy =
            EncryptionPolicy(
                mode = EncryptionMode.AT_REST_ONLY,
                serverEncryptionEnabled = true,
                allowPassthroughClientCiphertext = true,
            )
        val service = EncryptionService(policy = policy, codec = PayloadCodec(NoOpKeyring))
        val payload = "backup-plain".toByteArray()

        val uploaded = service.processBackupUpload(payload)
        assertTrue(uploaded.encrypted)

        val passthrough = service.processBackupDownload(uploaded.data, shouldDecrypt = false)
        assertTrue(uploaded.data.contentEquals(passthrough))

        val restored = service.processBackupDownload(uploaded.data, shouldDecrypt = true)
        assertTrue(payload.contentEquals(restored))
    }
}
