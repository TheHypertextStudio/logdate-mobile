package app.logdate.server.bluesky

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.identity.DidWebResolver
import studio.hypertext.atproto.identity.IdentityResolver
import studio.hypertext.atproto.identity.claimedHandle
import studio.hypertext.atproto.syntax.Handle
import java.net.URI

internal fun interface BlueskyTxtLookup {
    suspend fun records(name: String): List<String>
}

/** Public identity resolution for server-owned sign-in; the supplied HTTP engine must reject private addresses. */
internal class BlueskyIdentityResolver(
    private val http: HttpClient,
    private val txt: BlueskyTxtLookup,
) : IdentityResolver {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolveDid(handle: Handle): Result<AtprotoDid> =
        verified {
            require(!forbiddenDomain(handle.normalized))
            val answers = if (handle.normalized.length + 9 <= 253) dns(handle) else emptyList()
            val identities =
                answers
                    .mapNotNull { value ->
                        if (value.startsWith("did=")) AtprotoDid.parse(value.removePrefix("did=")).getOrNull() else null
                    }.distinct()
            require(identities.size <= 1)
            identities.singleOrNull() ?: AtprotoDid.require(fetch("https://${handle.normalized}/.well-known/atproto-did", 8192).trim())
        }

    override suspend fun resolveDocument(did: AtprotoDid): Result<DidDocument> =
        verified {
            val url =
                when (did.method) {
                    "plc" -> "https://plc.directory/$did"
                    "web" -> DidWebResolver(http).urlFor(did).also { require(URI(it).port == -1) }
                    else -> error("Unsupported identity")
                }
            json.decodeFromString<DidDocument>(fetch(url, 65_536)).also { require(it.id == did) }
        }

    override suspend fun resolveDocument(handle: Handle): Result<DidDocument> =
        verified {
            val did = resolveDid(handle).getOrThrow()
            resolveDocument(did).getOrThrow().also { document ->
                require(document.claimedHandle()?.normalized == handle.normalized)
            }
        }

    private suspend fun dns(handle: Handle): List<String> =
        try {
            txt.records("_atproto.${handle.normalized}")
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (invalid: BlueskyInvalidDnsAnswer) {
            throw invalid
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun fetch(
        url: String,
        limit: Long,
    ): String {
        var current = url
        repeat(4) { hop ->
            val uri = URI(current)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443))
            require(!forbiddenDomain(requireNotNull(uri.host)))
            val reply =
                http.prepareGet(current).execute { response ->
                    if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                        val location = requireNotNull(response.headers[HttpHeaders.Location])
                        require(location.length <= 4096)
                        Reply.Redirect(location)
                    } else {
                        require(response.status.value in 200..299)
                        require(response.contentLength()?.let { it <= limit } != false)
                        val bytes = response.bodyAsChannel().readRemaining(limit + 1).readByteArray()
                        require(bytes.size <= limit)
                        Reply.Body(bytes.decodeToString())
                    }
                }
            when (reply) {
                is Reply.Body -> return reply.value
                is Reply.Redirect -> {
                    require(hop < 3)
                    current = uri.resolve(reply.location).toString()
                }
            }
        }
        error("Identity resolution did not finish")
    }

    private suspend fun <T> verified(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            Result.failure(IllegalArgumentException("The account identity could not be verified"))
        }

    private sealed interface Reply {
        data class Body(
            val value: String,
        ) : Reply

        data class Redirect(
            val location: String,
        ) : Reply
    }
}

internal class BlueskyInvalidDnsAnswer : IllegalArgumentException("The DNS answer could not be verified")

internal fun forbiddenDomain(host: String): Boolean =
    host.substringAfterLast('.').lowercase() in
        setOf("alt", "arpa", "example", "internal", "invalid", "local", "localhost", "onion", "test")
