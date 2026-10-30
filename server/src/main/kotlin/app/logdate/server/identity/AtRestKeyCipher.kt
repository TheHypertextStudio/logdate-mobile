package app.logdate.server.identity

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Encrypts private keys the server stores, using AES-256-GCM under a key derived from a deployment
 * secret (the key-encryption key).
 *
 * New values are `v2:` followed by Base64 of the 12-byte IV and the ciphertext with its
 * authentication tag. The AES key comes from the secret through HKDF-SHA256, and each value is bound
 * to a context naming where it is stored, such as `signing_keys/<id>` (see [AtRestKeyContext]), as
 * GCM associated data: a value copied to another row, table or purpose fails to decrypt.
 *
 * Values written before this format have no prefix, use SHA-256 of the secret as the key and no
 * associated data. They are still read, and [rewrap] replaces them with the current format.
 *
 * New values are always encrypted with [encryptionKeySeed]. Values can also be decrypted with any of
 * [decryptOnlySeeds], which lets a deployment move to a new key-encryption key: first every
 * instance learns to read the new key, then the new key becomes the encrypting one and [rewrap]
 * re-encrypts the stored values.
 */
@OptIn(ExperimentalEncodingApi::class)
class AtRestKeyCipher(
    encryptionKeySeed: String,
    decryptOnlySeeds: List<String> = emptyList(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val seeds = listOf(encryptionKeySeed) + decryptOnlySeeds.filter { it != encryptionKeySeed }
    private val currentKeys = seeds.map(::derivedAesKey)
    private val legacyKeys = seeds.map(::legacyAesKey)

    /** Encrypts [plaintext] for storage under [context], in the current format. */
    fun encrypt(
        plaintext: ByteArray,
        context: String,
    ): String {
        val iv = ByteArray(GCM_IV_SIZE).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, currentKeys.first(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData(context))
        return CURRENT_PREFIX + Base64.encode(iv + cipher.doFinal(plaintext))
    }

    /**
     * Decrypts [encoded], stored under [context], with the encrypting key or any decrypt-only key.
     * Throws [javax.crypto.AEADBadTagException] when none of them encrypted it, it was stored under
     * another context, or it has been altered.
     */
    fun decrypt(
        encoded: String,
        context: String,
    ): ByteArray = decryptWithAnyKey(encoded, context).plaintext

    /**
     * Returns [encoded] re-encrypted with the encrypting key in the current format, or null when it
     * is already both. Throws [javax.crypto.AEADBadTagException] when no known key can read it.
     */
    fun rewrap(
        encoded: String,
        context: String,
    ): String? {
        val decrypted = decryptWithAnyKey(encoded, context)
        return if (decrypted.isCurrent) null else encrypt(decrypted.plaintext, context)
    }

    private fun decryptWithAnyKey(
        encoded: String,
        context: String,
    ): Decrypted {
        val current = encoded.startsWith(CURRENT_PREFIX)
        val bytes = Base64.decode(if (current) encoded.removePrefix(CURRENT_PREFIX) else encoded)
        val keys = if (current) currentKeys else legacyKeys
        val aad = if (current) associatedData(context) else null
        var failure: GeneralSecurityException? = null
        keys.forEachIndexed { index, key ->
            try {
                return Decrypted(plaintext = decryptWith(key, bytes, aad), isCurrent = current && index == 0)
            } catch (exception: GeneralSecurityException) {
                failure = exception
            }
        }
        throw checkNotNull(failure)
    }

    private fun decryptWith(
        key: SecretKeySpec,
        bytes: ByteArray,
        aad: ByteArray?,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_SIZE))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(bytes, GCM_IV_SIZE, bytes.size - GCM_IV_SIZE)
    }

    private class Decrypted(
        val plaintext: ByteArray,
        val isCurrent: Boolean,
    )

    internal companion object {
        const val CURRENT_PREFIX = "v2:"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val HMAC_SHA_256_ALGORITHM = "HmacSHA256"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_KEY_BYTES = 32
        private val HKDF_SALT = "LogDate at-rest key encryption".encodeToByteArray()
        private val HKDF_INFO = "AES-256-GCM key, format 2".encodeToByteArray()
        private val AAD_PREFIX = "LogDate at-rest key, format 2, stored at ".encodeToByteArray()

        private fun associatedData(context: String): ByteArray {
            require(context.isNotBlank()) { "An at-rest key needs the context it is stored under" }
            return AAD_PREFIX + context.encodeToByteArray()
        }

        private fun derivedAesKey(seed: String): SecretKeySpec =
            SecretKeySpec(hkdfSha256(seed.encodeToByteArray(), HKDF_SALT, HKDF_INFO, AES_KEY_BYTES), AES_ALGORITHM)

        private fun legacyAesKey(seed: String): SecretKeySpec =
            SecretKeySpec(MessageDigest.getInstance(SHA_256_ALGORITHM).digest(seed.toByteArray()), AES_ALGORITHM)

        /** HKDF with SHA-256 (RFC 5869): extract a pseudorandom key from [ikm], then expand it to [length] bytes. */
        fun hkdfSha256(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val prk = hmacSha256(salt).doFinal(ikm)
            val expand = hmacSha256(prk)
            val output = ByteArray(length)
            var previous = ByteArray(0)
            var written = 0
            var counter = 1
            while (written < length) {
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                previous = expand.doFinal()
                val take = minOf(previous.size, length - written)
                previous.copyInto(output, destinationOffset = written, endIndex = take)
                written += take
                counter++
            }
            return output
        }

        // RFC 5869 treats an empty salt as a hash-length block of zeros; the JCA refuses an empty key.
        private fun hmacSha256(key: ByteArray): Mac =
            Mac.getInstance(HMAC_SHA_256_ALGORITHM).apply {
                init(SecretKeySpec(key.takeIf(ByteArray::isNotEmpty) ?: ByteArray(AES_KEY_BYTES), HMAC_SHA_256_ALGORITHM))
            }
    }
}

/**
 * The contexts private keys are stored under, bound to each value by [AtRestKeyCipher]. Each names
 * the table and the row, so [StoredKeyRewrap] can rebuild it from a store's name and a key's ID.
 */
@OptIn(ExperimentalUuidApi::class)
object AtRestKeyContext {
    const val SIGNING_KEYS = "signing_keys"
    const val OAUTH_SIGNING_KEYS = "oauth_signing_keys"
    private const val PREPARED_KEYS = "prepared_keys"

    fun signingKey(id: Uuid): String = of(SIGNING_KEYS, id.toString())

    fun oauthSigningKey(keyId: String): String = of(OAUTH_SIGNING_KEYS, keyId)

    fun preparedKey(accountId: Uuid): String = of(PREPARED_KEYS, accountId.toString())

    fun of(
        store: String,
        id: String,
    ): String = "$store/$id"
}
