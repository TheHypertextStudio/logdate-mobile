package app.logdate.server.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [PayloadCodec] and the associated binary payload format used for
 * server-side encryption of media and backups.
 *
 * This suite provides deep verification of the server's data-at-rest protection:
 * - Ensures correct application of protocol-specific prefixes (`LDSM1` for media,
 *   `LDBK1` for backups).
 * - Validates the cryptographic round-trip (encryption/decryption) using AES-GCM.
 * - Tests the integrity and versioning of the payload header format, including
 *   key ID and IV management.
 * - Confirms robust error handling for missing keys, tampered data, and
 *   unsupported protocol versions.
 * - Exercises edge cases such as empty payloads and large multi-megabyte data streams.
 */
class PayloadCodecTest {
    private val testKey = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    private val keyring =
        object : EncryptionKeyring {
            private val key = EncryptionKey("test-key-1", testKey)

            override fun getActiveKey() = key

            override fun getKey(keyId: String) = if (keyId == "test-key-1") key else null
        }
    private val codec = PayloadCodec(keyring)

    @Test
    fun `media encryption produces LDSM1 prefix`() {
        val plaintext = "test data".toByteArray()
        val encrypted = codec.encryptMedia(plaintext)

        assertTrue(encrypted.size > plaintext.size)
        val prefix = encrypted.copyOfRange(0, 5)
        assertEquals("LDSM1", String(prefix, Charsets.UTF_8))
    }

    @Test
    fun `media encryption and decryption roundtrip`() {
        val plaintext = "test secret data".toByteArray()
        val encrypted = codec.encryptMedia(plaintext)
        val decrypted = codec.decryptMedia(encrypted)

        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `backup encryption produces LDBK1 prefix`() {
        val plaintext = "backup data".toByteArray()
        val encrypted = codec.encryptBackup(plaintext)

        assertTrue(encrypted.size > plaintext.size)
        val prefix = encrypted.copyOfRange(0, 5)
        assertEquals("LDBK1", String(prefix, Charsets.UTF_8))
    }

    @Test
    fun `backup encryption and decryption roundtrip`() {
        val plaintext = "backup secret data".toByteArray()
        val encrypted = codec.encryptBackup(plaintext)
        val decrypted = codec.decryptBackup(encrypted)

        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `decryption with wrong key fails`() {
        val encrypted = codec.encryptMedia("test".toByteArray())

        val wrongKeyring =
            object : EncryptionKeyring {
                private val wrongKey = EncryptionKey("test-key-1", ByteArray(32) { 0xFF.toByte() })

                override fun getActiveKey() = wrongKey

                override fun getKey(keyId: String) = wrongKey
            }
        val wrongCodec = PayloadCodec(wrongKeyring)

        assertFailsWith<Exception> {
            wrongCodec.decryptMedia(encrypted)
        }
    }

    @Test
    fun `decryption with missing key throws exception`() {
        val encrypted = codec.encryptMedia("test".toByteArray())

        val emptyKeyring =
            object : EncryptionKeyring {
                override fun getActiveKey() = EncryptionKey("other-key", testKey)

                override fun getKey(keyId: String) = null
            }
        val emptyCodec = PayloadCodec(emptyKeyring)

        val exception =
            assertFailsWith<EncryptionException> {
                emptyCodec.decryptMedia(encrypted)
            }
        assertTrue(exception.message?.contains("Key not found") == true)

        val encryptedBackup = codec.encryptBackup("backup".toByteArray())
        val backupException =
            assertFailsWith<EncryptionException> {
                emptyCodec.decryptBackup(encryptedBackup)
            }
        assertTrue(backupException.message?.contains("Key not found") == true)
    }

    @Test
    fun `payload includes version and keyId`() {
        val plaintext = "test".toByteArray()
        val encrypted = codec.encryptMedia(plaintext)

        assertEquals(0x01.toByte(), encrypted[5])

        val keyIdLength = ((encrypted[6].toInt() and 0xFF) shl 8) or (encrypted[7].toInt() and 0xFF)
        assertTrue(keyIdLength > 0)
    }

    @Test
    fun `empty plaintext encrypts successfully`() {
        val plaintext = ByteArray(0)
        val encrypted = codec.encryptMedia(plaintext)
        val decrypted = codec.decryptMedia(encrypted)

        assertEquals(0, decrypted.size)
    }

    @Test
    fun `large payload encrypts successfully`() {
        val plaintext = ByteArray(1024 * 1024) { it.toByte() }
        val encrypted = codec.encryptMedia(plaintext)
        val decrypted = codec.decryptMedia(encrypted)

        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test
    fun `payload header codec validates prefix size version key id and iv`() {
        val prefix = PayloadPrefixes.SERVER_MEDIA
        val keyId = "key-1"
        val iv = ByteArray(AesGcmCipher.IV_SIZE_BYTES) { 1 }
        val ciphertext = ByteArray(AesGcmCipher.GCM_TAG_BYTES) { 2 }
        val encoded = PayloadHeaderCodec.encode(prefix, keyId, iv, ciphertext)

        val header = PayloadHeaderCodec.decode(encoded, prefix)
        assertEquals(keyId, header.keyId)
        assertEquals(AesGcmCipher.IV_SIZE_BYTES, header.iv.size)
        assertTrue(header.ciphertextOffset > prefix.size)

        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.decode(encoded, PayloadPrefixes.SERVER_BACKUP)
        }

        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.decode(ByteArray(prefix.size + 2), prefix)
        }

        val unsupportedVersion = encoded.copyOf().also { it[prefix.size] = 0x02 }
        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.decode(unsupportedVersion, prefix)
        }

        val invalidKeyIdLength =
            encoded.copyOf().also {
                it[prefix.size + 1] = 0x00
                it[prefix.size + 2] = 0x00
            }
        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.decode(invalidKeyIdLength, prefix)
        }

        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.encode(prefix, "", iv, ciphertext)
        }
        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.encode(prefix, "k".repeat(129), iv, ciphertext)
        }
        assertFailsWith<EncryptionException> {
            PayloadHeaderCodec.encode(prefix, keyId, ByteArray(4), ciphertext)
        }
    }
}
