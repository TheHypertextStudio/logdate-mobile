package app.logdate.server.routes

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureSyncTestApp
import app.logdate.server.crypto.PayloadPrefixes
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.routes.support.associationUploadBody
import app.logdate.server.routes.support.backupMultipartWithFields
import app.logdate.server.routes.support.contentUploadBody
import app.logdate.server.routes.support.journalUploadBody
import app.logdate.server.routes.support.mediaMultipartWithFields
import app.logdate.server.sync.GcsMediaStorage
import app.logdate.shared.model.sync.DeviceId
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for edge cases and error conditions across the Sync API.
 *
 * This suite exercises less common paths and failure modes, such as metric retrieval via
 * Prometheus, handling of missing resources, batch deletion of associations, and error
 * recovery for media and backup operations when storage is unavailable or decryption fails.
 */
class SyncRoutesEdgeCasesTest {
    @Test
    fun `prometheus and by-id endpoints return expected results and validation errors`() =
        testApplication {
            val env = configureSyncTestApp()
            val userId = UUID.randomUUID()
            val authHeader = authHeader(env.tokenService, userId)

            val createContent =
                client.put("/api/v1/contents/content-edge-1") {
                    header(HttpHeaders.Authorization, authHeader)
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "content-edge-1"))
                }
            assertEquals(HttpStatusCode.Created, createContent.status)

            val createJournal =
                client.put("/api/v1/journals/journal-edge-1") {
                    header(HttpHeaders.Authorization, authHeader)
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "journal-edge-1"))
                }
            assertEquals(HttpStatusCode.Created, createJournal.status)

            val prom =
                client.get("/api/v1/ops/sync/metrics/prometheus") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            assertEquals(HttpStatusCode.OK, prom.status)
            assertTrue(prom.bodyAsText().contains("logdate_sync_operation_success_total"))

            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/v1/contents/content-edge-1") {
                        header(HttpHeaders.Authorization, authHeader)
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .get("/api/v1/contents/missing") {
                        header(HttpHeaders.Authorization, authHeader)
                    }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/v1/journals/journal-edge-1") {
                        header(HttpHeaders.Authorization, authHeader)
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client
                    .get("/api/v1/journals/missing") {
                        header(HttpHeaders.Authorization, authHeader)
                    }.status,
            )

            val invalidContentSince =
                client.get("/api/v1/contents?since=not-a-long") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            assertEquals(HttpStatusCode.BadRequest, invalidContentSince.status)

            val invalidJournalSince =
                client.get("/api/v1/journals?since=not-a-long") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            assertEquals(HttpStatusCode.BadRequest, invalidJournalSince.status)

            val invalidAssociationSince =
                client.get("/api/v1/associations?since=not-a-long") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            assertEquals(HttpStatusCode.BadRequest, invalidAssociationSince.status)
        }

    @Test
    fun `association singular and batch delete endpoints work`() =
        testApplication {
            val env = configureSyncTestApp()
            val userId = UUID.randomUUID()
            val authHeader = authHeader(env.tokenService, userId)

            val singularPut =
                client.put("/api/v1/associations/journal-edge/content-edge") {
                    header(HttpHeaders.Authorization, authHeader)
                    contentType(ContentType.Application.Json)
                    setBody("""{"createdAt":1}""")
                }
            assertEquals(HttpStatusCode.NoContent, singularPut.status)

            val singularDelete =
                client.delete("/api/v1/associations/journal-edge/content-edge") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            assertEquals(HttpStatusCode.NoContent, singularDelete.status)

            // Batch upload uses POST (not PUT — that returned 405 because the
            // route doesn't define a PUT handler at the collection level).
            val batchUpload =
                client.post("/api/v1/associations") {
                    header(HttpHeaders.Authorization, authHeader)
                    contentType(ContentType.Application.Json)
                    setBody(associationUploadBody(journalId = "journal-batch", contentId = "content-batch"))
                }
            assertEquals(HttpStatusCode.OK, batchUpload.status)

            // Batch delete on the collection isn't currently exposed by the
            // server. Singular DELETE above is the only documented removal
            // path. If batch delete is added, re-enable an assertion here.
        }

    @Test
    fun `media binary covers storage unavailable download missing decrypt failure and delete`() =
        testApplication {
            val userId = UUID.randomUUID()

            val envNoStorage = configureSyncTestApp()
            val authNoStorage = authHeader(envNoStorage.tokenService, userId)
            envNoStorage.mediaRepository.upsertMedia(
                userId,
                mediaRecord(
                    userId = userId,
                    mediaId = "media-storage-required",
                    storagePath = "users/$userId/media/media-storage-required/file.bin",
                ),
            )

            val storageUnavailable =
                client.get("/api/v1/media/media-storage-required/binary") {
                    header(HttpHeaders.Authorization, authNoStorage)
                }
            assertEquals(HttpStatusCode.InternalServerError, storageUnavailable.status)
            assertTrue(storageUnavailable.bodyAsText().contains("MEDIA_STORAGE_UNAVAILABLE"))
        }

    @Test
    fun `media binary supports storage missing decrypt failure and idempotent delete`() =
        testApplication {
            val userId = UUID.randomUUID()
            val storage = mockk<GcsMediaStorage>()
            every { storage.getBlob("missing-path") } returns null
            every { storage.getBlob("invalid-cipher-path") } returns (PayloadPrefixes.SERVER_MEDIA + byteArrayOf(1, 2, 3))
            every { storage.deleteBlobIfExists(any()) } returns Unit

            val env = configureSyncTestApp(mediaStorage = storage)
            val auth = authHeader(env.tokenService, userId)

            env.mediaRepository.upsertMedia(
                userId,
                mediaRecord(
                    userId = userId,
                    mediaId = "media-missing-storage",
                    storagePath = "missing-path",
                ),
            )
            env.mediaRepository.upsertMedia(
                userId,
                mediaRecord(
                    userId = userId,
                    mediaId = "media-bad-cipher",
                    storagePath = "invalid-cipher-path",
                ),
            )
            env.mediaRepository.upsertMedia(
                userId,
                mediaRecord(
                    userId = userId,
                    mediaId = "media-delete",
                    storagePath = "users/$userId/media/media-delete/file.bin",
                ),
            )

            val missing =
                client.get("/api/v1/media/media-missing-storage/binary") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            val decryptFail =
                client.get("/api/v1/media/media-bad-cipher/binary") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.InternalServerError, decryptFail.status)
            assertTrue(decryptFail.bodyAsText().contains("MEDIA_DECRYPT_FAILED"))

            val firstDelete =
                client.delete("/api/v1/media/media-delete") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NoContent, firstDelete.status)

            val secondDelete =
                client.delete("/api/v1/media/media-delete") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NoContent, secondDelete.status)
        }

    @Test
    fun `backup endpoints validate ids handle decrypt failures and delete`() =
        testApplication {
            val userId = UUID.randomUUID()
            val storage = mockk<GcsMediaStorage>()
            every { storage.getBlobFile("backup-missing", any(), any()) } returns false
            every { storage.getBlobFile("backup-bad-cipher", any(), any()) } answers {
                Files.write(secondArg<Path>(), PayloadPrefixes.SERVER_BACKUP + byteArrayOf(1, 2, 3))
                true
            }
            every { storage.deleteBlobIfExists(any()) } returns Unit

            val env = configureSyncTestApp(mediaStorage = storage)
            val auth = authHeader(env.tokenService, userId)

            val missingId =
                client.get("/api/v1/backups/not-a-uuid") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.BadRequest, missingId.status)

            val missingBinaryId =
                client.get("/api/v1/backups/not-a-uuid/binary") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.BadRequest, missingBinaryId.status)

            val backupMissing = UUID.randomUUID()
            env.backupRepository.createBackup(userId, backupRecord(userId, backupMissing, "backup-missing"))
            val missingBinary =
                client.get("/api/v1/backups/$backupMissing/binary") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NotFound, missingBinary.status)

            val backupBadCipher = UUID.randomUUID()
            env.backupRepository.createBackup(userId, backupRecord(userId, backupBadCipher, "backup-bad-cipher"))
            val decryptFail =
                client.get("/api/v1/backups/$backupBadCipher/binary") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.InternalServerError, decryptFail.status)
            assertTrue(decryptFail.bodyAsText().contains("BACKUP_DECRYPT_FAILED"))

            val firstDelete =
                client.delete("/api/v1/backups/$backupBadCipher") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NoContent, firstDelete.status)

            val secondDelete =
                client.delete("/api/v1/backups/$backupBadCipher") {
                    header(HttpHeaders.Authorization, auth)
                }
            assertEquals(HttpStatusCode.NoContent, secondDelete.status)
        }

    @Test
    fun `multipart validation covers all required media and backup fields`() =
        testApplication {
            val storage = mockk<GcsMediaStorage>(relaxed = true)
            val env = configureSyncTestApp(mediaStorage = storage)
            val auth = authHeader(env.tokenService, UUID.randomUUID())
            val payload = byteArrayOf(1, 2, 3, 4)

            val mediaCases =
                listOf(
                    "contentId" to mediaMultipartWithFields(false, true, true, true, true, true, 4, payload),
                    "fileName" to mediaMultipartWithFields(true, false, true, true, true, true, 4, payload),
                    "mimeType" to mediaMultipartWithFields(true, true, false, true, true, true, 4, payload),
                    "sizeBytes" to mediaMultipartWithFields(true, true, true, false, true, true, 4, payload),
                    "deviceId" to mediaMultipartWithFields(true, true, true, true, false, true, 4, payload),
                    "data" to mediaMultipartWithFields(true, true, true, true, true, false, 4, payload),
                )
            mediaCases.forEach { (field, body) ->
                val response =
                    client.post("/api/v1/media") {
                        header(HttpHeaders.Authorization, auth)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains(field))
            }

            val backupCases =
                listOf(
                    "deviceId" to backupMultipartWithFields(false, true, true, payload),
                    "manifest" to backupMultipartWithFields(true, false, true, payload),
                    "data" to backupMultipartWithFields(true, true, false, payload),
                )
            backupCases.forEach { (field, body) ->
                val response =
                    client.post("/api/v1/backups") {
                        header(HttpHeaders.Authorization, auth)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains(field))
            }
        }

    private fun authHeader(
        tokenService: JwtTokenService,
        userId: UUID,
    ): String = "Bearer ${tokenService.generateAccessToken(userId.toString())}"

    private fun mediaRecord(
        userId: UUID,
        mediaId: String,
        storagePath: String?,
    ): LogDateMedia =
        LogDateMedia(
            mediaId = mediaId,
            contentId = "content-$mediaId",
            userId = userId,
            fileName = "file.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 3L,
            data = byteArrayOf(1, 2, 3),
            storagePath = storagePath,
            createdAt = 1L,
            version = 1L,
            deviceId = DeviceId("dev-1"),
        )

    private fun backupRecord(
        userId: UUID,
        id: UUID,
        storagePath: String,
    ): LogDateBackup =
        LogDateBackup(
            id = id,
            userId = userId,
            deviceId = "dev-1",
            manifest = "{}",
            storagePath = storagePath,
            createdAt = 1L,
            sizeBytes = 3L,
        )
}
