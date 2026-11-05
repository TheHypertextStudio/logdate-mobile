package app.logdate.server.routes.sync

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.logdate.FilesystemLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.asLogDateBackupRepository
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logdate.asLogDateMediaBlobRepository
import app.logdate.server.routes.support.mediaMultipartWithFields
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.MediaUploadResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SyncMediaRetrySafetyTest {
    @Test
    fun `durable receipt lookup survives lost acknowledgement and is isolated by account`() =
        testApplication {
            val fixture = installMediaFixture()
            val intent = "b88ce7fe-902c-4c01-862d-45238bf30928"
            val missing = client.get("/api/v1/media/receipts/$intent") { header(HttpHeaders.Authorization, fixture.auth) }
            assertEquals(HttpStatusCode.OK, missing.status)
            assertEquals("{\"receipt\":null}", missing.readRawBytes().decodeToString())
            val first = upload(fixture, byteArrayOf(1, 2, 3), intent)
            assertEquals(HttpStatusCode.Created, first.status)
            val accepted = Json.decodeFromString<MediaUploadResponse>(first.readRawBytes().decodeToString())
            val receipt = client.get("/api/v1/media/receipts/$intent") { header(HttpHeaders.Authorization, fixture.auth) }
            assertEquals(HttpStatusCode.OK, receipt.status)
            kotlin.test.assertTrue(receipt.readRawBytes().decodeToString().contains(accepted.mediaId))
            val other = fixture.tokenService.generateAccessToken("00000000-0000-0000-0000-000000000222")
            val foreign = client.get("/api/v1/media/receipts/$intent") { header(HttpHeaders.Authorization, "Bearer $other") }
            assertEquals(HttpStatusCode.OK, foreign.status)
            assertEquals("{\"receipt\":null}", foreign.readRawBytes().decodeToString())
            // Replayed intent never replaces the committed version, even with a different body.
            assertEquals(HttpStatusCode.Created, upload(fixture, byteArrayOf(9, 9, 9), intent).status)
            assertContentEquals(byteArrayOf(1, 2, 3), download(fixture, accepted.mediaId).readRawBytes())
            val next = upload(fixture, byteArrayOf(4, 5, 6), "2b005707-657d-4bc8-a16c-ea0eca509cf0")
            val newer = Json.decodeFromString<MediaUploadResponse>(next.readRawBytes().decodeToString())
            kotlin.test.assertNotEquals(accepted.mediaId, newer.mediaId)
            assertContentEquals(byteArrayOf(1, 2, 3), download(fixture, accepted.mediaId).readRawBytes())
        }

    @Test
    fun `failed retry preserves the previously committed attachment bytes`() =
        testApplication {
            val fixture = installMediaFixture()
            val first = upload(fixture, byteArrayOf(1, 2, 3))
            assertEquals(HttpStatusCode.Created, first.status)
            val mediaId = Json.decodeFromString<MediaUploadResponse>(first.readRawBytes().decodeToString()).mediaId

            fixture.repository.failure = MetadataFailure.BEFORE_COMMIT
            assertEquals(HttpStatusCode.InternalServerError, upload(fixture, byteArrayOf(4, 5, 6)).status)

            val downloaded = download(fixture, mediaId)
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertContentEquals(byteArrayOf(1, 2, 3), downloaded.readRawBytes())
        }

    @Test
    fun `lost metadata acknowledgement leaves the committed attachment readable`() =
        testApplication {
            val fixture = installMediaFixture()
            fixture.repository.failure = MetadataFailure.AFTER_COMMIT
            assertEquals(HttpStatusCode.InternalServerError, upload(fixture, byteArrayOf(7, 8, 9)).status)

            val committed = fixture.repository.listMedia(fixture.userId).single()
            val downloaded = download(fixture, committed.mediaId)
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertContentEquals(byteArrayOf(7, 8, 9), downloaded.readRawBytes())

            fixture.repository.failure = null
            val retry = upload(fixture, byteArrayOf(7, 8, 9))
            assertEquals(HttpStatusCode.Created, retry.status)
            assertEquals(
                committed.mediaId,
                Json.decodeFromString<MediaUploadResponse>(retry.readRawBytes().decodeToString()).mediaId,
            )
            assertEquals(1, fixture.repository.listMedia(fixture.userId).size)
        }

    @Test
    fun `successful replacement retains one logical record and retires its previous blob`() =
        testApplication {
            val fixture = installMediaFixture()
            assertEquals(HttpStatusCode.Created, upload(fixture, byteArrayOf(1, 2, 3)).status)
            assertEquals(HttpStatusCode.Created, upload(fixture, byteArrayOf(4, 5, 6)).status)

            val committed = fixture.repository.listMedia(fixture.userId).single()
            val downloaded = download(fixture, committed.mediaId)
            assertEquals(HttpStatusCode.OK, downloaded.status)
            assertContentEquals(byteArrayOf(4, 5, 6), downloaded.readRawBytes())
            assertEquals(1, fixture.storage.blobs.size)
        }

    @Test
    fun `replacement remains compatible with a legacy filesystem attachment`() =
        testApplication {
            val root = Files.createTempDirectory("logdate-media-retry-")
            try {
                val storage = FilesystemLogDateBlobStorage(root)
                val fixture = MediaFixture(storage)
                val mediaId = "6030e5b6-4c36-383a-a7a8-21afefad5e72"
                val legacyPath =
                    storage.putBlob(
                        LogDateBlobWriteRequest(
                            ownerId = fixture.userId,
                            namespace = LogDateBlobNamespace.MEDIA,
                            blobId = mediaId,
                            contentType = "image/jpeg",
                            bytes = byteArrayOf(1, 2, 3),
                        ),
                    )
                fixture.repository.upsertMedia(
                    fixture.userId,
                    LogDateMedia(
                        mediaId = mediaId,
                        contentId = "content-1",
                        userId = fixture.userId,
                        fileName = "photo.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 3,
                        data = byteArrayOf(),
                        storagePath = legacyPath,
                        createdAt = 1,
                        version = 1,
                        deviceId = DeviceId("device-1"),
                    ),
                )
                installMediaFixture(fixture)

                val replaced = upload(fixture, byteArrayOf(4, 5, 6))
                assertEquals(HttpStatusCode.Created, replaced.status)
                val downloaded = download(fixture, mediaId)
                assertEquals(HttpStatusCode.OK, downloaded.status)
                assertContentEquals(byteArrayOf(4, 5, 6), downloaded.readRawBytes())
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun ApplicationTestBuilder.installMediaFixture(fixture: MediaFixture = MediaFixture()): MediaFixture {
        val syncRepository = InMemorySyncRepository()
        application {
            install(ContentNegotiation) { json() }
            routing {
                route("/api/v1") {
                    syncRoutes(
                        tokenService = fixture.tokenService,
                        mediaStorage = fixture.blobStorage,
                        metrics = SyncMetricsRegistry(),
                        collectionsRepository = syncRepository.asLogDateCollectionsRepository(),
                        mediaBlobRepository = fixture.repository,
                        backupRepository = syncRepository.asLogDateBackupRepository(),
                    )
                }
            }
        }
        return fixture
    }

    private suspend fun ApplicationTestBuilder.upload(
        fixture: MediaFixture,
        bytes: ByteArray,
        intent: String? = null,
    ): HttpResponse =
        client.post("/api/v1/media") {
            header(HttpHeaders.Authorization, fixture.auth)
            intent?.let { header("X-LogDate-Upload-Intent", it) }
            setBody(
                mediaMultipartWithFields(
                    includeContentId = true,
                    includeFileName = true,
                    includeMimeType = true,
                    includeSizeBytes = true,
                    includeDeviceId = true,
                    includeData = true,
                    sizeBytes = bytes.size.toLong(),
                    payload = bytes,
                ),
            )
        }

    private suspend fun ApplicationTestBuilder.download(
        fixture: MediaFixture,
        mediaId: String,
    ): HttpResponse = client.get("/api/v1/media/$mediaId/binary") { header(HttpHeaders.Authorization, fixture.auth) }
}

private class MediaFixture(
    val blobStorage: LogDateBlobStorage = RetryBlobStorage(),
) {
    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000111")
    val tokenService = JwtTokenService("media-retry-safety-test")
    val auth = "Bearer ${tokenService.generateAccessToken(userId.toString())}"
    val storage get() = blobStorage as RetryBlobStorage
    val repository = FailingMediaRepository(InMemoryLogDateMediaRepository().asLogDateMediaBlobRepository())
}

private enum class MetadataFailure { BEFORE_COMMIT, AFTER_COMMIT }

private class FailingMediaRepository(
    private val delegate: LogDateMediaBlobRepository,
) : LogDateMediaBlobRepository by delegate {
    var failure: MetadataFailure? = null

    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
        identityEpoch: Long,
    ): LogDateMedia {
        check(failure != MetadataFailure.BEFORE_COMMIT) { "Metadata write failed before commit" }
        val stored = delegate.upsertMedia(userId, media, identityEpoch)
        check(failure != MetadataFailure.AFTER_COMMIT) { "Metadata acknowledgement lost after commit" }
        return stored
    }
}

private class RetryBlobStorage : LogDateBlobStorage {
    val blobs = mutableMapOf<String, ByteArray>()

    override fun putBlob(request: LogDateBlobWriteRequest): String {
        val path = "users/${request.ownerId}/media/${request.blobId}/${request.fileName}"
        blobs[path] = request.bytes.copyOf()
        return path
    }

    override fun getBlob(storagePath: String): ByteArray? = blobs[storagePath]?.copyOf()

    override fun deleteBlob(storagePath: String): Boolean = blobs.remove(storagePath) != null

    override fun openBlob(storagePath: String): LogDateBlobStream? = error("Streamed reads are not used by this route")

    override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter = error("Streamed uploads are not used by this route")

    override fun getSignedDownloadUrl(
        storagePath: String,
        expirationHours: Long,
    ): String = "https://storage.example/$storagePath"

    override fun putBlobFile(request: LogDateBlobFileWriteRequest): String = error("File upload is not used by this route")

    override fun getBlobFile(
        storagePath: String,
        destination: Path,
        checkActive: () -> Unit,
    ): Boolean = error("File download is not used by this route")
}
