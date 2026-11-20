package app.logdate.client.sync.cloud

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BackupPartUploadTest {
    private val baseUrl = "https://api.logdate.example.com/api/v1"
    private val partSize = 16L * 1024 * 1024

    /** Just over the threshold, so the archive needs three 16 MiB parts with a short last one. */
    private val archive = Random(3).nextBytes((PARTED_BACKUP_THRESHOLD_BYTES + 777).toInt())

    @Test
    fun `a large archive goes up in parts that join back into the archive`() =
        withArchive { path ->
            val parts = mutableMapOf<Int, ByteArray>()
            var completeBody = ""
            val client =
                client { request ->
                    assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
                    val path = request.url.encodedPath
                    when {
                        request.method == HttpMethod.Post && path.endsWith("/backups/uploads") -> {
                            json(startBody(3))
                        }

                        request.method == HttpMethod.Put && "/parts/" in path -> {
                            parts[path.substringAfterLast('/').toInt()] = request.body.toByteArray()
                            respond("", HttpStatusCode.NoContent)
                        }

                        path.endsWith("/complete") -> {
                            completeBody = request.body.toByteArray().decodeToString()
                            json("""{"id":"backup-1","createdAt":1,"sizeBytes":${archive.size}}""", HttpStatusCode.Created)
                        }

                        else -> {
                            error("Unexpected ${request.method.value} $path")
                        }
                    }
                }

            val result = client.uploadBackupFile("access-token", BackupUploadFileRequest("device-1", "{}", path, archive.size.toLong()))

            assertEquals(archive.size.toLong(), result.getOrThrow().sizeBytes)
            assertEquals(listOf(0, 1, 2), parts.keys.sorted())
            assertContentEquals(
                archive,
                parts.keys
                    .sorted()
                    .map { parts.getValue(it) }
                    .fold(ByteArray(0)) { joined, part -> joined + part },
            )
            assertTrue(completeBody.contains("\"sizeBytes\":${archive.size}"))
            assertTrue(completeBody.contains("\"deviceId\":\"device-1\""))
        }

    @Test
    fun `a server without parted uploads gets the archive in one request`() =
        withArchive { path ->
            var multipart = false
            val client =
                client { request ->
                    when {
                        request.url.encodedPath.endsWith("/backups/uploads") -> {
                            respond("", HttpStatusCode.NotFound)
                        }

                        request.url.encodedPath.endsWith("/backups") -> {
                            multipart = request.body is MultiPartFormDataContent
                            json("""{"id":"backup-1","createdAt":1,"sizeBytes":${archive.size}}""", HttpStatusCode.Created)
                        }

                        else -> {
                            error("Unexpected ${request.url.encodedPath}")
                        }
                    }
                }

            client.uploadBackupFile("access-token", BackupUploadFileRequest("device-1", "{}", path, archive.size.toLong())).getOrThrow()

            assertTrue(multipart)
        }

    @Test
    fun `a part the server briefly refuses is sent again on its own`() =
        withArchive { path ->
            val attempts = mutableMapOf<Int, Int>()
            val client =
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        request.method == HttpMethod.Post && path.endsWith("/backups/uploads") -> {
                            json(startBody(3))
                        }

                        request.method == HttpMethod.Put -> {
                            val index = path.substringAfterLast('/').toInt()
                            val attempt = (attempts[index] ?: 0) + 1
                            attempts[index] = attempt
                            request.body.toByteArray()
                            if (index == 1 && attempt == 1) {
                                respond("""{"code":"SERVER_BUSY","message":"busy"}""", HttpStatusCode.ServiceUnavailable)
                            } else {
                                respond("", HttpStatusCode.NoContent)
                            }
                        }

                        path.endsWith("/complete") -> {
                            json("""{"id":"b","createdAt":1,"sizeBytes":${archive.size}}""", HttpStatusCode.Created)
                        }

                        else -> {
                            error("Unexpected $path")
                        }
                    }
                }

            client.uploadBackupFile("access-token", BackupUploadFileRequest("device-1", "{}", path, archive.size.toLong())).getOrThrow()

            assertEquals(mapOf(0 to 1, 1 to 2, 2 to 1), attempts.toMap())
        }

    @Test
    fun `an upload that fails is abandoned so its parts are deleted`() =
        withArchive { path ->
            var abandoned = false
            val client =
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        request.method == HttpMethod.Post && path.endsWith("/backups/uploads") -> {
                            json(startBody(3))
                        }

                        request.method == HttpMethod.Put -> {
                            request.body.toByteArray()
                            respond("""{"code":"VALIDATION_ERROR","message":"no"}""", HttpStatusCode.BadRequest)
                        }

                        request.method == HttpMethod.Delete && path.endsWith("/backups/uploads/upload-1") -> {
                            abandoned = true
                            respond("", HttpStatusCode.NoContent)
                        }

                        else -> {
                            error("Unexpected ${request.method.value} $path")
                        }
                    }
                }

            val result = client.uploadBackupFile("access-token", BackupUploadFileRequest("device-1", "{}", path, archive.size.toLong()))

            assertTrue(result.isFailure)
            assertEquals(400, (result.exceptionOrNull() as CloudApiException).statusCode)
            assertTrue(abandoned)
        }

    private fun startBody(partCount: Int) = """{"uploadId":"upload-1","partSizeBytes":$partSize,"partCount":$partCount}"""

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun withArchive(block: suspend (Path) -> Unit) =
        runTest {
            val path = Path(SystemTemporaryDirectory, "backup-parts-${Uuid.random()}.bin")
            try {
                SystemFileSystem.sink(path).buffered().use { it.write(archive) }
                block(path)
            } finally {
                SystemFileSystem.delete(path, mustExist = false)
            }
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): LogDateCloudApiClient {
        val httpClient = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return LogDateCloudApiClient(FixedConfig(baseUrl), httpClient)
    }

    private class FixedConfig(
        private val baseUrl: String,
    ) : LogDateConfigRepository {
        override val backendUrl: StateFlow<String> = MutableStateFlow(baseUrl.removeSuffix("/api/v1"))
        override val apiVersion: StateFlow<String> = MutableStateFlow("v1")
        override val apiBaseUrl: Flow<String> = MutableStateFlow(baseUrl)
        override val localServerAddress: StateFlow<String> = MutableStateFlow("localhost")
        override val serverDescriptor: StateFlow<ServerDescriptor?> = MutableStateFlow(null)

        override suspend fun updateBackendUrl(url: String) = Unit

        override suspend fun updateApiVersion(version: String) = Unit

        override suspend fun updateLocalServerAddress(address: String) = Unit

        override suspend fun updateServerDescriptor(descriptor: ServerDescriptor?) = Unit

        override suspend fun resetToDefaults() = Unit

        override fun getCurrentBackendUrl(): String = backendUrl.value

        override fun getCurrentApiBaseUrl(): String = baseUrl

        override fun getCurrentServerDescriptor(): ServerDescriptor? = null
    }
}
