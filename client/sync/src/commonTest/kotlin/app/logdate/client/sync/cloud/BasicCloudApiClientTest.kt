package app.logdate.client.sync.cloud

import app.logdate.client.sync.test.uploadMedia
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.PasskeyAuthenticatorResponse
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Smoke tests for auth-relevant API mappings in [LogDateCloudApiClient].
 */
class BasicCloudApiClientTest {
    @Test
    fun `creating content uses the existing create-only server precondition`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
                        respond(
                            """{"id":"entry","serverVersion":42,"uploadedAt":2}""",
                            HttpStatusCode.Created,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )
            assertTrue(
                client
                    .uploadContent(
                        "token",
                        app.logdate.shared.model.sync.ContentUploadRequest(
                            id = "entry",
                            type = "TEXT",
                            content = "Original entry",
                            mediaUri = null,
                            createdAt = 1,
                            lastUpdated = 2,
                        ),
                    ).isSuccess,
            )
        }

    private val baseUrl = "https://api.logdate.example.com/v1"
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            serializersModule =
                SerializersModule {
                    contextual(Uuid::class, UuidSerializer)
                }
        }

    @Test
    fun `username availability uses auth v1 path`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/signup/username/testuser/available", request.url.toString())
                        respond(
                            content = """{"success":true,"data":{"username":"testuser","available":true}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result = client.checkUsernameAvailability("testuser")
            assertTrue(result.isSuccess)
            assertEquals("testuser", result.getOrThrow().username)
            assertTrue(result.getOrThrow().available)
        }

    @Test
    fun `begin account creation uses auth v1 passkey signup path`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/signup/passkey/begin", request.url.toString())
                        respond(
                            content =
                                """
                                {
                                  "success": true,
                                  "data": {
                                    "sessionToken": "sess123",
                                    "registrationOptions": {
                                      "challenge": "challenge123",
                                      "rpId": "logdate.app",
                                      "rpName": "LogDate",
                                      "user": {"id":"u","name":"newuser","displayName":"New User"},
                                      "pubKeyCredParams": [{"type":"public-key","alg":-7}],
                                      "timeout": 300000
                                    }
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result = client.beginAccountCreation(BeginAccountCreationRequest("newuser", "New User"))
            assertTrue(result.isSuccess)
            assertEquals("sess123", result.getOrThrow().data.sessionToken)
        }

    @Test
    fun `complete account creation maps auth response to legacy response model`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/signup/passkey/complete", request.url.toString())
                        respond(
                            content =
                                """
                                {
                                  "success": true,
                                  "data": {
                                    "account": {
                                      "id": "550e8400-e29b-41d4-a716-446655440000",
                                      "username": "newuser",
                                      "displayName": "New User",
                                      "did": "did:web:newuser.logdate.app",
                                      "handle": "newuser.logdate.app",
                                      "passkeyCredentialIds": ["cred-1"],
                                      "createdAt": "2026-03-05T00:00:00Z",
                                      "updatedAt": "2026-03-05T00:00:00Z"
                                    },
                                    "tokens": {"accessToken":"access","refreshToken":"refresh"}
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result =
                client.completeAccountCreation(
                    CompleteAccountCreationRequest(
                        sessionToken = "sess123",
                        credential =
                            PasskeyCredentialResponse(
                                id = "cred-1",
                                rawId = "cred-1",
                                response = PasskeyAuthenticatorResponse("client", "attestation"),
                            ),
                    ),
                )

            assertTrue(result.isSuccess)
            assertEquals(
                "newuser",
                result
                    .getOrThrow()
                    .data
                    .account
                    .username,
            )
            assertEquals(
                "did:web:newuser.logdate.app",
                result
                    .getOrThrow()
                    .data.account.did,
            )
            assertEquals(
                "newuser.logdate.app",
                result
                    .getOrThrow()
                    .data.account.handle,
            )
            assertEquals(
                "access",
                result
                    .getOrThrow()
                    .data
                    .tokens
                    .accessToken,
            )
        }

    @Test
    fun `refresh token uses auth v1 path`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/token/refresh", request.url.toString())
                        respond(
                            content = """{"success":true,"data":{"accessToken":"new-access"}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result = client.refreshAccessToken("refresh")
            assertTrue(result.isSuccess)
            assertEquals("new-access", result.getOrThrow())
        }

    @Test
    fun `a session refresh returns a rotated refresh token`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/token/refresh", request.url.toString())
                        respond(
                            content = """{"success":true,"data":{"accessToken":"new-access","refreshToken":"new-refresh"}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            assertEquals(RefreshedSession("new-access", "new-refresh"), client.refreshSession("refresh").getOrThrow())
        }

    @Test
    fun `a session refresh without rotation has no new refresh token`() =
        runTest {
            val bodies =
                listOf(
                    """{"success":true,"data":{"accessToken":"new-access"}}""",
                    """{"success":true,"data":{"accessToken":"new-access","refreshToken":null}}""",
                )

            bodies.forEach { body ->
                val client =
                    createApiClient(
                        MockEngine {
                            respond(
                                content = body,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    )

                assertEquals(RefreshedSession("new-access", null), client.refreshSession("refresh").getOrThrow())
            }
        }

    @Test
    fun `a revoked device session keeps its error code on the refresh route`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine {
                        respond(
                            content = """{"error":{"code":"DEVICE_SESSION_REVOKED","message":"This device was removed"}}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val error = assertIs<CloudApiException>(client.refreshSession("refresh").exceptionOrNull())
            assertEquals("DEVICE_SESSION_REVOKED", error.errorCode)
            assertEquals(401, error.statusCode)
        }

    @Test
    fun `a revoked device session keeps its error code on a sync route`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine {
                        respond(
                            content =
                                """{"code":"DEVICE_SESSION_REVOKED","message":"This device was removed from the account",""" +
                                    """"details":{},"timestamp":"2026-01-01T00:00:00Z"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val error = assertIs<CloudApiException>(client.getContentChanges("token", since = 0).exceptionOrNull())
            assertEquals("DEVICE_SESSION_REVOKED", error.errorCode)
            assertEquals(401, error.statusCode)
        }

    @Test
    fun `auth error envelope maps into cloud api exception`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine {
                        respond(
                            content = """{"error":{"code":"INVALID_REFRESH_TOKEN","message":"Refresh token is invalid"}}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result = client.refreshAccessToken("bad-refresh-token")
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as CloudApiException
            assertEquals("INVALID_REFRESH_TOKEN", exception.errorCode)
            assertEquals(HttpStatusCode.Unauthorized.value, exception.statusCode)
        }

    @Test
    fun `sync error envelope maps into cloud api exception`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine {
                        respond(
                            content =
                                """
                                {
                                  "code": "UNAUTHORIZED",
                                  "message": "Missing or invalid Authorization header",
                                  "details": {},
                                  "timestamp": "2026-03-07T00:00:00Z"
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result =
                client.uploadContent(
                    accessToken = "bad",
                    content =
                        ContentUploadRequest(
                            id = "content-err",
                            type = "TEXT",
                            content = "test",
                            mediaUri = null,
                            createdAt = 1_700_000_000_000L,
                            lastUpdated = 1_700_000_000_000L,
                            deviceId = DeviceId("device-test"),
                        ),
                )
            assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as CloudApiException
            assertEquals("UNAUTHORIZED", exception.errorCode)
            assertEquals(HttpStatusCode.Unauthorized.value, exception.statusCode)
        }

    @Test
    fun `create-only and moment error codes reach callers with their own code and status`() =
        runTest {
            val answers =
                listOf(
                    Triple("CONTENT_EXISTS", HttpStatusCode.PreconditionFailed, "Content already exists"),
                    Triple("CONTENT_DELETED", HttpStatusCode.Gone, "Content was deleted"),
                    Triple("JOURNAL_EXISTS", HttpStatusCode.PreconditionFailed, "Journal already exists"),
                    Triple("JOURNAL_DELETED", HttpStatusCode.Gone, "Journal was deleted"),
                    Triple("MOMENT_EXISTS", HttpStatusCode.PreconditionFailed, "Moment already exists"),
                    Triple("MOMENT_DELETED", HttpStatusCode.Gone, "Moment was deleted"),
                    Triple("INVALID_MOMENT", HttpStatusCode.BadRequest, "Moment payload must be one LDSE2 encrypted value"),
                )
            answers.forEach { (code, status, serverMessage) ->
                val client =
                    createApiClient(
                        MockEngine {
                            respond(
                                content = """{"code":"$code","message":"$serverMessage","details":{"deletedAt":1,"serverVersion":2}}""",
                                status = status,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    )

                val result =
                    client.uploadContent(
                        accessToken = "token",
                        content =
                            ContentUploadRequest(
                                id = "content-err",
                                type = "TEXT",
                                content = "test",
                                mediaUri = null,
                                createdAt = 1_700_000_000_000L,
                                lastUpdated = 1_700_000_000_000L,
                                deviceId = DeviceId("device-test"),
                            ),
                    )

                val exception = result.exceptionOrNull() as CloudApiException
                assertEquals(code, exception.errorCode, "$code must not be reported as UNKNOWN_ERROR")
                assertEquals(status.value, exception.statusCode, code)
                assertEquals("Request failed (HTTP ${status.value})", exception.message, code)
            }
        }

    @Test
    fun `get account info uses auth me path`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/auth/me", request.url.toString())
                        assertEquals("Bearer jwt-abc", request.headers[HttpHeaders.Authorization])
                        respond(
                            content =
                                """
                                {
                                  "success": true,
                                  "data": {
                                    "account": {
                                      "id": "550e8400-e29b-41d4-a716-446655440001",
                                      "username": "tester",
                                      "displayName": "Tester",
                                      "did": "did:web:tester.logdate.app",
                                      "handle": "tester.logdate.app",
                                      "passkeyCredentialIds": ["cred-1"],
                                      "createdAt": "2026-03-05T00:00:00Z",
                                      "updatedAt": "2026-03-05T00:00:00Z"
                                    },
                                    "tokens": {"accessToken":"access","refreshToken":"refresh"}
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result = client.getAccountInfo("jwt-abc")
            assertTrue(result.isSuccess)
            assertEquals("tester", result.getOrThrow().username)
            assertEquals("did:web:tester.logdate.app", result.getOrThrow().did)
            assertEquals("tester.logdate.app", result.getOrThrow().handle)
            assertEquals(AccountStorageMode.LEGACY, result.getOrThrow().storageMode, "a server without storage modes means legacy")
        }

    @Test
    fun `get account info reads the account's storage mode`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine {
                        respond(
                            content =
                                """
                                {
                                  "success": true,
                                  "data": {
                                    "account": {
                                      "id": "550e8400-e29b-41d4-a716-446655440001",
                                      "username": "tester",
                                      "displayName": "Tester",
                                      "createdAt": "2026-03-05T00:00:00Z",
                                      "updatedAt": "2026-03-05T00:00:00Z",
                                      "storageMode": "personalSpaceV1"
                                    },
                                    "tokens": {"accessToken":"access","refreshToken":"refresh"}
                                  }
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, client.getAccountInfo("jwt-abc").getOrThrow().storageMode)
        }

    @Test
    fun `upload media uses multipart media collection path`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/media", request.url.toString())
                        assertEquals("Bearer jwt-abc", request.headers[HttpHeaders.Authorization])
                        assertTrue(request.body is MultiPartFormDataContent)
                        respond(
                            content =
                                """
                                {
                                  "contentId":"content-1",
                                  "mediaId":"media-1",
                                  "downloadUrl":"https://example.com/media-1",
                                  "uploadedAt": 1234
                                }
                                """.trimIndent(),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result =
                client.uploadMedia(
                    accessToken = "jwt-abc",
                    media =
                        MediaUploadRequest(
                            contentId = "content-1",
                            fileName = "photo.jpg",
                            mimeType = "image/jpeg",
                            sizeBytes = 3,
                            data = byteArrayOf(1, 2, 3),
                            deviceId = DeviceId("device-a"),
                        ),
                )

            assertTrue(result.isSuccess)
            assertEquals("media-1", result.getOrThrow().mediaId)
        }

    @Test
    fun `upload media reads the body from its source while the request is written`() =
        runTest {
            val size = 4 * 1024 * 1024
            var opens = 0
            var bytesRead = 0L
            var bytesSent = 0
            val client =
                createApiClient(
                    MockEngine { request ->
                        assertEquals(0L, bytesRead, "The body must not be read before the request is written")
                        bytesSent = request.body.toByteArray().size
                        respond(
                            content =
                                """
                                {"contentId":"content-1","mediaId":"media-1","downloadUrl":"https://example.com/media-1","uploadedAt":1}
                                """.trimIndent(),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )
            val upload =
                MediaUpload(
                    contentId = "content-1",
                    fileName = "recording.m4a",
                    mimeType = "audio/mp4",
                    sizeBytes = size.toLong(),
                ) {
                    opens++
                    PatternSource(size.toLong()) { read -> bytesRead += read }
                }

            client.uploadMedia("jwt-abc", upload).getOrThrow()

            assertEquals(1, opens)
            assertEquals(size.toLong(), bytesRead)
            assertTrue(bytesSent > size, "The multipart body should carry the whole file")
        }

    @Test
    fun `upload media reports an unreadable body as a media read error rather than a network error`() =
        runTest {
            val client =
                createApiClient(
                    MockEngine { request ->
                        request.body.toByteArray()
                        respond(content = "{}", status = HttpStatusCode.Created)
                    },
                )
            val upload =
                MediaUpload(
                    contentId = "content-1",
                    fileName = "recording.m4a",
                    mimeType = "audio/mp4",
                    sizeBytes = 16,
                ) { throw MediaReadException("recording.m4a is gone") }

            val result = client.uploadMedia("jwt-abc", upload)

            assertIs<MediaReadException>(result.exceptionOrNull())
        }

    /** Produces [size] bytes on demand without ever holding them all. */
    private class PatternSource(
        private var remaining: Long,
        private val onRead: (Long) -> Unit,
    ) : RawSource {
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (remaining == 0L) return -1
            val count = minOf(byteCount, remaining, 8192L)
            repeat(count.toInt()) { index -> sink.writeByte((index % 251).toByte()) }
            remaining -= count
            onRead(count)
            return count
        }

        override fun close() = Unit
    }

    private fun createApiClient(
        mockEngine: MockEngine,
        protocolFeatures: List<String> = emptyList(),
    ): LogDateCloudApiClient {
        val httpClient =
            HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(json)
                }
            }
        val config = MockConfigRepository(baseUrl)
        if (protocolFeatures.isNotEmpty()) {
            config.descriptor =
                app.logdate.shared.model.ServerDescriptor(
                    serverOrigin = baseUrl.removeSuffix("/api/v1"),
                    apiBaseUrl = baseUrl,
                    deploymentKind = app.logdate.shared.model.DeploymentKind.SELF_HOSTED,
                    displayName = "Test",
                    protocolFeatures = protocolFeatures,
                )
        }
        return LogDateCloudApiClient(config, httpClient)
    }

    private class MockConfigRepository(
        baseUrl: String,
    ) : LogDateConfigRepository {
        private val _backendUrl = MutableStateFlow(baseUrl.removeSuffix("/api/v1"))
        private val _apiVersion = MutableStateFlow("v1")
        private val _apiBaseUrl = MutableStateFlow(baseUrl)
        private val _localServerAddress = MutableStateFlow("localhost:8765")
        private val _serverDescriptor = MutableStateFlow<app.logdate.shared.model.ServerDescriptor?>(null)
        var descriptor: app.logdate.shared.model.ServerDescriptor?
            get() = _serverDescriptor.value
            set(value) {
                _serverDescriptor.value = value
            }

        override val backendUrl: StateFlow<String> = _backendUrl
        override val apiVersion: StateFlow<String> = _apiVersion
        override val apiBaseUrl: Flow<String> = _apiBaseUrl
        override val localServerAddress: StateFlow<String> = _localServerAddress
        override val serverDescriptor: StateFlow<app.logdate.shared.model.ServerDescriptor?> = _serverDescriptor

        override suspend fun updateBackendUrl(url: String) {
            _backendUrl.value = url
            _apiBaseUrl.value = "${url.trimEnd('/')}/api/${_apiVersion.value}"
        }

        override suspend fun updateApiVersion(version: String) {
            _apiVersion.value = version
            _apiBaseUrl.value = "${_backendUrl.value.trimEnd('/')}/api/$version"
        }

        override suspend fun updateLocalServerAddress(address: String) {
            _localServerAddress.value = address
        }

        override suspend fun updateServerDescriptor(descriptor: app.logdate.shared.model.ServerDescriptor?) {
            _serverDescriptor.value = descriptor
        }

        override suspend fun resetToDefaults() = Unit

        override fun getCurrentBackendUrl(): String = _backendUrl.value

        override fun getCurrentApiBaseUrl(): String = _apiBaseUrl.value

        override fun getCurrentServerDescriptor(): app.logdate.shared.model.ServerDescriptor? = _serverDescriptor.value
    }
}
