package app.logdate.server.database

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logdate.asLogDateMediaBlobRepository
import app.logdate.server.routes.support.backupUploadMultipartContent
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BackupUploadCommitPostgresTest {
    @Test fun lostMetadataAcknowledgementKeepsTheCommittedArchiveReadable() = exercise(afterCommit = true)

    @Test fun failureBeforeMetadataCommitCleansOnlyItsUnreferencedCandidate() = exercise(afterCommit = false)

    private fun exercise(afterCommit: Boolean) =
        withBlueskyOAuthDatabase {
            val owner = Uuid.random().toJavaUUID()
            createTestAccount(owner)
            val delegate = PostgreSQLLogDateBackupRepository()
            val repository =
                object : LogDateBackupRepository by delegate {
                    override fun createBackup(
                        userId: UUID,
                        backup: LogDateBackup,
                        identityEpoch: Long,
                    ): LogDateBackup {
                        if (afterCommit) delegate.createBackup(userId, backup, identityEpoch)
                        error("Synthetic metadata acknowledgement failure")
                    }
                }
            val objects = mutableMapOf<String, ByteArray>()
            var deletes = 0
            val storage = mockk<LogDateBlobStorage>()
            every { storage.putBlobFile(any()) } answers {
                val request = firstArg<LogDateBlobFileWriteRequest>()
                val path = "users/$owner/backups/${request.blobId}"
                objects[path] = Files.readAllBytes(request.path)
                path
            }
            every { storage.deleteBlob(any()) } answers {
                deletes++
                objects.remove(firstArg<String>()) != null
            }
            every { storage.deleteBlobIfExists(any()) } answers {
                deletes++
                objects.remove(firstArg<String>())
                Unit
            }
            every { storage.getBlobFile(any(), any(), any()) } answers {
                val bytes = objects[firstArg<String>()]
                if (bytes == null) {
                    false
                } else {
                    Files.write(secondArg(), bytes)
                    true
                }
            }
            every { storage.getSignedDownloadUrl(any(), any()) } returns "https://storage.test/backup"
            val tokens = JwtTokenService("synthetic-backup-commit-test")
            val token = tokens.generateAccessToken(owner.toString())
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        route("/api/v1") {
                            syncRoutes(
                                tokenService = tokens,
                                mediaStorage = storage,
                                metrics = SyncMetricsRegistry(),
                                collectionsRepository = InMemorySyncRepository().asLogDateCollectionsRepository(),
                                mediaBlobRepository = InMemoryLogDateMediaRepository().asLogDateMediaBlobRepository(),
                                backupRepository = repository,
                            )
                        }
                    }
                }
                val payload = "Synthetic private journal archive".encodeToByteArray()
                val response =
                    client.post("/api/v1/backups") {
                        bearerAuth(token)
                        setBody(backupUploadMultipartContent("device", "{}", payload))
                    }
                assertEquals(HttpStatusCode.InternalServerError, response.status)
                if (afterCommit) {
                    val backup = delegate.listBackups(owner).single()
                    assertEquals(1, objects.size)
                    assertEquals(0, deletes)
                    val download = client.get("/api/v1/backups/${backup.id}/binary") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, download.status)
                    assertContentEquals(payload, download.readRawBytes())
                } else {
                    assertEquals(emptyList(), delegate.listBackups(owner))
                    assertEquals(0, objects.size)
                    assertEquals(1, deletes)
                }
            }
        }
}
