package app.logdate.server.identity

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The at-rest format stored keys had before they were bound to where they are stored: Base64 of a
 * 12-byte IV and AES-GCM ciphertext under SHA-256 of the secret, with no associated data. Written
 * here independently so tests pin that databases holding it stay readable.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object LegacyAtRestFormat {
    fun encrypt(
        seed: String,
        plaintext: ByteArray,
    ): String {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(seed), GCMParameterSpec(TAG_BITS, iv))
        return Base64.encode(iv + cipher.doFinal(plaintext))
    }

    fun decrypt(
        seed: String,
        encoded: String,
    ): ByteArray {
        val bytes = Base64.decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(seed), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    }

    private fun key(seed: String) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()), "AES")

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
}
