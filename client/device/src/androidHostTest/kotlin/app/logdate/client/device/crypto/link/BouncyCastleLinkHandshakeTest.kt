package app.logdate.client.device.crypto.link

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [BouncyCastleLinkHandshake] against the RFC 7748 and RFC 5869 vectors, pins the keys and
 * confirmation derived from them, and checks that any change to the ciphertext, IV or transcript fails.
 */
class BouncyCastleLinkHandshakeTest {
    private val handshake = BouncyCastleLinkHandshake()

    @Test
    fun `x25519 matches RFC 7748 section 6 point 1`() {
        assertEquals(SHARED_SECRET, handshake.x25519(ALICE_PRIVATE.hex(), BOB_PUBLIC.hex()).toHex())
        assertEquals(SHARED_SECRET, handshake.x25519(BOB_PRIVATE.hex(), ALICE_PUBLIC.hex()).toHex())
    }

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm =
            handshake.hkdfSha256(
                ikm = ByteArray(22) { 0x0b },
                salt = "000102030405060708090a0b0c".hex(),
                info = "f0f1f2f3f4f5f6f7f8f9".hex(),
                length = 42,
            )

        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun `the transcript hash is pinned`() {
        assertEquals(TRANSCRIPT_HASH, handshake.transcriptHash(transcript()).toHex())
    }

    @Test
    fun `both devices derive the same pinned keys`() {
        val phone = handshake.deriveKeys(ALICE_PRIVATE.hex(), BOB_PUBLIC.hex(), TRANSCRIPT_HASH.hex())
        val watch = handshake.deriveKeys(BOB_PRIVATE.hex(), ALICE_PUBLIC.hex(), TRANSCRIPT_HASH.hex())

        assertEquals(BUNDLE_KEY, phone.bundleKey.toHex())
        assertEquals(CONFIRM_KEY, phone.confirmKey.toHex())
        assertEquals(PAIR_KEY, phone.pairKey.toHex())
        assertContentEquals(phone.bundleKey, watch.bundleKey)
        assertContentEquals(phone.confirmKey, watch.confirmKey)
        assertContentEquals(phone.pairKey, watch.pairKey)
    }

    @Test
    fun `fresh key pairs agree`() {
        val phone = handshake.generateKeyPair()
        val watch = handshake.generateKeyPair()

        val phoneKeys = handshake.deriveKeys(phone.privateKey, watch.publicKey, TRANSCRIPT_HASH.hex())
        val watchKeys = handshake.deriveKeys(watch.privateKey, phone.publicKey, TRANSCRIPT_HASH.hex())

        assertEquals(32, phone.privateKey.size)
        assertEquals(32, phone.publicKey.size)
        assertFalse(phone.publicKey.contentEquals(watch.publicKey))
        assertEquals(32, phoneKeys.bundleKey.size)
        assertContentEquals(phoneKeys.bundleKey, watchKeys.bundleKey)
    }

    @Test
    fun `a sealed bundle opens with the same key and transcript`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())

        assertEquals(12, sealed.iv.size)
        assertContentEquals(PLAINTEXT, handshake.open(BUNDLE_KEY.hex(), sealed, TRANSCRIPT_HASH.hex()))
    }

    @Test
    fun `a changed ciphertext fails`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertFailsWith<LinkHandshakeException> {
            handshake.open(
                BUNDLE_KEY.hex(),
                SealedBytes(sealed.iv, tampered),
                TRANSCRIPT_HASH.hex(),
            )
        }
    }

    @Test
    fun `a changed iv fails`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())
        val tampered = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertFailsWith<LinkHandshakeException> {
            handshake.open(BUNDLE_KEY.hex(), SealedBytes(tampered, sealed.ciphertext), TRANSCRIPT_HASH.hex())
        }
    }

    @Test
    fun `a different transcript fails as associated data`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())

        assertFailsWith<LinkHandshakeException> { handshake.open(BUNDLE_KEY.hex(), sealed, ByteArray(32)) }
    }

    @Test
    fun `keys from a different transcript cannot open the bundle`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())
        val otherKeys = handshake.deriveKeys(ALICE_PRIVATE.hex(), BOB_PUBLIC.hex(), ByteArray(32) { 7 })

        assertFailsWith<LinkHandshakeException> { handshake.open(otherKeys.bundleKey, sealed, TRANSCRIPT_HASH.hex()) }
    }

    @Test
    fun `an all zero peer key is rejected`() {
        assertFailsWith<LinkHandshakeException> { handshake.deriveKeys(ALICE_PRIVATE.hex(), ByteArray(32), TRANSCRIPT_HASH.hex()) }
    }

    @Test
    fun `keys of the wrong length are rejected`() {
        assertFailsWith<LinkHandshakeException> { handshake.x25519(ByteArray(31), BOB_PUBLIC.hex()) }
        assertFailsWith<LinkHandshakeException> { handshake.deriveKeys(ALICE_PRIVATE.hex(), BOB_PUBLIC.hex(), ByteArray(31)) }
        assertFailsWith<LinkHandshakeException> { handshake.seal(ByteArray(16), PLAINTEXT, TRANSCRIPT_HASH.hex()) }
    }

    @Test
    fun `the confirmation is pinned and verifies only for its transcript`() {
        val mac = handshake.confirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex())

        assertEquals(CONFIRMATION, mac.toHex())
        assertTrue(handshake.verifyConfirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex(), mac))
        assertFalse(handshake.verifyConfirmation(CONFIRM_KEY.hex(), ByteArray(32), mac))
    }

    @Test
    fun `nonces are 16 fresh bytes`() {
        val first = handshake.newNonce()

        assertEquals(16, first.size)
        assertFalse(first.contentEquals(handshake.newNonce()))
    }

    @Test
    fun `a ciphertext shorter than the tag fails`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())

        assertFailsWith<LinkHandshakeException> {
            handshake.open(BUNDLE_KEY.hex(), SealedBytes(sealed.iv, ByteArray(0)), TRANSCRIPT_HASH.hex())
        }
        assertFailsWith<LinkHandshakeException> {
            handshake.open(BUNDLE_KEY.hex(), SealedBytes(sealed.iv, sealed.ciphertext.copyOf(15)), TRANSCRIPT_HASH.hex())
        }
    }

    @Test
    fun `an iv of the wrong length fails`() {
        val sealed = handshake.seal(BUNDLE_KEY.hex(), PLAINTEXT, TRANSCRIPT_HASH.hex())

        assertFailsWith<LinkHandshakeException> {
            handshake.open(BUNDLE_KEY.hex(), SealedBytes(sealed.iv.copyOf(11), sealed.ciphertext), TRANSCRIPT_HASH.hex())
        }
    }

    @Test
    fun `a confirmation of the wrong length does not verify`() {
        val mac = handshake.confirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex())

        assertTrue(handshake.verifyConfirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex(), mac))
        assertFalse(handshake.verifyConfirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex(), mac.copyOf(16)))
        assertFalse(handshake.verifyConfirmation(CONFIRM_KEY.hex(), TRANSCRIPT_HASH.hex(), ByteArray(0)))
    }

    private fun transcript() =
        LinkTranscript(
            phoneNode = "phone-node",
            watchNode = "watch-node",
            phonePublicKey = ALICE_PUBLIC.hex(),
            watchPublicKey = BOB_PUBLIC.hex(),
            phoneNonce = ByteArray(16) { 1 },
            watchNonce = ByteArray(16) { 2 },
            schema = 1,
        )

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        const val ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        const val BOB_PRIVATE = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        const val BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        const val SHARED_SECRET = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        // SHA-256 of the transcript above, computed independently with Python's hashlib.
        const val TRANSCRIPT_HASH = "0fefc1ebb2c354d393e73cabb5100755611c933cc869adb5665acfb62ff8e25c"

        // HKDF-SHA256(SHARED_SECRET, salt = TRANSCRIPT_HASH, info = label, L = 32), computed with Python's hmac.
        const val BUNDLE_KEY = "00d7bdd46947deaaa84f43334524bc0a26a66b6e1dc505bc7df316acf17a03eb"
        const val CONFIRM_KEY = "02e97ac2dbe0e691c065fca1b0f997a93a404b6cd55e5ca69401cc73addea0b4"
        const val PAIR_KEY = "34be0cd20b076e34efe276e9ac6df863783bdafd5f67dfb733c85131d80ffff7"

        // HMAC-SHA256(CONFIRM_KEY, "LDLINK1 confirm" + TRANSCRIPT_HASH).
        const val CONFIRMATION = "e95ffb79c8e0284751de230426caba0a5bc352bbbf7871a0a1e150f4b803a898"

        val PLAINTEXT = """{"mode":"CLOUD"}""".encodeToByteArray()
    }
}
