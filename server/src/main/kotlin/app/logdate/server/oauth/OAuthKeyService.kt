package app.logdate.server.oauth

import app.logdate.server.config.AtprotoSigningKeyKek
import app.logdate.server.config.AtprotoSigningKeyKekDecryptOnly
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.AtRestKeyContext
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Holds the ES256 keys that sign OAuth access tokens and are published by the OAuth JWKS endpoint.
 *
 * The keys live in [repository], encrypted under the deployment's key-encryption key, so every
 * server instance and every restart signs with the same key and tokens stay valid across them.
 * The first instance that finds no stored key creates one.
 *
 * Keys rotate without signing anyone out (see [rotateIfDue]). Each instance rereads the stored
 * keys at most every [refreshInterval], so a newly staged key is accepted everywhere before any
 * instance signs with it.
 */
@OptIn(ExperimentalEncodingApi::class)
class OAuthKeyService(
    private val repository: OAuthSigningKeyRepository = InMemoryOAuthSigningKeyRepository(),
    encryptionKeySeed: String = AtprotoSigningKeyKek.developmentValue,
    decryptOnlyEncryptionKeySeeds: List<String> = emptyList(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clock: Clock = Clock.System,
    private val rotationPolicy: OAuthKeyRotationPolicy = OAuthKeyRotationPolicy(),
    private val refreshInterval: Duration = DEFAULT_REFRESH_INTERVAL,
) {
    private val cipher = AtRestKeyCipher(encryptionKeySeed, decryptOnlyEncryptionKeySeeds, secureRandom)

    @Volatile
    private var snapshot: KeySnapshot? = null

    /**
     * Returns the JSON Web Key Set for OAuth discovery: the active key, plus any key that is
     * about to become active or still verifies tokens it signed.
     */
    fun jwks(): JsonWebKeySet = JsonWebKeySet(keys = current().publishedKeys)

    /**
     * Returns the active ES256 public key as a JWK.
     */
    fun currentJwk(): JsonWebKey = current().active.jwk

    /**
     * Signs [payload] with the active private key.
     */
    fun sign(payload: ByteArray): ByteArray = sign(current().active, payload)

    /**
     * Returns a compact JWS: [signingInput] builds the encoded header and payload for the active
     * key's id, and the result is signed with that same key. Taking the id and the key from one
     * read means a rotation in between can't pair a signature with the wrong key id.
     */
    fun signJwt(signingInput: (keyId: String) -> String): String {
        val active = current().active
        val input = signingInput(active.jwk.kid)
        val signature = sign(active, input.toByteArray(Charsets.UTF_8))
        return "$input.${base64Url(signature)}"
    }

    /**
     * Returns the active public key.
     */
    fun publicKey(): PublicKey = current().active.publicKey

    /**
     * Returns the public key that verifies tokens carrying [keyId], or null when no current key
     * has that id.
     */
    fun verificationKey(keyId: String): PublicKey? = current().verificationKeys[keyId]

    /**
     * Moves key rotation forward one step when it's due: deletes a retiring key whose tokens have
     * all expired, promotes a staged key once every instance has had time to accept it, and stages
     * a new key once the active one reaches its maximum age. Safe to call from every instance.
     */
    fun rotateIfDue(): OAuthKeyRotationStep {
        val now = clock.now()
        repository.getOrCreate(::newStoredKey)
        val retiredDeleted = repository.deleteRetired(now)
        val keys = repository.keySet()
        val active = checkNotNull(keys.active) { "OAuth signing key was not stored" }
        val next = keys.next
        val step =
            when {
                next != null && now - next.createdAt >= rotationPolicy.stagePeriod -> {
                    val retireAfter = now + rotationPolicy.retireAfterPromotion
                    if (repository.promoteNext(active.keyId, next.keyId, retireAfter)) {
                        OAuthKeyRotationStep.PROMOTED
                    } else {
                        OAuthKeyRotationStep.NONE
                    }
                }
                next == null && now - active.createdAt >= rotationPolicy.maxKeyAge -> {
                    repository.stageNext(::newStoredKey)
                    OAuthKeyRotationStep.STAGED
                }
                retiredDeleted -> OAuthKeyRotationStep.RETIRED
                else -> OAuthKeyRotationStep.NONE
            }
        if (step != OAuthKeyRotationStep.NONE) snapshot = null
        return step
    }

    private fun sign(
        key: ActiveKey,
        payload: ByteArray,
    ): ByteArray =
        Signature.getInstance(JWS_SIGNATURE_ALGORITHM).run {
            initSign(key.privateKey, secureRandom)
            update(payload)
            sign()
        }

    private fun current(): KeySnapshot {
        val now = clock.now()
        snapshot?.takeIf { now - it.loadedAt < refreshInterval }?.let { return it }
        return synchronized(this) {
            snapshot?.takeIf { now - it.loadedAt < refreshInterval } ?: load(now).also { snapshot = it }
        }
    }

    private fun load(now: Instant): KeySnapshot {
        val active = repository.getOrCreate(::newStoredKey)
        val keys = repository.keySet()
        val activeKey = loadActiveKey(keys.active ?: active)
        val accepted =
            listOfNotNull(
                keys.next,
                keys.retiring?.takeIf { retiring -> retiring.retireAfter?.let { it > now } ?: false },
            ).map { publicJwkAndKey(it.publicKeySpki) }
        return KeySnapshot(
            active = activeKey,
            publishedKeys = listOf(activeKey.jwk) + accepted.map { it.first },
            verificationKeys = (listOf(activeKey.jwk to activeKey.publicKey) + accepted).associate { (jwk, key) -> jwk.kid to key },
            loadedAt = now,
        )
    }

    private fun loadActiveKey(stored: StoredOAuthSigningKey): ActiveKey {
        val privateKeyBytes =
            try {
                cipher.decrypt(stored.privateKeyEncrypted, AtRestKeyContext.oauthSigningKey(stored.keyId))
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException(
                    "The stored OAuth signing key can't be decrypted. ${AtprotoSigningKeyKek.envVar} or " +
                        "${AtprotoSigningKeyKekDecryptOnly.ENV_VAR} must hold the value that encrypted it.",
                    e,
                )
            }
        val (jwk, publicKey) = publicJwkAndKey(stored.publicKeySpki)
        val privateKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        return ActiveKey(jwk = jwk, publicKey = publicKey, privateKey = privateKey)
    }

    private fun publicJwkAndKey(publicKeySpki: String): Pair<JsonWebKey, PublicKey> {
        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.decode(publicKeySpki)))
        return buildJwk(publicKey as ECPublicKey) to publicKey
    }

    private fun newStoredKey(): StoredOAuthSigningKey {
        val generated =
            KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
                initialize(ECGenParameterSpec(P256_CURVE_NAME), secureRandom)
                generateKeyPair()
            }
        val publicKey = generated.public as ECPublicKey
        val keyId = buildJwk(publicKey).kid
        return StoredOAuthSigningKey(
            keyId = keyId,
            privateKeyEncrypted = cipher.encrypt(generated.private.encoded, AtRestKeyContext.oauthSigningKey(keyId)),
            publicKeySpki = Base64.encode(publicKey.encoded),
            createdAt = clock.now(),
        )
    }

    private fun coordinate(value: BigInteger): String = base64Url(value.toFixedWidth(P256_COORDINATE_BYTES))

    private fun buildJwk(publicKey: ECPublicKey): JsonWebKey {
        val x = coordinate(publicKey.w.affineX)
        val y = coordinate(publicKey.w.affineY)
        return JsonWebKey(
            kty = KEY_ALGORITHM,
            use = KEY_USE,
            key_ops = KEY_OPERATIONS,
            alg = JWS_ALGORITHM,
            kid = jwkThumbprint(x = x, y = y),
            crv = JWK_CURVE_NAME,
            x = x,
            y = y,
        )
    }

    private fun jwkThumbprint(
        x: String,
        y: String,
    ): String {
        val canonicalJwk = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(canonicalJwk.toByteArray())
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private class ActiveKey(
        val jwk: JsonWebKey,
        val publicKey: PublicKey,
        val privateKey: PrivateKey,
    )

    private class KeySnapshot(
        val active: ActiveKey,
        val publishedKeys: List<JsonWebKey>,
        val verificationKeys: Map<String, PublicKey>,
        val loadedAt: Instant,
    )

    private companion object {
        private const val KEY_ALGORITHM = "EC"
        private const val KEY_USE = "sig"
        private val KEY_OPERATIONS = listOf("verify")
        private const val JWS_ALGORITHM = "ES256"
        private const val JWS_SIGNATURE_ALGORITHM = "SHA256withECDSAinP1363Format"
        private const val JWK_CURVE_NAME = "P-256"
        private const val P256_CURVE_NAME = "secp256r1"
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val P256_COORDINATE_BYTES = 32
        private val DEFAULT_REFRESH_INTERVAL = 1.minutes
    }
}

/** What one call to [OAuthKeyService.rotateIfDue] did. */
enum class OAuthKeyRotationStep {
    NONE,
    STAGED,
    PROMOTED,
    RETIRED,
}

/**
 * How OAuth signing keys rotate.
 *
 * - [maxKeyAge]: how long a key signs before a replacement is staged.
 * - [stagePeriod]: how long a staged key is accepted and published before it starts signing. It
 *   must be longer than an instance's key refresh interval, so every instance accepts the new key
 *   before any instance signs with it.
 * - [retireAfterPromotion]: how long the previous key keeps verifying tokens after it stops
 *   signing. It must cover the access token lifetime plus clock skew.
 */
data class OAuthKeyRotationPolicy(
    val maxKeyAge: Duration = 90.days,
    val stagePeriod: Duration = 1.hours,
    val retireAfterPromotion: Duration = 1.hours + 5.minutes,
) {
    companion object {
        const val MAX_AGE_DAYS_ENV = "OAUTH_SIGNING_KEY_MAX_AGE_DAYS"

        /** Reads [maxKeyAge] from [MAX_AGE_DAYS_ENV], at least one day. */
        fun fromEnvironment(readEnv: (String) -> String? = System::getenv): OAuthKeyRotationPolicy {
            val days = readEnv(MAX_AGE_DAYS_ENV)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: return OAuthKeyRotationPolicy()
            return OAuthKeyRotationPolicy(maxKeyAge = days.days)
        }
    }
}

/**
 * OAuth JWKS response payload.
 */
@Serializable
data class JsonWebKeySet(
    val keys: List<JsonWebKey>,
)

/**
 * Public JWK for the server's ES256 OAuth signing key.
 */
@Serializable
data class JsonWebKey(
    val kty: String,
    val use: String,
    val key_ops: List<String>,
    val alg: String,
    val kid: String,
    val crv: String,
    val x: String,
    val y: String,
)

private fun BigInteger.toFixedWidth(width: Int): ByteArray {
    val encoded = toByteArray()
    return when {
        encoded.size == width -> encoded
        encoded.size < width -> ByteArray(width - encoded.size) + encoded
        else -> encoded.copyOfRange(encoded.size - width, encoded.size)
    }
}
