package app.logdate.client.networking.atproto

import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectBeginRequest
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectRequest
import app.logdate.shared.model.AtprotoDisconnectResponse
import app.logdate.shared.model.AtprotoReconnectCompleteRequest
import app.logdate.shared.model.AtprotoReconnectStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Isolated request pipeline: ambient authentication, cookies and redirects are never inherited. */
class AtprotoConnectionApiClient(
    transport: HttpClient,
    backendOrigin: String,
) : AtprotoConnectionApi {
    override val backendOrigin = checkedOrigin(backendOrigin)
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        HttpClient(transport.engine) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }

    override suspend fun snapshot(accessToken: String): Result<AtprotoConnectionSnapshot> =
        safely { request<AtprotoConnectionSnapshot>(accessToken, HttpMethod.Get).checked() }

    override suspend fun beginDisconnect(
        accessToken: String,
        expectedRevision: Long,
    ): Result<AtprotoDisconnectChallenge> =
        safely {
            require(expectedRevision > 0)
            request<AtprotoDisconnectChallenge>(
                accessToken,
                HttpMethod.Post,
                "/step-up",
                json.encodeToString(AtprotoDisconnectBeginRequest(expectedRevision)),
            ).also {
                require(it.expectedRevision == expectedRevision && IDENTIFIER.matches(it.decisionId))
                require(IDENTIFIER.matches(it.authenticationOptions.challenge) && it.authenticationOptions.rpId == "logdate.app")
                require(it.authenticationOptions.allowCredentials.isNotEmpty())
                Instant.parse(it.expiresAt)
            }
        }

    override suspend fun disconnect(
        accessToken: String,
        encodedProof: String,
    ): Result<AtprotoDisconnectResponse> =
        safely {
            val proof = checkedProof(encodedProof)
            request<AtprotoDisconnectResponse>(accessToken, HttpMethod.Delete, body = encodedProof).also {
                it.current.checked()
                require(it.receipt.decisionId == proof.decisionId && it.receipt.disconnectedRevision > 0)
                require(it.current.revision >= it.receipt.disconnectedRevision)
                Instant.parse(it.receipt.completedAt)
            }
        }

    override suspend fun startReconnect(
        accessToken: String,
        challenge: String,
    ): Result<BlueskyStartResponse> =
        safely {
            require(IDENTIFIER.matches(challenge))
            request<BlueskyStartResponse>(
                accessToken,
                HttpMethod.Post,
                "/reconnect/start",
                json.encodeToString(AtprotoReconnectStartRequest(challenge)),
            ).also {
                require(IDENTIFIER.matches(it.attemptId) && validAuthorizationUrl(it.authorizationUrl))
            }
        }

    override suspend fun completeReconnect(
        accessToken: String,
        attemptId: String,
        verifier: String,
    ): Result<AtprotoConnectionSnapshot> =
        safely {
            require(IDENTIFIER.matches(attemptId) && VERIFIER.matches(verifier))
            request<AtprotoConnectionSnapshot>(
                accessToken,
                HttpMethod.Post,
                "/reconnect/complete",
                json.encodeToString(AtprotoReconnectCompleteRequest(attemptId, verifier)),
            ).checked()
        }

    override fun close() = client.close()

    private suspend inline fun <reified T> request(
        token: String,
        method: HttpMethod,
        path: String = "",
        body: String? = null,
    ): T {
        require(token.isNotBlank() && token.length <= 16384 && token.none { it.isWhitespace() || it.isISOControl() })
        require(body == null || body.encodeToByteArray().size <= 65536)
        return client
            .prepareRequest("$backendOrigin/api/v1/auth/atproto/connection$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                timeout {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 15_000
                }
            }.execute { response ->
                val success = response.status.value == 200
                val limit = if (success) 65536L else 8192L
                require(response.contentLength()?.let { it <= limit } != false)
                val bytes = response.bodyAsChannel().readRemaining(limit + 1).readByteArray()
                require(bytes.size <= limit)
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                if (!success) throw AtprotoConnectionApiException(failure(response.status.value, text))
                json.decodeFromString<T>(text)
            }
    }

    private fun AtprotoConnectionSnapshot.checked(): AtprotoConnectionSnapshot {
        require(revision >= 0 && (!canDisconnect || (eligible && state == AtprotoConnectionState.CONNECTED)))
        require(state != AtprotoConnectionState.CONNECTED || (eligible && revision > 0))
        Instant.parse(observedAt)
        return this
    }

    private fun checkedProof(raw: String): AtprotoDisconnectRequest {
        require(raw.encodeToByteArray().size <= 65536)
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid action")
        require(root.keys == setOf("decisionId", "challenge", "credential"))
        val credential = root["credential"] as? JsonObject ?: error("Invalid action")
        require(
            credential.keys.containsAll(setOf("id", "rawId", "response")) &&
                credential.keys.all { it in setOf("id", "rawId", "type", "response") },
        )
        val response = credential["response"] as? JsonObject ?: error("Invalid action")
        require(response.keys == setOf("clientDataJSON", "authenticatorData", "signature", "userHandle"))
        return json.decodeFromJsonElement<AtprotoDisconnectRequest>(root).also {
            require(IDENTIFIER.matches(it.decisionId) && IDENTIFIER.matches(it.challenge))
            require(it.credential.type == "public-key")
        }
    }

    private fun failure(
        status: Int,
        body: String,
    ): AtprotoConnectionApiFailure {
        val code =
            runCatching {
                val root = json.parseToJsonElement(body) as JsonObject
                (root["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.content
            }.getOrNull()
        return when {
            status == 401 || (status == 403 && code == "DEVICE_SCOPE_DENIED") -> AtprotoConnectionApiFailure.UNAUTHORIZED
            status == 403 && code == "CONNECTION_PROOF_INVALID" -> AtprotoConnectionApiFailure.PROOF_INVALID
            status == 409 && code == "CONNECTION_CHANGED" -> AtprotoConnectionApiFailure.CHANGED
            status == 409 && code == "LAST_SIGNIN_FACTOR" -> AtprotoConnectionApiFailure.LAST_SIGN_IN_FACTOR
            status == 409 && code == "CONNECTION_INELIGIBLE" -> AtprotoConnectionApiFailure.INELIGIBLE
            status == 410 &&
                code in
                setOf(
                    "CONNECTION_PROOF_EXPIRED",
                    "CONNECTION_RECONNECT_INVALID",
                )
            -> AtprotoConnectionApiFailure.PROOF_EXPIRED
            status == 429 -> AtprotoConnectionApiFailure.RATE_LIMITED
            else -> AtprotoConnectionApiFailure.UNAVAILABLE
        }
    }

    private suspend inline fun <T> safely(work: () -> T): Result<T> =
        try {
            Result.success(work())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (bounded: AtprotoConnectionApiException) {
            Result.failure(bounded)
        } catch (_: Exception) {
            Result.failure(AtprotoConnectionApiException(AtprotoConnectionApiFailure.INVALID_RESPONSE))
        }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
        val VERIFIER = Regex("[A-Za-z0-9_-]{43,128}")

        fun checkedOrigin(value: String): String {
            require(value.length <= 2048 && Regex("https://[^/?#@\\s]+").matches(value))
            val url = Url(value)
            require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.port in 1..65535)
            return value
        }

        fun validAuthorizationUrl(value: String): Boolean {
            if (value.length > 8192 || '#' in value || value.any { it.isWhitespace() || it.isISOControl() }) return false
            val url = Url(value)
            return url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
        }
    }
}
