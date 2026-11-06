package app.logdate.server.atproto

import app.logdate.server.auth.Account
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.identity.DidPlcResolver
import studio.hypertext.atproto.identity.DidWebResolver
import java.net.URI
import kotlin.uuid.ExperimentalUuidApi

class AtprotoServiceProxy(
    private val http: HttpClient,
    private val issuer: AtprotoServiceAuth,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val web = DidWebResolver(http)
    private val plc = DidPlcResolver(http)
    private val limiter = FixedWindowRateLimiter()
    private val inFlight = Semaphore(16)

    @OptIn(ExperimentalUuidApi::class)
    internal suspend fun forward(
        account: Account,
        reference: String,
        method: String,
        request: ProxyRequest,
    ): ProxyResponse {
        val service = parseReference(reference)
        if (!limiter.allow(account.id.toString(), PROXY_RATE_LIMIT)) {
            throw ProxyException(HttpStatusCode.TooManyRequests, "RateLimitExceeded", "Too many service requests.")
        }
        if (!inFlight.tryAcquire()) {
            throw ProxyException(HttpStatusCode.ServiceUnavailable, "TemporarilyUnavailable", "Service forwarding is busy.")
        }
        try {
            // Authorize before resolving a caller-selected service.
            val audience = if (reference in LEGACY_BLUESKY_SERVICES) service.did.toString() else reference
            val token = issuer.issue(account, audience, method)
            return send(service, method, request, token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProxyException) {
            throw error
        } catch (error: ServiceAuthException) {
            throw error
        } catch (_: Exception) {
            throw unavailable()
        } finally {
            inFlight.release()
        }
    }

    private suspend fun send(
        service: ServiceReference,
        method: String,
        request: ProxyRequest,
        token: String,
    ): ProxyResponse {
        val body =
            if (request.method == HttpMethod.Post) {
                request.body.readProxyBytes(MAX_PROXY_REQUEST_BYTES) ?: throw oversizedRequest()
            } else {
                null
            }
        val origin = resolveService(service)
        val url = URLBuilder(origin)
        url.encodedPath = "/xrpc/$method"
        url.parameters.appendAll(request.query)
        val outbound =
            http.prepareRequest(url.build()) {
                this.method = request.method
                headers.append(HttpHeaders.Authorization, "Bearer $token")
                request.accept.forEach { headers.append(HttpHeaders.Accept, it) }
                request.labelers.forEach { headers.append(ACCEPT_LABELERS, it) }
                if (body != null) {
                    request.contentType?.let { headers.append(HttpHeaders.ContentType, it) }
                    setBody(body)
                }
            }
        return outbound.execute { response ->
            if (response.status.value in 400..499) return@execute ProxyResponse(response.status)
            if (!response.status.isSuccess()) throw unavailable()
            ProxyResponse(
                response.status,
                response.bodyAsChannel().readProxyBytes(MAX_PROXY_RESPONSE_BYTES) ?: throw unavailable(),
                response.headers[HttpHeaders.ContentType],
                response.headers.getAll(CONTENT_LABELERS).orEmpty(),
            )
        }
    }

    private fun parseReference(reference: String): ServiceReference {
        val did = AtprotoDid.parse(reference.substringBefore('#')).getOrNull()
        val fragment = reference.substringAfter('#', "")
        if (reference.length > 2048 || did == null || !SERVICE_FRAGMENT.matches(fragment)) {
            throw ProxyException(HttpStatusCode.BadRequest, "InvalidRequest", "Provide one service DID and fragment.")
        }
        val documentUrl =
            runCatching {
                when {
                    did.toString().startsWith("did:web:") -> web.urlFor(did)
                    did.toString().startsWith("did:plc:") -> plc.urlFor(did)
                    else -> null
                }
            }.getOrNull() ?: throw ProxyException(HttpStatusCode.BadRequest, "InvalidRequest", "The service DID is unsupported.")
        return ServiceReference(did, fragment, documentUrl)
    }

    private suspend fun resolveService(reference: ServiceReference): String =
        http.prepareGet(reference.documentUrl).execute { response ->
            if (response.status != HttpStatusCode.OK) throw unavailable()
            val bytes = response.bodyAsChannel().readProxyBytes(MAX_DID_DOCUMENT_BYTES) ?: throw unavailable()
            val document = json.decodeFromString<DidDocument>(bytes.decodeToString())
            if (document.id != reference.did) throw unavailable()
            val fragment = "#${reference.fragment}"
            val service = document.service.singleOrNull { it.id == fragment || it.id == "${reference.did}$fragment" }
            val origin = service?.serviceEndpoint?.let(::URI) ?: throw unavailable()
            if (origin.scheme != "https" || origin.host == null || origin.rawUserInfo != null ||
                origin.rawQuery != null || origin.rawFragment != null || origin.rawPath !in listOf("", "/") ||
                origin.port !in -1..65535 || origin.port == 0
            ) {
                throw unavailable()
            }
            origin.toASCIIString()
        }

    private data class ServiceReference(
        val did: AtprotoDid,
        val fragment: String,
        val documentUrl: String,
    )
}

internal data class ProxyRequest(
    val method: HttpMethod,
    val query: Parameters,
    val accept: List<String>,
    val labelers: List<String>,
    val contentType: String?,
    val body: ByteReadChannel,
)

internal data class ProxyResponse(
    val status: HttpStatusCode,
    val body: ByteArray = byteArrayOf(),
    val contentType: String? = null,
    val labelers: List<String> = emptyList(),
)

internal class ProxyException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

private fun oversizedRequest() = ProxyException(HttpStatusCode.PayloadTooLarge, "PayloadTooLarge", "The service request is too large.")

private fun unavailable() = ProxyException(HttpStatusCode.BadGateway, "UpstreamFailure", "The service could not complete the request.")

private suspend fun ByteReadChannel.readProxyBytes(limit: Long): ByteArray? =
    readRemaining(limit + 1).readByteArray().takeIf { it.size <= limit }

internal const val ACCEPT_LABELERS = "atproto-accept-labelers"
internal const val CONTENT_LABELERS = "atproto-content-labelers"
private const val MAX_DID_DOCUMENT_BYTES = 64L * 1024
private const val MAX_PROXY_REQUEST_BYTES = 2L * 1024 * 1024
private const val MAX_PROXY_RESPONSE_BYTES = 8L * 1024 * 1024
private val SERVICE_FRAGMENT = Regex("[a-zA-Z0-9._~-]+")
private val PROXY_RATE_LIMIT = RateLimitPolicy(maxRequests = 120, windowSeconds = 60)

internal const val BLUESKY_APPVIEW = "did:web:api.bsky.app#bsky_appview"
internal const val BLUESKY_CHAT = "did:web:api.bsky.chat#bsky_chat"
internal const val BLUESKY_MODERATION = "did:plc:ar7c4by46qjdydhdevvrndac#atproto_labeler"

// These services currently receive bare-DID audiences from the reference PDS.
private val LEGACY_BLUESKY_SERVICES = setOf(BLUESKY_APPVIEW, BLUESKY_CHAT, BLUESKY_MODERATION)
