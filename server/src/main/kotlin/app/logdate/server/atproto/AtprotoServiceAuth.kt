package app.logdate.server.atproto

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.SigningKeyService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Nsid
import java.net.URI
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Issues short-lived credentials using the public identity's existing repository key. */
@OptIn(ExperimentalUuidApi::class)
class AtprotoServiceAuth(
    private val accounts: AccountRepository,
    private val keys: SigningKeyService,
    private val clock: Clock = Clock.System,
) {
    suspend fun issue(
        authenticatedAccount: Account,
        audience: String,
        method: String,
        expiresAt: Long? = null,
    ): String {
        if (!validAudience(audience) || Nsid.parse(method).isFailure) {
            throw ServiceAuthException("InvalidRequest", "Provide a service DID and a valid XRPC method.")
        }
        val key = keys.findActiveKey(authenticatedAccount.id)
        val account = accounts.findById(authenticatedAccount.id)
        val did = account?.did
        if (account == null || did == null || did != authenticatedAccount.did ||
            !account.isActive || account.moderation != null || account.publicIdentityDid != did
        ) {
            throw ServiceAuthException("Forbidden", "Service authentication requires an active public identity.")
        }
        val curve = key?.let { EcCurve.fromSigningKeyAlgorithm(it.algorithm) }
        if (key == null || curve == null || key.publicKeyMultibase != account.signingKeyPublic) {
            throw ServiceAuthException("TemporarilyUnavailable", "The service credential could not be issued.")
        }

        val now = clock.now().epochSeconds
        val deadline = now + MAX_LIFETIME_SECONDS
        val expiration = expiresAt ?: deadline
        if (expiration <= now || expiration > deadline) {
            throw ServiceAuthException("BadExpiration", "The service credential must expire within 60 seconds.")
        }

        val header = encode(Json.encodeToString(ServiceJwtHeader(alg = curve.jwsAlgorithm, typ = "JWT")).encodeToByteArray())
        val claims = ServiceJwtClaims(iss = did, aud = audience, lxm = method, iat = now, exp = expiration, jti = Uuid.random().toString())
        val payload = encode(Json.encodeToString(claims).encodeToByteArray())
        val input = "$header.$payload"
        val signature = EcKeySupport.signSha256(keys.decryptPrivateKey(key), curve, input.encodeToByteArray())
        return "$input.${encode(signature)}"
    }

    private fun validAudience(audience: String): Boolean {
        if (audience.length > 2048 || AtprotoDid.parse(audience.substringBefore('#')).isFailure) return false
        val uri = runCatching { URI(audience) }.getOrNull() ?: return false
        return uri.rawFragment?.isNotEmpty() ?: true
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        private const val MAX_LIFETIME_SECONDS = 60L
    }
}

internal class ServiceAuthException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

@Serializable
internal data class ServiceAuthResponse(
    val token: String,
)

@Serializable
private data class ServiceJwtHeader(
    val alg: String,
    val typ: String,
)

@Serializable
private data class ServiceJwtClaims(
    val iss: String,
    val aud: String,
    val lxm: String,
    val iat: Long,
    val exp: Long,
    val jti: String,
)
