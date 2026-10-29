package app.logdate.client.networking.bluesky

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationResponse
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerInfoResponse
import app.logdate.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

interface BlueskyApi {
    val backendOrigin: String

    fun close() = Unit

    suspend fun start(request: BlueskyStartRequest): Result<BlueskyStartResponse>

    suspend fun exchange(request: BlueskyExchangeRequest): Result<BlueskyExchangeResponse>

    suspend fun beginRegistration(request: BlueskyRegistrationBeginRequest): Result<BlueskyRegistrationBeginResponse>

    suspend fun completeRegistration(request: BlueskyRegistrationCompleteRequest): Result<CompleteAuthenticationData>

    suspend fun beginAuthentication(request: BlueskyAuthenticationBeginRequest): Result<BlueskyAuthenticationBeginResponse>

    suspend fun completeAuthentication(request: BlueskyAuthenticationCompleteRequest): Result<CompleteAuthenticationData>
}

/** Owns its request pipeline, while the injected transport continues to own its shared engine. */
class BlueskyApiClient(
    transport: HttpClient,
    backendOrigin: String,
) : BlueskyApi {
    private val origin = checkedOrigin(backendOrigin)
    override val backendOrigin: String get() = origin
    private val client =
        HttpClient(transport.engine) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
    private val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule { contextual(Uuid::class, UuidSerializer) }
        }

    override suspend fun start(request: BlueskyStartRequest): Result<BlueskyStartResponse> =
        post<BlueskyStartRequest, BlueskyStartResponse>("start", request).mapCatching { response ->
            if (!IDENTIFIER.matches(response.attemptId) || !validAuthorizationUrl(response.authorizationUrl)) {
                throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
            }
            response
        }

    override suspend fun exchange(request: BlueskyExchangeRequest): Result<BlueskyExchangeResponse> = post("exchange", request)

    override suspend fun beginRegistration(request: BlueskyRegistrationBeginRequest): Result<BlueskyRegistrationBeginResponse> =
        post("registration/begin", request)

    override suspend fun completeRegistration(request: BlueskyRegistrationCompleteRequest): Result<CompleteAuthenticationData> =
        session("registration/complete", request)

    override suspend fun beginAuthentication(request: BlueskyAuthenticationBeginRequest): Result<BlueskyAuthenticationBeginResponse> =
        post("authentication/begin", request)

    override suspend fun completeAuthentication(request: BlueskyAuthenticationCompleteRequest): Result<CompleteAuthenticationData> =
        session("authentication/complete", request)

    override fun close() = client.close()

    suspend fun serverDescriptor(): Result<ServerDescriptor> =
        boundedRequest {
            val response =
                client
                    .prepareGet("$origin/api/v1/server/info") {
                        timeout {
                            requestTimeoutMillis = 30_000
                            connectTimeoutMillis = 5_000
                            socketTimeoutMillis = 15_000
                        }
                    }.execute { decodeBounded<ServerInfoResponse>(it) }
            if (!response.success || response.data.serverOrigin != origin || response.data.apiBaseUrl != "$origin/api/v1") {
                throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
            }
            response.data
        }

    private suspend inline fun <reified Request : Any> session(
        path: String,
        request: Request,
    ): Result<CompleteAuthenticationData> =
        post<Request, JsonObject>(path, request).fold(
            onSuccess = { body ->
                try {
                    val account =
                        body
                            .getValue("data")
                            .jsonObject
                            .getValue("account")
                            .jsonObject
                    require(SESSION_FIELDS.all { it in account })
                    val response = json.decodeFromJsonElement<CompleteAuthenticationResponse>(body)
                    require(
                        !response.data.account.did
                            .isNullOrBlank(),
                    )
                    require(
                        response.success &&
                            response.data.account.identityKind == AccountIdentityKind.EXTERNAL &&
                            response.data.account.storageMode == AccountStorageMode.PERSONAL_SPACE_V1,
                    )
                    Result.success(response.data)
                } catch (_: IllegalArgumentException) {
                    Result.failure(BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE))
                } catch (_: NoSuchElementException) {
                    Result.failure(BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE))
                }
            },
            onFailure = { Result.failure(it) },
        )

    private suspend inline fun <reified Request : Any, reified Response> post(
        path: String,
        request: Request,
    ): Result<Response> =
        boundedRequest {
            client
                .preparePost("$origin/api/v1/auth/bluesky/$path") {
                    contentType(ContentType.Application.Json)
                    timeout {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 5_000
                        socketTimeoutMillis = 15_000
                    }
                    setBody(json.encodeToString(request))
                }.execute { decodeBounded<Response>(it) }
        }

    private suspend inline fun <reified Response> decodeBounded(response: HttpResponse): Response {
        val success = response.status == HttpStatusCode.OK
        val limit = if (success) MAX_RESPONSE_BYTES else MAX_ERROR_BYTES
        if (response.contentLength()?.let { it > limit } == true) {
            throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
        }
        val bytes = response.bodyAsChannel().readRemaining(limit + 1).readByteArray()
        if (bytes.size > limit) throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
        val text = bytes.decodeToString()
        if (!success) throw BlueskyApiException(failureKind(response.status.value, text))
        return try {
            json.decodeFromString<Response>(text)
        } catch (_: IllegalArgumentException) {
            throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
        }
    }

    private suspend inline fun <Response> boundedRequest(block: () -> Response): Result<Response> =
        try {
            Result.success(block())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: BlueskyApiException) {
            Result.failure(failure)
        } catch (_: Exception) {
            // No transport cause, body or URL can enter a UI error or diagnostic string.
            Result.failure(BlueskyApiException(BlueskyApiFailure.UNAVAILABLE))
        }

    private fun failureKind(
        status: Int,
        body: String,
    ): BlueskyApiFailure {
        val code =
            try {
                json.decodeFromString<ErrorResponse>(body).error.code
            } catch (_: IllegalArgumentException) {
                null
            }
        return when (status to code) {
            400 to "BLUESKY_CANCELED" -> BlueskyApiFailure.CANCELED
            401 to "BLUESKY_HANDOFF_EXPIRED" -> BlueskyApiFailure.EXPIRED
            400 to "VALIDATION_ERROR", 400 to "INVALID_REQUEST" -> BlueskyApiFailure.INVALID_REQUEST
            409 to "ACCOUNT_LINK_CONFLICT" -> BlueskyApiFailure.CONFLICT
            401 to "PASSKEY_VERIFICATION_FAILED" -> BlueskyApiFailure.PASSKEY_FAILED
            429 to "RATE_LIMIT_EXCEEDED" -> BlueskyApiFailure.RATE_LIMITED
            else -> BlueskyApiFailure.UNAVAILABLE
        }
    }

    @Serializable
    private class ErrorResponse(
        val error: ErrorCode,
    )

    @Serializable
    private class ErrorCode(
        val code: String,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024L
        const val MAX_ERROR_BYTES = 8192L
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
        val SESSION_FIELDS = setOf("id", "username", "displayName", "did", "createdAt", "updatedAt", "storageMode", "identityKind")
        val ORIGIN = Regex("https://[^/?#@\\s]+")

        fun validAuthorizationUrl(value: String): Boolean {
            if (value.length > 8192 ||
                !value.startsWith("https://") ||
                value.any { it.isWhitespace() || it.isISOControl() } ||
                '#' in value
            ) {
                return false
            }
            return try {
                val url = Url(value)
                url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun checkedOrigin(value: String): String {
            require(value.length <= 2048 && ORIGIN.matches(value)) { "The sign-in server is invalid" }
            val url =
                try {
                    Url(value)
                } catch (
                    _: IllegalArgumentException,
                ) {
                    throw IllegalArgumentException("The sign-in server is invalid")
                }
            require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.port in 1..65535) { "The sign-in server is invalid" }
            return value
        }
    }
}

enum class BlueskyApiFailure { CANCELED, EXPIRED, INVALID_REQUEST, CONFLICT, PASSKEY_FAILED, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE }

class BlueskyApiException(
    val kind: BlueskyApiFailure,
) : IllegalStateException(
        when (kind) {
            BlueskyApiFailure.CANCELED -> "Bluesky sign-in was canceled"
            BlueskyApiFailure.EXPIRED -> "This sign-in request has expired"
            BlueskyApiFailure.INVALID_REQUEST -> "Check the account details"
            BlueskyApiFailure.CONFLICT -> "This account could not be created or confirmed"
            BlueskyApiFailure.PASSKEY_FAILED -> "The passkey could not be confirmed"
            BlueskyApiFailure.RATE_LIMITED -> "Too many sign-in requests"
            BlueskyApiFailure.UNAVAILABLE -> "Bluesky sign-in is unavailable"
            BlueskyApiFailure.INVALID_RESPONSE -> "The sign-in response could not be read"
        },
    )
