package app.logdate.server.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Generates and stores encrypted hosted signing keys for AT Protocol identity documents.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class SigningKeyService(
    private val repository: SigningKeyRepository,
    encryptionKeySeed: String,
    decryptOnlyEncryptionKeySeeds: List<String> = emptyList(),
    private val clock: Clock = Clock.System,
) {
    private val secureRandom = SecureRandom()
    private val atRestCipher = AtRestKeyCipher(encryptionKeySeed, decryptOnlyEncryptionKeySeeds, secureRandom)
    private val sealJson = Json { ignoreUnknownKeys = true }

    /**
     * Seals a key from [prepareKey] into an opaque token the client returns to finish a two-step
     * change, so the server stores nothing in between. The token is encrypted with the key-encryption
     * key, names its account, the operation it was prepared on and a digest of the operation it
     * prepared, and expires after [PREPARED_KEY_LIFETIME]. The token is bound to its account, and it
     * keeps the key's ID, which its encrypted private key is bound to.
     */
    fun sealPreparedKey(
        key: StoredSigningKey,
        prevCid: String,
        operationDigest: String,
    ): String =
        atRestCipher.encrypt(
            sealJson
                .encodeToString(
                    SealedPreparedKey.serializer(),
                    SealedPreparedKey(
                        accountId = key.accountId.toString(),
                        keyId = key.id.toString(),
                        algorithm = key.algorithm,
                        publicKeyMultibase = key.publicKeyMultibase,
                        privateKeyEncrypted = key.privateKeyEncrypted,
                        prevCid = prevCid,
                        operationDigest = operationDigest,
                        sealedAtEpochSeconds = clock.now().epochSeconds,
                    ),
                ).encodeToByteArray(),
            AtRestKeyContext.preparedKey(key.accountId),
        )

    /**
     * Opens a token from [sealPreparedKey] for [accountId]. A token that was altered, sealed by
     * another deployment or for another account is refused as invalid; an old one as expired.
     */
    fun openPreparedKey(
        accountId: Uuid,
        token: String,
    ): OpenedPreparedKey {
        val sealed =
            try {
                sealJson.decodeFromString(
                    SealedPreparedKey.serializer(),
                    atRestCipher.decrypt(token, AtRestKeyContext.preparedKey(accountId)).decodeToString(),
                )
            } catch (exception: Exception) {
                throw IdentityLifecycleValidationException("The prepared key is not valid", exception, code = PREPARED_KEY_INVALID)
            }
        if (sealed.accountId != accountId.toString()) {
            throw IdentityLifecycleValidationException("The prepared key belongs to a different account", code = PREPARED_KEY_INVALID)
        }
        val age = clock.now().epochSeconds - sealed.sealedAtEpochSeconds
        if (age > PREPARED_KEY_LIFETIME.inWholeSeconds) {
            throw IdentityLifecycleConflictException("The prepared key has expired; start again", code = PREPARED_KEY_EXPIRED)
        }
        return OpenedPreparedKey(
            key =
                StoredSigningKey(
                    // Tokens sealed before keys were bound to their ID carry none; their keys aren't bound.
                    id = sealed.keyId?.let(Uuid::parse) ?: Uuid.random(),
                    accountId = accountId,
                    algorithm = sealed.algorithm,
                    publicKeyMultibase = sealed.publicKeyMultibase,
                    privateKeyEncrypted = sealed.privateKeyEncrypted,
                    createdAt = clock.now(),
                ),
            prevCid = sealed.prevCid,
            operationDigest = sealed.operationDigest,
        )
    }

    fun generateKeyPair(curve: EcCurve = DEFAULT_SIGNING_CURVE): GeneratedSigningKey {
        val keyPair = EcKeySupport.generateKeyPair(curve = curve, secureRandom = secureRandom)
        return GeneratedSigningKey(
            algorithm = curve.signingKeyAlgorithm,
            publicKeyMultibase = EcKeySupport.encodePublicKeyMultibase(keyPair.public as java.security.interfaces.ECPublicKey, curve),
            privateKeyPkcs8 = keyPair.private.encoded,
        )
    }

    suspend fun ensureActiveKey(accountId: Uuid): StoredSigningKey = findActiveKey(accountId) ?: createKey(accountId)

    /** Returns the account's active key, or null when it has none; unlike [ensureActiveKey] it never creates one. */
    suspend fun findActiveKey(accountId: Uuid): StoredSigningKey? = repository.findActiveByAccountId(accountId)

    suspend fun rotateKey(accountId: Uuid): StoredSigningKey = activatePreparedKey(accountId, prepareKey(accountId))

    suspend fun prepareKey(accountId: Uuid): StoredSigningKey {
        val generated = generateKeyPair()
        return buildStoredKey(
            accountId = accountId,
            algorithm = generated.algorithm,
            publicKeyMultibase = generated.publicKeyMultibase,
            privateKeyPkcs8 = generated.privateKeyPkcs8,
        )
    }

    suspend fun activatePreparedKey(
        accountId: Uuid,
        preparedKey: StoredSigningKey,
    ): StoredSigningKey {
        require(preparedKey.accountId == accountId) { "Prepared signing key belongs to a different account" }
        return repository.replaceActiveKey(preparedKey)
    }

    suspend fun exportActiveKey(
        accountId: Uuid,
        passphrase: String,
    ): ExportedSigningKey {
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }

        val record = ensureActiveKey(accountId)
        val salt = ByteArray(EXPORT_SALT_SIZE).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_SIZE).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, exportAesKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encryptedPrivateKey = cipher.doFinal(decryptPrivateKey(record).encoded)

        return ExportedSigningKey(
            algorithm = record.algorithm,
            publicKeyMultibase = record.publicKeyMultibase,
            publicKeyDidKey = didKeyFor(record.publicKeyMultibase),
            encryptedPrivateKey = Base64.encode(encryptedPrivateKey),
            salt = Base64.encode(salt),
            iv = Base64.encode(iv),
            iterations = EXPORT_KDF_ITERATIONS,
        )
    }

    fun decryptPrivateKey(record: StoredSigningKey): PrivateKey =
        EcKeySupport.decodePrivateKey(atRestCipher.decrypt(record.privateKeyEncrypted, AtRestKeyContext.signingKey(record.id)))

    fun decryptExportedKey(
        exportedKey: ExportedSigningKey,
        passphrase: String,
    ): PrivateKey {
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }
        require(exportedKey.kdf == PBKDF2_ALGORITHM) { "Unsupported export KDF: ${exportedKey.kdf}" }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            exportAesKey(passphrase, Base64.decode(exportedKey.salt), exportedKey.iterations),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(exportedKey.iv)),
        )
        val privateKeyBytes = cipher.doFinal(Base64.decode(exportedKey.encryptedPrivateKey))
        return EcKeySupport.decodePrivateKey(privateKeyBytes)
    }

    suspend fun importActiveKey(
        accountId: Uuid,
        exportedKey: ExportedSigningKey,
        passphrase: String,
    ): StoredSigningKey =
        activatePreparedKey(
            accountId = accountId,
            preparedKey = prepareImportedKey(accountId, exportedKey, passphrase),
        )

    suspend fun prepareImportedKey(
        accountId: Uuid,
        exportedKey: ExportedSigningKey,
        passphrase: String,
    ): StoredSigningKey {
        val privateKey =
            try {
                decryptExportedKey(exportedKey, passphrase)
            } catch (exception: IllegalArgumentException) {
                throw IdentityLifecycleValidationException("Invalid signing key import payload", exception)
            } catch (exception: Exception) {
                throw IdentityLifecycleValidationException("Unable to decrypt signing key import payload", exception)
            }
        require(exportedKey.publicKeyDidKey == didKeyFor(exportedKey.publicKeyMultibase)) {
            "publicKeyDidKey must match publicKeyMultibase"
        }
        require(EcCurve.fromSigningKeyAlgorithm(exportedKey.algorithm) != null) {
            "Unsupported signing key algorithm: ${exportedKey.algorithm}"
        }
        return buildStoredKey(
            accountId = accountId,
            algorithm = exportedKey.algorithm,
            publicKeyMultibase = exportedKey.publicKeyMultibase,
            privateKeyPkcs8 = privateKey.encoded,
        )
    }

    private suspend fun createKey(accountId: Uuid): StoredSigningKey {
        val generated = generateKeyPair()
        return repository.save(
            buildStoredKey(
                accountId = accountId,
                algorithm = generated.algorithm,
                publicKeyMultibase = generated.publicKeyMultibase,
                privateKeyPkcs8 = generated.privateKeyPkcs8,
            ),
        )
    }

    private fun buildStoredKey(
        accountId: Uuid,
        algorithm: String,
        publicKeyMultibase: String,
        privateKeyPkcs8: ByteArray,
    ): StoredSigningKey {
        val id = Uuid.random()
        return StoredSigningKey(
            id = id,
            accountId = accountId,
            algorithm = algorithm,
            publicKeyMultibase = publicKeyMultibase,
            privateKeyEncrypted = atRestCipher.encrypt(privateKeyPkcs8, AtRestKeyContext.signingKey(id)),
            createdAt = Clock.System.now(),
        )
    }

    private fun exportAesKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = EXPORT_KDF_ITERATIONS,
    ): SecretKeySpec {
        val keyFactory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, EXPORT_AES_KEY_BITS)
        return SecretKeySpec(keyFactory.generateSecret(keySpec).encoded, AES_ALGORITHM)
    }

    data class GeneratedSigningKey(
        val algorithm: String,
        val publicKeyMultibase: String,
        val privateKeyPkcs8: ByteArray,
    )

    @Serializable
    data class ExportedSigningKey(
        val algorithm: String,
        val publicKeyMultibase: String,
        val publicKeyDidKey: String,
        val encryptedPrivateKey: String,
        val salt: String,
        val iv: String,
        val kdf: String = PBKDF2_ALGORITHM,
        val iterations: Int = EXPORT_KDF_ITERATIONS,
    )

    /** A key from [sealPreparedKey], ready to activate, the operation it was prepared on and the digest of the one it prepared. */
    data class OpenedPreparedKey(
        val key: StoredSigningKey,
        val prevCid: String,
        val operationDigest: String,
    )

    @Serializable
    private data class SealedPreparedKey(
        val accountId: String,
        val keyId: String? = null,
        val algorithm: String,
        val publicKeyMultibase: String,
        val privateKeyEncrypted: String,
        val prevCid: String,
        val operationDigest: String,
        val sealedAtEpochSeconds: Long,
    )

    companion object {
        /** The prepared key token is altered, unreadable or for another account. */
        const val PREPARED_KEY_INVALID: String = "PREPARED_KEY_INVALID"

        /** The prepared key token is older than [PREPARED_KEY_LIFETIME]. */
        const val PREPARED_KEY_EXPIRED: String = "PREPARED_KEY_EXPIRED"

        /** How long a prepared key token can be completed. */
        val PREPARED_KEY_LIFETIME: Duration = 30.minutes

        private val DEFAULT_SIGNING_CURVE: EcCurve = EcCurve.K256
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val EXPORT_SALT_SIZE = 16
        private const val EXPORT_KDF_ITERATIONS = 120_000
        private const val EXPORT_AES_KEY_BITS = 256
    }
}

internal fun didKeyFor(publicKeyMultibase: String): String = "did:key:$publicKeyMultibase"
