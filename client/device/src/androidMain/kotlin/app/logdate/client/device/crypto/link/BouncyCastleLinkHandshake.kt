package app.logdate.client.device.crypto.link

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.util.Arrays
import java.security.SecureRandom

/** [LinkHandshake] on BouncyCastle, which `AndroidDeviceTransfer` already uses for X25519. */
class BouncyCastleLinkHandshake(
    private val random: SecureRandom = SecureRandom(),
) : LinkHandshake {
    override fun generateKeyPair(): LinkKeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return LinkKeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    override fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    override fun transcriptHash(transcript: LinkTranscript): ByteArray = sha256(transcript.encode())

    override fun deriveKeys(
        ownPrivateKey: ByteArray,
        peerPublicKey: ByteArray,
        transcriptHash: ByteArray,
    ): LinkKeys {
        if (transcriptHash.size != HASH_BYTES) throw LinkHandshakeException("A transcript hash is $HASH_BYTES bytes")
        val shared = x25519(ownPrivateKey, peerPublicKey)
        try {
            return LinkKeys(
                bundleKey = hkdfSha256(shared, transcriptHash, LinkLabels.BUNDLE.encodeToByteArray(), KEY_BYTES),
                confirmKey = hkdfSha256(shared, transcriptHash, LinkLabels.CONFIRM.encodeToByteArray(), KEY_BYTES),
                pairKey = hkdfSha256(shared, transcriptHash, LinkLabels.PAIR.encodeToByteArray(), KEY_BYTES),
            )
        } finally {
            shared.fill(0)
        }
    }

    override fun seal(
        bundleKey: ByteArray,
        plaintext: ByteArray,
        transcriptHash: ByteArray,
    ): SealedBytes {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val ciphertext = runGcm(forEncryption = true, key = bundleKey, iv = iv, associatedData = transcriptHash, input = plaintext)
        return SealedBytes(iv, ciphertext)
    }

    override fun open(
        bundleKey: ByteArray,
        sealed: SealedBytes,
        transcriptHash: ByteArray,
    ): ByteArray =
        try {
            runGcm(forEncryption = false, key = bundleKey, iv = sealed.iv, associatedData = transcriptHash, input = sealed.ciphertext)
        } catch (e: InvalidCipherTextException) {
            throw LinkHandshakeException("The link bundle failed its integrity check", e)
        }

    override fun confirmation(
        confirmKey: ByteArray,
        transcriptHash: ByteArray,
    ): ByteArray = hmacSha256(confirmKey, LinkLabels.CONFIRM.encodeToByteArray() + transcriptHash)

    override fun verifyConfirmation(
        confirmKey: ByteArray,
        transcriptHash: ByteArray,
        mac: ByteArray,
    ): Boolean = Arrays.constantTimeAreEqual(confirmation(confirmKey, transcriptHash), mac)

    /** X25519 as RFC 7748 defines it. Throws [LinkHandshakeException] for a result of all zeros. */
    fun x25519(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        if (privateKey.size != KEY_BYTES || publicKey.size != KEY_BYTES) throw LinkHandshakeException("X25519 keys are $KEY_BYTES bytes")
        val shared = ByteArray(KEY_BYTES)
        try {
            X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(publicKey, 0), shared, 0)
        } catch (e: IllegalStateException) {
            throw LinkHandshakeException("The other device's key gives no shared secret", e)
        }
        return shared
    }

    /** HKDF-SHA256 as RFC 5869 defines it. */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest.newInstance())
        generator.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    private fun runGcm(
        forEncryption: Boolean,
        key: ByteArray,
        iv: ByteArray,
        associatedData: ByteArray,
        input: ByteArray,
    ): ByteArray {
        if (key.size != KEY_BYTES || iv.size != IV_BYTES) throw LinkHandshakeException("AES-256-GCM needs a 32-byte key and a 12-byte IV")
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(forEncryption, AEADParameters(KeyParameter(key), TAG_BITS, iv, associatedData))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val written = cipher.processBytes(input, 0, input.size, output, 0)
        val total = written + cipher.doFinal(output, written)
        return output.copyOf(total)
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = HMac(SHA256Digest.newInstance())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = SHA256Digest.newInstance()
        digest.update(data, 0, data.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val HASH_BYTES = 32
        const val NONCE_BYTES = 16
    }
}
