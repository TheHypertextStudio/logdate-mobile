package app.logdate.server.auth

import io.github.aakira.napier.Napier
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class GoogleIdTokenClaims(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String? = null,
    val picture: String? = null,
    val issuer: String,
    val audience: String,
    val expiresAtEpochSeconds: Long,
    val issuedAtEpochSeconds: Long,
)

interface GoogleIdTokenVerifier {
    fun isConfigured(): Boolean = true

    suspend fun verify(
        idToken: String,
        nonce: String?,
    ): GoogleIdTokenClaims?
}

/**
 * Verifies Google ID tokens with Google's `tokeninfo` endpoint.
 *
 * The call to Google is asynchronous and bounded by [requestTimeout], so a slow response never holds
 * a request thread. When the caller expects a nonce, the token must carry that same nonce; a token
 * without one is refused, so an ID token issued for another sign-in can't be replayed.
 */
class HttpGoogleIdTokenVerifier(
    private val allowedClientIds: Set<String>,
    private val acceptedIssuers: Set<String> = setOf("https://accounts.google.com", "accounts.google.com"),
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
) : GoogleIdTokenVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun isConfigured(): Boolean = allowedClientIds.isNotEmpty()

    override suspend fun verify(
        idToken: String,
        nonce: String?,
    ): GoogleIdTokenClaims? {
        if (idToken.isBlank()) {
            return null
        }

        if (allowedClientIds.isEmpty()) {
            Napier.e("GOOGLE_OIDC_CLIENT_IDS is not configured")
            return null
        }

        val encodedToken = URLEncoder.encode(idToken, StandardCharsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token=$encodedToken"))
                .timeout(requestTimeout)
                .GET()
                .build()

        return try {
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() !in 200..299) {
                Napier.w("Google tokeninfo returned ${response.statusCode()}")
                null
            } else {
                parseAndValidate(response.body(), nonce)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Failed to verify Google ID token", e)
            null
        }
    }

    private fun parseAndValidate(
        body: String,
        nonce: String?,
    ): GoogleIdTokenClaims? {
        val obj = json.parseToJsonElement(body).jsonObject

        val subject = obj["sub"]?.jsonPrimitive?.content ?: return null
        val email = obj["email"]?.jsonPrimitive?.content ?: return null
        val emailVerified = obj["email_verified"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val issuer = obj["iss"]?.jsonPrimitive?.content ?: return null
        val audience = obj["aud"]?.jsonPrimitive?.content ?: return null
        val exp = obj["exp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val iat = obj["iat"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val tokenNonce = obj["nonce"]?.jsonPrimitive?.content
        val now = System.currentTimeMillis() / 1000

        if (!acceptedIssuers.contains(issuer)) {
            Napier.w("Google issuer not allowed: $issuer")
            return null
        }

        if (!allowedClientIds.contains(audience)) {
            Napier.w("Google audience not allowed: $audience")
            return null
        }

        if (exp <= now) {
            Napier.w("Google ID token is expired")
            return null
        }

        if (nonce != null && nonce != tokenNonce) {
            Napier.w("Google ID token nonce is missing or does not match")
            return null
        }

        return GoogleIdTokenClaims(
            subject = subject,
            email = email,
            emailVerified = emailVerified,
            name = obj["name"]?.jsonPrimitive?.content,
            picture = obj["picture"]?.jsonPrimitive?.content,
            issuer = issuer,
            audience = audience,
            expiresAtEpochSeconds = exp,
            issuedAtEpochSeconds = iat,
        )
    }
}

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
