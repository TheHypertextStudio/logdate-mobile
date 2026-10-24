package app.logdate.client.sync.cloud

import app.logdate.shared.config.LogDateConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BackupCloudApiClientTest {
    private val baseUrl = "https://api.logdate.example.com/api/v1"

    @Test
    fun `file upload sends authenticated multipart without reading archive into model`() =
        runTest {
            val path = Path(SystemTemporaryDirectory, "backup-upload-${Uuid.random()}.bin")
            try {
                SystemFileSystem.sink(path).buffered().use { it.write(byteArrayOf(1, 2, 3)) }
                val client =
                    createClient(
                        MockEngine { request ->
                            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                            assertTrue(request.body is MultiPartFormDataContent)
                            respond(
                                """{"id":"backup-1","createdAt":1234,"sizeBytes":3}""",
                                HttpStatusCode.Created,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    )
                val result = client.uploadBackupFile("access-token", BackupUploadFileRequest("device-1", "{}", path, 3)).getOrThrow()
                assertEquals(3, result.sizeBytes)
            } finally {
                SystemFileSystem.delete(path)
            }
        }

    @Test
    fun `file download streams authenticated binary into destination`() =
        runTest {
            val path = Path(SystemTemporaryDirectory, "backup-download-${Uuid.random()}.bin")
            try {
                var requests = 0
                val client =
                    createClient(
                        MockEngine { request ->
                            requests++
                            assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                            if (requests ==
                                1
                            ) {
                                respond(
                                    """{"id":"backup-1","deviceId":"device-1","manifest":"{}","createdAt":1234,"sizeBytes":3,"downloadUrl":"/api/v1/backups/backup-1/binary"}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            } else {
                                respond(
                                    byteArrayOf(4, 5, 6),
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                                )
                            }
                        },
                    )
                val result = client.downloadBackupToFile("access-token", "backup-1", path).getOrThrow()
                assertEquals("device-1", result.deviceId)
                assertContentEquals(byteArrayOf(4, 5, 6), SystemFileSystem.source(path).buffered().use { it.readByteArray() })
            } finally {
                if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
            }
        }

    @Test
    fun `cancelled file download removes incomplete destination`() =
        runTest {
            val path = Path(SystemTemporaryDirectory, "backup-cancelled-${Uuid.random()}.bin")
            var requests = 0
            val client =
                createClient(
                    MockEngine {
                        requests++
                        if (requests ==
                            1
                        ) {
                            respond(
                                """{"id":"backup-1","deviceId":"device-1","manifest":"{}","createdAt":1234,"sizeBytes":3,"downloadUrl":"/api/v1/backups/backup-1/binary"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } else {
                            throw CancellationException("download cancelled")
                        }
                    },
                )
            assertFailsWith<CancellationException> { client.downloadBackupToFile("access-token", "backup-1", path) }
            assertTrue(!SystemFileSystem.exists(path))
        }

    @Test
    fun `upload backup sends authenticated multipart payload`() =
        runTest {
            val client =
                createClient(
                    MockEngine { request ->
                        assertEquals("$baseUrl/backups", request.url.toString())
                        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                        assertTrue(request.body is MultiPartFormDataContent)
                        respond(
                            """{"id":"backup-1","createdAt":1234,"sizeBytes":3}""",
                            HttpStatusCode.Created,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )

            val result =
                client.uploadBackup(
                    "access-token",
                    BackupUploadRequest("device-1", "{}", byteArrayOf(1, 2, 3)),
                )

            assertEquals(BackupUploadResponse("backup-1", 1234, 3), result.getOrThrow())
        }

    @Test
    fun `download backup fetches metadata then authenticated binary`() =
        runTest {
            var requestCount = 0
            val client =
                createClient(
                    MockEngine { request ->
                        requestCount += 1
                        when (requestCount) {
                            1 -> {
                                assertEquals("$baseUrl/backups/backup-1", request.url.toString())
                                assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                                respond(
                                    """{"id":"backup-1","deviceId":"device-1","manifest":"{\"version\":1}","createdAt":1234,"sizeBytes":3,"downloadUrl":"/api/v1/backups/backup-1/binary"}""",
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                                )
                            }

                            else -> {
                                assertEquals("$baseUrl/backups/backup-1/binary", request.url.toString())
                                respond(
                                    byteArrayOf(4, 5, 6),
                                    HttpStatusCode.OK,
                                    headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()),
                                )
                            }
                        }
                    },
                )

            val result = client.downloadBackup("access-token", "backup-1").getOrThrow()
            assertEquals("device-1", result.metadata.deviceId)
            assertContentEquals(byteArrayOf(4, 5, 6), result.data)
        }

    private fun createClient(engine: MockEngine): LogDateCloudApiClient {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return LogDateCloudApiClient(TestConfigRepository(baseUrl), httpClient)
    }

    private class TestConfigRepository(
        baseUrl: String,
    ) : LogDateConfigRepository {
        private val backend = MutableStateFlow(baseUrl.removeSuffix("/api/v1"))
        private val version = MutableStateFlow("v1")
        private val api = MutableStateFlow(baseUrl)
        private val local = MutableStateFlow("localhost")
        private val descriptor = MutableStateFlow<app.logdate.shared.model.ServerDescriptor?>(null)

        override val backendUrl: StateFlow<String> = backend
        override val apiVersion: StateFlow<String> = version
        override val apiBaseUrl: Flow<String> = api
        override val localServerAddress: StateFlow<String> = local
        override val serverDescriptor: StateFlow<app.logdate.shared.model.ServerDescriptor?> = descriptor

        override suspend fun updateBackendUrl(url: String) {
            backend.value = url
            api.value = "${url.trimEnd('/')}/api/${version.value}"
        }

        override suspend fun updateApiVersion(version: String) {
            this.version.value = version
            api.value = "${backend.value.trimEnd('/')}/api/$version"
        }

        override suspend fun updateLocalServerAddress(address: String) {
            local.value = address
        }

        override suspend fun updateServerDescriptor(descriptor: app.logdate.shared.model.ServerDescriptor?) {
            this.descriptor.value = descriptor
        }

        override suspend fun resetToDefaults() = Unit

        override fun getCurrentBackendUrl(): String = backend.value

        override fun getCurrentApiBaseUrl(): String = api.value

        override fun getCurrentServerDescriptor(): app.logdate.shared.model.ServerDescriptor? = descriptor.value
    }
}
