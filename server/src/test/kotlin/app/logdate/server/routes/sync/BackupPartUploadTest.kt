package app.logdate.server.routes.sync

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.logdate.FilesystemLogDateBlobStorage
import app.logdate.server.logdate.asLogDateBackupRepository
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logdate.asLogDateMediaBlobRepository
import app.logdate.server.logdate.asLogDateMediaRepository
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.MediaAccessPolicy
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transfer.TransferLimits
import app.logdate.server.transfer.TransferSlots
import app.logdate.shared.model.sync.BackupPartUploadCompleteRequest
import app.logdate.shared.model.sync.BackupPartUploadStartRequest
import app.logdate.shared.model.sync.BackupPartUploadStartResponse
import app.logdate.shared.model.sync.BackupUploadResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.walk
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupPartUploadTest {
    private val root: Path = Files.createTempDirectory("backup-parts-test")
    private val tokenService = JwtTokenService("backup-part-secret")
    private val auth = "Bearer ${tokenService.generateAccessToken(UUID.randomUUID().toString())}"

    /** Small parts keep the test's memory low; the server's real parts are [BACKUP_PART_BYTES]. */
    private val partBytes = 64L * 1024

    /** Two full parts and a short third, so ordering and the last part's length both matter. */
    private val payload = Random(7).nextBytes((2 * partBytes + 1234).toInt())

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a backup sent in parts out of order is stored whole and its parts are deleted`() =
        withServer { client ->
            val upload = client.start(payload.size.toLong())
            assertEquals(partBytes, upload.partSizeBytes)
            assertEquals(3, upload.partCount)

            for (index in listOf(2, 0, 1)) {
                assertEquals(HttpStatusCode.NoContent, client.putPart(upload.uploadId, index).status)
            }
            // Sending a part again replaces it rather than failing.
            assertEquals(HttpStatusCode.NoContent, client.putPart(upload.uploadId, 1).status)

            val completed = client.complete(upload.uploadId, payload.size.toLong())
            assertEquals(HttpStatusCode.Created, completed.status, completed.bodyAsText())
            val backup = Json.decodeFromString<BackupUploadResponse>(completed.bodyAsText())
            assertEquals(payload.size.toLong(), backup.sizeBytes)

            val downloaded = client.get("/api/v1/backups/${backup.id}/binary") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertContentEquals(payload, downloaded.readRawBytes())
            assertTrue(stagedParts().isEmpty(), "staged parts remain: ${stagedParts()}")
        }

    @Test
    fun `completing names a missing part and succeeds once it arrives`() =
        withServer { client ->
            val upload = client.start(payload.size.toLong())
            client.putPart(upload.uploadId, 0)
            client.putPart(upload.uploadId, 2)

            val early = client.complete(upload.uploadId, payload.size.toLong())
            assertEquals(HttpStatusCode.Conflict, early.status)
            assertTrue(early.bodyAsText().contains("BACKUP_PART_MISSING"))
            assertTrue(early.bodyAsText().contains("Part 1 "))

            client.putPart(upload.uploadId, 1)
            assertEquals(HttpStatusCode.Created, client.complete(upload.uploadId, payload.size.toLong()).status)
        }

    @Test
    fun `a size that disagrees with the parts is refused instead of stored`() =
        withServer { client ->
            val upload = client.start(payload.size.toLong())
            (0 until upload.partCount).forEach { client.putPart(upload.uploadId, it) }

            val wrong = client.complete(upload.uploadId, payload.size.toLong() - 1)
            assertEquals(HttpStatusCode.Conflict, wrong.status)
        }

    @Test
    fun `parts outside the limits are refused`() =
        withServer { client ->
            val upload = client.start(payload.size.toLong())
            val oversized =
                client.put("/api/v1/backups/uploads/${upload.uploadId}/parts/0") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(ByteArray((partBytes + 1).toInt()))
                }
            assertEquals(HttpStatusCode.BadRequest, oversized.status)

            val badIndex =
                client.put("/api/v1/backups/uploads/${upload.uploadId}/parts/9999") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(byteArrayOf(1))
                }
            assertEquals(HttpStatusCode.BadRequest, badIndex.status)

            val badId =
                client.put("/api/v1/backups/uploads/not-a-uuid/parts/0") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(byteArrayOf(1))
                }
            assertEquals(HttpStatusCode.BadRequest, badId.status)

            val tooBig =
                client.post("/api/v1/backups/uploads") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(BackupPartUploadStartRequest(sizeBytes = MAX_PARTED_BACKUP_BYTES + 1)))
                }
            assertEquals(HttpStatusCode.BadRequest, tooBig.status)
        }

    @Test
    fun `parts need a signed-in account`() =
        withServer { client ->
            val anonymous =
                client.put("/api/v1/backups/uploads/${UUID.randomUUID()}/parts/0") { setBody(byteArrayOf(1)) }
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        }

    @Test
    fun `abandoning an upload deletes its parts`() =
        withServer { client ->
            val upload = client.start(payload.size.toLong())
            client.putPart(upload.uploadId, 0)
            client.putPart(upload.uploadId, 1)
            assertEquals(2, stagedParts().size)

            val abandoned = client.delete("/api/v1/backups/uploads/${upload.uploadId}") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.NoContent, abandoned.status)
            assertTrue(stagedParts().isEmpty())
        }

    private fun stagedParts(): List<String> =
        root
            .walk()
            .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("upload-") }
            .map { it.toString() }
            .toList()

    private fun withServer(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            val repository = InMemorySyncRepository()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        syncRoutes(
                            tokenService = tokenService,
                            mediaStorage = FilesystemLogDateBlobStorage(root),
                            metrics = SyncMetricsRegistry(),
                            mediaAccessPolicy = MediaAccessPolicy(useSignedUrls = false, signedUrlTtlHours = 1),
                            collectionsRepository = repository.asLogDateCollectionsRepository(),
                            mediaBlobRepository = repository.asLogDateMediaRepository().asLogDateMediaBlobRepository(),
                            backupRepository = repository.asLogDateBackupRepository(),
                            rateLimiter = null,
                            transfers = TransferSlots(TransferLimits(backupPartBytes = partBytes)),
                        )
                    }
                }
            }
            block(client)
        }

    private suspend fun HttpClient.start(sizeBytes: Long): BackupPartUploadStartResponse {
        val response =
            post("/api/v1/backups/uploads") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(BackupPartUploadStartRequest(sizeBytes)))
            }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.putPart(
        uploadId: String,
        index: Int,
    ): HttpResponse {
        val from = (index * partBytes).toInt().coerceAtMost(payload.size)
        val to = ((index + 1) * partBytes).toInt().coerceAtMost(payload.size)
        return put("/api/v1/backups/uploads/$uploadId/parts/$index") {
            header(HttpHeaders.Authorization, auth)
            setBody(payload.copyOfRange(from, to))
        }
    }

    private suspend fun HttpClient.complete(
        uploadId: String,
        sizeBytes: Long,
    ): HttpResponse =
        post("/api/v1/backups/uploads/$uploadId/complete") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(BackupPartUploadCompleteRequest(deviceId = "device-1", manifest = "{}", sizeBytes = sizeBytes)))
        }
}
