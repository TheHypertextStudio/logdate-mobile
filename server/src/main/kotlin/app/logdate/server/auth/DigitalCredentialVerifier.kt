package app.logdate.server.auth

import app.logdate.server.crypto.sha256
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Verifies an Android Digital Credentials presentation produced by the Credential
 * Manager + Google's UserInfoCredential issuer for the email-verification flow.
 *
 * Wire shape (what the wallet returns inside `DigitalCredential.credentialJson`):
 * ```
 * { "vp_token": { "<query_id>": [ "<SD-JWT VC>" ] } }
 * ```
 * Where the SD-JWT VC is `IssuerJWT~Disclosure1~...~DisclosureN~KeyBindingJWT`.
 *
 * Verification steps:
 *  1. Extract the SD-JWT VC from `vp_token`.
 *  2. Parse the outer JWS (issuer JWT). Look up the signing key by `kid` from
 *     [GoogleVcJwksCache] and verify the signature with Nimbus.
 *  3. Enforce issuer (`iss`), credential type (`vct`), `exp`, `iat` with clock skew.
 *  4. For each `~`-separated disclosure, base64url-decode and SHA-256 hash the
 *     raw segment, confirm the hash is in the issuer JWT's `_sd` array, then
 *     unpack the `[salt, name, value]` triplet into a claims map.
 *  5. Verify the trailing Key Binding JWT against the holder JWK in the issuer
 *     JWT's `cnf.jwk`, confirming `nonce` matches the server-issued challenge and
 *     `aud` matches our expected audience.
 *  6. Require `email_verified == true` and an `email` claim.
 *
 * Anything else throws [VerificationException] with a short reason code, which is
 * the only thing surfaced to clients (no Napier stack traces leak server crypto state).
 *
 * NOTE: This implementation is scoped to Google's UserInfoCredential. Adding
 * other issuers means broadening the trust anchor + tightening the issuer allow-list.
 */
open class DigitalCredentialVerifier(
    private val jwksCache: GoogleVcJwksCache,
    private val expectedAudience: String,
    private val allowedClockSkew: Duration = 5.minutes,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    open suspend fun verify(
        credentialJson: String,
        expectedNonce: String,
    ): Result<VerifiedEmailClaims> =
        runCatching {
            val sdJwt = extractSdJwt(credentialJson)
            val parts = splitSdJwt(sdJwt)

            val issuerJws = JWSObject.parse(parts.issuerJwt)
            val issuerClaims = parseIssuerClaims(issuerJws)

            requireString(issuerClaims, "iss") { it == EXPECTED_ISSUER }
                ?: throw VerificationException("untrusted_issuer")
            requireString(issuerClaims, "vct") { it == EXPECTED_VCT }
                ?: throw VerificationException("unexpected_vct")

            val kid = issuerJws.header.keyID ?: throw VerificationException("missing_kid")
            val jwk = jwksCache.keyForKid(kid) ?: throw VerificationException("unknown_kid")
            if (!verifyJws(issuerJws, jwk)) throw VerificationException("issuer_signature_invalid")

            val nowEpoch = clock().epochSeconds
            val skew = allowedClockSkew.inWholeSeconds
            val exp =
                issuerClaims["exp"]?.jsonPrimitive?.longOrNull
                    ?: throw VerificationException("missing_exp")
            if (nowEpoch - skew > exp) throw VerificationException("expired")
            val iat =
                issuerClaims["iat"]?.jsonPrimitive?.longOrNull
                    ?: throw VerificationException("missing_iat")
            if (iat - skew > nowEpoch) throw VerificationException("issued_in_future")

            // SD-JWT hash algorithm. The spec allows others; Google uses sha-256.
            val sdAlg = issuerClaims["_sd_alg"]?.jsonPrimitive?.contentOrNull ?: "sha-256"
            if (sdAlg != "sha-256") throw VerificationException("unsupported_sd_alg")

            val sdHashes =
                (
                    issuerClaims["_sd"]?.jsonArray
                        ?: throw VerificationException("missing_sd_array")
                ).map { it.jsonPrimitive.content }
                    .toSet()
            val disclosed = decodeDisclosures(parts.disclosures, sdHashes)

            val email =
                disclosed.string("email")
                    ?: issuerClaims["email"]?.jsonPrimitive?.contentOrNull
                    ?: throw VerificationException("missing_email")
            val emailVerified =
                disclosed.boolean("email_verified")
                    ?: issuerClaims["email_verified"]?.jsonPrimitive?.booleanOrNull
                    ?: throw VerificationException("missing_email_verified")
            if (!emailVerified) throw VerificationException("email_not_verified")

            verifyKeyBinding(
                keyBindingJwt = parts.keyBindingJwt ?: throw VerificationException("missing_kb_jwt"),
                cnfJwk =
                    issuerClaims["cnf"]?.jsonObject?.get("jwk")?.jsonObject
                        ?: throw VerificationException("missing_cnf_jwk"),
                expectedNonce = expectedNonce,
                nowEpoch = nowEpoch,
            )

            VerifiedEmailClaims(
                email = email,
                name = disclosed.string("name"),
                givenName = disclosed.string("given_name"),
                familyName = disclosed.string("family_name"),
                picture = disclosed.string("picture"),
                hostedDomain = disclosed.string("hd").orEmpty(),
                verifiedAt = clock(),
            )
        }.onFailure { e ->
            if (e is VerificationException) {
                Napier.w("Digital credential verification rejected: ${e.message}")
            } else {
                Napier.e("Digital credential verification crashed", e)
            }
        }

    // --- helpers -----------------------------------------------------------

    private fun extractSdJwt(credentialJson: String): String {
        val root =
            runCatching { json.parseToJsonElement(credentialJson).jsonObject }
                .getOrElse { throw VerificationException("malformed_credential_json") }
        val vpToken = root["vp_token"]?.jsonObject ?: throw VerificationException("missing_vp_token")
        val firstQuery =
            vpToken.entries
                .firstOrNull()
                ?.value
                ?.jsonArray
                ?: throw VerificationException("missing_query_response")
        return firstQuery.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw VerificationException("missing_sd_jwt_entry")
    }

    private data class SdJwtParts(
        val issuerJwt: String,
        val disclosures: List<String>,
        val keyBindingJwt: String?,
    )

    private fun splitSdJwt(sdJwt: String): SdJwtParts {
        val segments = sdJwt.split("~")
        if (segments.size < 2) throw VerificationException("malformed_sd_jwt")
        val issuerJwt = segments.first()
        val tail = segments.last()
        // The Key Binding JWT, when present, sits in the last `~`-segment and
        // looks like a JWS (three base64url-segments separated by `.`). When the
        // SD-JWT is presented without holder binding, the tail is an empty string.
        val keyBindingJwt = if (tail.isNotEmpty() && tail.count { it == '.' } == 2) tail else null
        val disclosuresEnd = if (keyBindingJwt != null) segments.size - 1 else segments.size
        val disclosures =
            segments
                .subList(1, disclosuresEnd)
                .filter { it.isNotEmpty() }
        return SdJwtParts(issuerJwt, disclosures, keyBindingJwt)
    }

    private fun parseIssuerClaims(jws: JWSObject): JsonObject =
        runCatching { json.parseToJsonElement(jws.payload.toString()).jsonObject }
            .getOrElse { throw VerificationException("malformed_issuer_jwt_payload") }

    private fun requireString(
        obj: JsonObject,
        key: String,
        predicate: (String) -> Boolean,
    ): String? = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf(predicate)

    private fun decodeDisclosures(
        raw: List<String>,
        sdHashes: Set<String>,
    ): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        for (disclosure in raw) {
            val hash = sha256Base64UrlNoPad(disclosure.toByteArray(Charsets.US_ASCII))
            if (hash !in sdHashes) throw VerificationException("disclosure_hash_mismatch")
            val bytes =
                runCatching { Base64.getUrlDecoder().decode(disclosure) }
                    .getOrElse { throw VerificationException("disclosure_b64_invalid") }
            val triplet =
                runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray }
                    .getOrElse { throw VerificationException("disclosure_malformed") }
            if (triplet.size != 3) throw VerificationException("disclosure_arity_invalid")
            val name =
                triplet[1].jsonPrimitive.contentOrNull
                    ?: throw VerificationException("disclosure_missing_name")
            if (name in result) throw VerificationException("disclosure_duplicate")
            result[name] = triplet[2]
        }
        return result
    }

    private fun verifyKeyBinding(
        keyBindingJwt: String,
        cnfJwk: JsonObject,
        expectedNonce: String,
        nowEpoch: Long,
    ) {
        val holderKey =
            runCatching { JWK.parse(cnfJwk.toString()) }
                .getOrElse { throw VerificationException("invalid_cnf_jwk") }
        val kbJws =
            runCatching { JWSObject.parse(keyBindingJwt) }
                .getOrElse { throw VerificationException("malformed_kb_jwt") }
        if (!verifyJws(kbJws, holderKey)) throw VerificationException("kb_signature_invalid")
        val kbClaims =
            runCatching { json.parseToJsonElement(kbJws.payload.toString()).jsonObject }
                .getOrElse { throw VerificationException("malformed_kb_payload") }
        val nonce =
            kbClaims["nonce"]?.jsonPrimitive?.contentOrNull
                ?: throw VerificationException("missing_kb_nonce")
        if (nonce != expectedNonce) throw VerificationException("nonce_mismatch")
        val aud =
            kbClaims["aud"]?.jsonPrimitive?.contentOrNull
                ?: throw VerificationException("missing_kb_aud")
        if (aud != expectedAudience) throw VerificationException("audience_mismatch")
        val iat =
            kbClaims["iat"]?.jsonPrimitive?.longOrNull
                ?: throw VerificationException("missing_kb_iat")
        if (iat - allowedClockSkew.inWholeSeconds > nowEpoch) {
            throw VerificationException("kb_issued_in_future")
        }
    }

    private fun verifyJws(
        jws: JWSObject,
        jwk: JWK,
    ): Boolean =
        runCatching {
            when (jwk) {
                is ECKey -> jws.verify(ECDSAVerifier(jwk))
                is RSAKey -> jws.verify(RSASSAVerifier(jwk))
                else -> false
            }
        }.getOrElse { false }

    private fun Map<String, JsonElement>.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun Map<String, JsonElement>.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun sha256Base64UrlNoPad(input: ByteArray): String {
        val digest = sha256(input)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        const val EXPECTED_ISSUER = "https://verifiablecredentials-pa.googleapis.com"
        const val EXPECTED_VCT = "UserInfoCredential"
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = false
            }
    }
}

/** Reason codes are stable strings; safe to expose to clients in error responses. */
class VerificationException(
    message: String,
) : RuntimeException(message)

data class VerifiedEmailClaims(
    val email: String,
    val name: String?,
    val givenName: String?,
    val familyName: String?,
    val picture: String?,
    /** Hosted domain for workspace accounts; empty string for consumer Google. */
    val hostedDomain: String,
    val verifiedAt: Instant,
)
