package studio.hypertext.atproto.oauth.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.currentCoroutineContext
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Names the session a data server request is made for. [OAuthSession] adds it around each request
 * it makes through the shared [DataServerHttp] client.
 */
internal class OAuthSessionContext(
    val did: AtprotoDid,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OAuthSessionContext>
}

/**
 * The one HTTP client an [AtprotoOAuthClient] uses for every session's data server requests.
 *
 * Sessions are created on every restore, so each one building its own `HttpClient` would leave a
 * client attached to the shared engine per restore. Instead, every session shares [client], and
 * its DPoP plugin finds the session from the [OAuthSessionContext] around the request.
 */
internal class DataServerHttp(
    base: HttpClient,
    private val manager: OAuthSessionManager,
    private val crypto: OAuthCrypto,
    private val nonces: DpopNonceCache,
    private val clock: Clock,
) : AutoCloseable {
    val client: HttpClient = dataServerHttpClient(base) { install(dpopAuthentication()) }

    override fun close() {
        client.close()
    }

    private fun dpopAuthentication() =
        createClientPlugin("AtprotoOAuthDpop") {
            on(Send) { request ->
                val did =
                    currentCoroutineContext()[OAuthSessionContext]?.did
                        ?: error("Data server requests must be made through an OAuthSession")
                var session = manager.session(did, OAuthRefreshMode.AUTO)
                requireDataServerOrigin(did, request, session)

                suspend fun sendWithNonceRetry(): HttpResponse {
                    val sentNonce = authorize(request, session, nonces.get(request.url.buildString()))
                    var response = proceed(request).response
                    val freshNonce = rememberNonce(request, response)?.takeIf { it != sentNonce }
                    if (freshNonce != null && response.isDpopError("use_dpop_nonce")) {
                        authorize(request, session, freshNonce)
                        response = proceed(request).response
                        rememberNonce(request, response)
                    }
                    return response
                }

                var response = sendWithNonceRetry()
                if (response.isDpopError("invalid_token")) {
                    session = manager.renewAfterRejection(session)
                    // The renewal re-resolves the account, which may now live on another data server.
                    requireDataServerOrigin(did, request, session)
                    response = sendWithNonceRetry()
                    if (response.isDpopError("invalid_token")) {
                        manager.endAfterRejection(
                            session,
                            OAuthSessionRevokedException(did, "the data server rejected a freshly renewed access token"),
                        )
                    }
                }
                response.call
            }
        }

    private fun requireDataServerOrigin(
        did: AtprotoDid,
        request: HttpRequestBuilder,
        session: OAuthStoredSession,
    ) {
        val targetOrigin = request.url.build().origin()
        val pdsOrigin = Url(session.pdsUrl).origin()
        if (targetOrigin != pdsOrigin) throw OAuthForeignOriginException(did, targetOrigin, pdsOrigin)
    }

    /**
     * Adds the access token and a DPoP proof carrying [nonce], and returns [nonce].
     */
    private fun authorize(
        request: HttpRequestBuilder,
        session: OAuthStoredSession,
        nonce: String?,
    ): String? {
        val proof =
            crypto.createDpopProof(
                dpopKey = session.dpopKey,
                htm = request.method.value,
                htu = dpopHtu(request.url.buildString()),
                nonce = nonce,
                accessToken = session.accessToken,
                now = clock.now(),
            )
        request.headers[HttpHeaders.Authorization] = "DPoP ${session.accessToken}"
        request.headers[DPOP_HEADER] = proof
        return nonce
    }

    /**
     * Stores and returns the response's DPoP nonce, if it has one.
     */
    private suspend fun rememberNonce(
        request: HttpRequestBuilder,
        response: HttpResponse,
    ): String? {
        val nonce = response.headers[DPOP_NONCE_HEADER] ?: return null
        nonces.put(request.url.buildString(), nonce)
        return nonce
    }
}

/**
 * Whether the response is a 401 whose `WWW-Authenticate` challenge carries [error]
 * (RFC 6750 section 3, RFC 9449 section 9).
 */
private fun HttpResponse.isDpopError(error: String): Boolean {
    if (status != HttpStatusCode.Unauthorized) return false
    val challenge = headers[HttpHeaders.WWWAuthenticate] ?: return false
    val scheme = challenge.substringBefore(' ')
    val isTokenChallenge = scheme.equals("DPoP", ignoreCase = true) || scheme.equals("Bearer", ignoreCase = true)
    return isTokenChallenge && challenge.contains("error=\"$error\"")
}
