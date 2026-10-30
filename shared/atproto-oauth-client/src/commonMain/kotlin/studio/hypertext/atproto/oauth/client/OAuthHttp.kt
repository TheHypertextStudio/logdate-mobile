package studio.hypertext.atproto.oauth.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.seconds

/**
 * The largest metadata document the client reads. Real documents are a few kilobytes.
 */
public const val MAX_METADATA_BYTES: Long = 64L * 1024

/**
 * The largest pushed authorization, token, or revocation response the client reads.
 */
internal const val MAX_AUTHORIZATION_SERVER_RESPONSE_BYTES: Long = 64L * 1024

private val CONNECT_TIMEOUT = 10.seconds
private val PROTOCOL_REQUEST_TIMEOUT = 20.seconds

/**
 * Builds the client used for metadata, pushed authorization, token, and revocation requests.
 *
 * It shares [base]'s engine but none of its plugins, so a caller's logging, retry, or default
 * header plugins never see client assertions or tokens. It never follows redirects (a metadata
 * document or token endpoint that redirects is refused) and gives up on slow servers.
 *
 * An authorization server that fetches documents its clients name, such as client metadata and
 * JWKS documents, can use it the same way: pass `JvmOAuthHttp.publicInternetClient()` as [base] on
 * the JVM so those fetches only reach public addresses, and read bodies with [bodyAsTextCapped].
 */
public fun protocolHttpClient(base: HttpClient): HttpClient =
    HttpClient(base.engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
            requestTimeoutMillis = PROTOCOL_REQUEST_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = PROTOCOL_REQUEST_TIMEOUT.inWholeMilliseconds
        }
    }

/**
 * Builds the shared data server client behind [OAuthSession.request]: [base]'s engine without its
 * plugins, no redirects (the access token must reach only the data server), a connect timeout,
 * and the [plugins] given.
 */
internal fun dataServerHttpClient(
    base: HttpClient,
    plugins: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(base.engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds }
        plugins()
    }

/**
 * A response body was larger than the client is willing to read.
 */
public class ResponseTooLargeException(
    public val limit: Long,
) : Exception("The response is larger than $limit bytes")

/**
 * Reads the body of a streaming response as UTF-8, refusing to read more than [limit] bytes.
 *
 * @throws ResponseTooLargeException when the declared or actual body is larger than [limit].
 */
public suspend fun HttpResponse.bodyAsTextCapped(limit: Long): String {
    contentLength()?.let { if (it > limit) throw ResponseTooLargeException(limit) }
    val bytes = bodyAsChannel().readRemaining(limit + 1).readByteArray()
    if (bytes.size > limit) throw ResponseTooLargeException(limit)
    return bytes.decodeToString()
}
