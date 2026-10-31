package app.logdate.server.identity

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtRestKeyCipherTest {
    private val plaintext = "private key bytes".encodeToByteArray()

    @Test
    fun `decrypts values encrypted with a decrypt-only key`() {
        val previous = AtRestKeyCipher(OLD_KEK).encrypt(plaintext, CONTEXT)

        val cipher = AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK))

        assertContentEquals(plaintext, cipher.decrypt(previous, CONTEXT))
    }

    @Test
    fun `encrypts only with the encrypting key`() {
        val encrypted = AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK)).encrypt(plaintext, CONTEXT)

        assertContentEquals(plaintext, AtRestKeyCipher(NEW_KEK).decrypt(encrypted, CONTEXT))
        assertFailsWith<AEADBadTagException> { AtRestKeyCipher(OLD_KEK).decrypt(encrypted, CONTEXT) }
    }

    @Test
    fun `new values are in the current format and bound to where they are stored`() {
        val cipher = AtRestKeyCipher(NEW_KEK)
        val encrypted = cipher.encrypt(plaintext, CONTEXT)

        assertTrue(encrypted.startsWith("v2:"), encrypted)
        assertFailsWith<AEADBadTagException> { cipher.decrypt(encrypted, "signing_keys/another-row") }
        assertFailsWith<AEADBadTagException> { cipher.decrypt(encrypted, "oauth_signing_keys/key-1") }
        assertFailsWith<AEADBadTagException> { LegacyAtRestFormat.decrypt(NEW_KEK, encrypted.removePrefix("v2:")) }
    }

    @Test
    fun `values stored before the current format are still read`() {
        val legacy = LegacyAtRestFormat.encrypt(OLD_KEK, plaintext)

        assertContentEquals(plaintext, AtRestKeyCipher(OLD_KEK).decrypt(legacy, CONTEXT))
        assertContentEquals(plaintext, AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK)).decrypt(legacy, CONTEXT))
    }

    @Test
    fun `rewrap leaves values already current under the encrypting key`() {
        val cipher = AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK))

        assertNull(cipher.rewrap(cipher.encrypt(plaintext, CONTEXT), CONTEXT))
    }

    @Test
    fun `rewrap re-encrypts values from a decrypt-only key under the encrypting key`() {
        val previous = AtRestKeyCipher(OLD_KEK).encrypt(plaintext, CONTEXT)

        val rewrapped = assertNotNull(AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK)).rewrap(previous, CONTEXT))

        assertContentEquals(plaintext, AtRestKeyCipher(NEW_KEK).decrypt(rewrapped, CONTEXT))
    }

    @Test
    fun `rewrap upgrades values in the earlier format even under the same key`() {
        val legacy = LegacyAtRestFormat.encrypt(NEW_KEK, plaintext)

        val rewrapped = assertNotNull(AtRestKeyCipher(NEW_KEK).rewrap(legacy, CONTEXT))

        assertTrue(rewrapped.startsWith("v2:"))
        assertContentEquals(plaintext, AtRestKeyCipher(NEW_KEK).decrypt(rewrapped, CONTEXT))
        assertFailsWith<AEADBadTagException> { AtRestKeyCipher(NEW_KEK).decrypt(rewrapped, "signing_keys/another-row") }
    }

    @Test
    fun `values no known key encrypted are rejected`() {
        val foreign = AtRestKeyCipher(OTHER_KEK).encrypt(plaintext, CONTEXT)
        val cipher = AtRestKeyCipher(NEW_KEK, decryptOnlySeeds = listOf(OLD_KEK))

        assertFailsWith<AEADBadTagException> { cipher.decrypt(foreign, CONTEXT) }
        assertFailsWith<AEADBadTagException> { cipher.rewrap(foreign, CONTEXT) }
        assertFailsWith<AEADBadTagException> { cipher.decrypt(LegacyAtRestFormat.encrypt(OTHER_KEK, plaintext), CONTEXT) }
    }

    @Test
    fun `the key derivation is HKDF-SHA256`() {
        // RFC 5869, test case 1.
        val okm =
            AtRestKeyCipher.hkdfSha256(
                ikm = ByteArray(22) { 0x0b },
                salt = ByteArray(13) { it.toByte() },
                info = ByteArray(10) { (0xf0 + it).toByte() },
                length = 42,
            )

        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    private companion object {
        const val CONTEXT = "signing_keys/0b9e1c7a-5d1f-4f0e-9a40-6f1d2c3b4a59"
        const val OLD_KEK = "old-key-encryption-key-0123456789abcdef"
        const val NEW_KEK = "new-key-encryption-key-0123456789abcdef"
        const val OTHER_KEK = "unrelated-key-encryption-key-0123456789"
    }
}
