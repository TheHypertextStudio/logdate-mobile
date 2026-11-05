@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.routes.LogDateAccountAuth
import app.logdate.server.routes.support.backupUploadMultipartContent
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.BackupUploadResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdentityAdoptionBackupRoutesPostgresTest {
    @Test fun canonicalSessionCanUploadReadBackAndDeleteAnArchiveAfterAdoption() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = BackupFixture(this)
            fixture.serve {
                val uploaded =
                    client.post("/api/v1/backups") {
                        bearerAuth(fixture.fresh)
                        setBody(backupUploadMultipartContent("synthetic-device", "{}", fixture.bytes))
                    }
                assertEquals(HttpStatusCode.Created, uploaded.status)
                val result = Json.decodeFromString<BackupUploadResponse>(uploaded.bodyAsText())
                val id = UUID.fromString(result.id)
                val record = assertNotNull(fixture.backups.getBackup(fixture.owner, id))
                val download = client.get("/api/v1/backups/$id/binary") { bearerAuth(fixture.fresh) }
                assertEquals(HttpStatusCode.OK, download.status)
                assertEquals(fixture.bytes.toList(), download.body<ByteArray>().toList())
                val deleted = client.delete("/api/v1/backups/$id") { bearerAuth(fixture.fresh) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertNull(fixture.backups.getBackup(fixture.owner, id))
                assertNull(fixture.memory.getBlob(record.storagePath))
                auth.source.blueskyTransaction {
                    prepareStatement("SELECT identity_epoch,phase FROM object_upload_intents WHERE storage_path=?").use {
                        it.setString(1, record.storagePath)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1L, rows.getLong(1))
                            assertEquals("ATTACHED", rows.getString(2))
                        }
                    }
                }
            }
        }

    @Test fun retiredAndUnboundSessionsCannotUploadOrDeleteCurrentArchives() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = BackupFixture(this)
            // Metadata is enough to prove rejection precedes any provider access.
            val id = Uuid.random().toJavaUUID()
            fixture.backups.createBackup(
                fixture.owner,
                LogDateBackup(id, fixture.owner, "synthetic-device", "{}", "synthetic/current-archive", 1, 3),
                1,
            )
            fixture.serve {
                for (token in listOf(fixture.old, fixture.unbound)) {
                    val uploaded =
                        client.post("/api/v1/backups") {
                            bearerAuth(token)
                            setBody(backupUploadMultipartContent("synthetic-device", "{}", fixture.bytes))
                        }
                    assertEquals(HttpStatusCode.Unauthorized, uploaded.status)
                    val deleted = client.delete("/api/v1/backups/$id") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.Unauthorized, deleted.status)
                    assertNotNull(fixture.backups.getBackup(fixture.owner, id))
                }
                assertEquals(0, fixture.writes)
            }
        }

    @Test fun cutoverAfterAuthenticationCannotUpgradeAnOldUploadToTheNewEpoch() =
        withAdoptionCommit {
            val fixture = BackupFixture(this)
            var cutover = false
            fixture.afterCapture = {
                assertNotNull(commit())
                cutover = true
            }
            fixture.serve {
                val response =
                    client.post("/api/v1/backups") {
                        bearerAuth(fixture.old)
                        setBody(backupUploadMultipartContent("synthetic-device", "{}", fixture.bytes))
                    }
                assertTrue(cutover)
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(0, fixture.writes)
                assertTrue(fixture.backups.listBackups(fixture.owner).isEmpty())
            }
        }

    @Test fun aLinkedDeviceSessionCannotAcquireFullArchiveAuthority() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = BackupFixture(this)
            fixture.serve {
                val response =
                    client.post("/api/v1/backups") {
                        bearerAuth(fixture.device)
                        setBody(backupUploadMultipartContent("synthetic-device", "{}", fixture.bytes))
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals(0, fixture.writes)
            }
        }

    private class BackupFixture(
        val adoption: IdentityAdoptionCommitFixture,
    ) {
        val owner =
            adoption.operation.binding.accountId
                .toJavaUUID()
        val bytes = "Synthetic encrypted archive input".encodeToByteArray()
        val memory = InMemoryLogDateBlobStorage()
        val backups = PostgreSQLLogDateBackupRepository()
        var writes = 0
        var afterCapture: (suspend () -> Unit)? = null
        private val delegate = PostgreSQLAccountRepository()
        private val accounts =
            object : AccountRepository by delegate {
                override suspend fun findById(id: Uuid): Account? {
                    val captured = delegate.findById(id)
                    val after = afterCapture
                    afterCapture = null
                    if (after != null) after()
                    return captured
                }
            }
        private val tokens = JwtTokenService("synthetic-backup-adoption-test")
        private val account =
            adoption.operation.binding.accountId
                .toString()
        val fresh = tokens.generateAccessToken(account, adoption.operation.binding.targetDid)
        val old = tokens.generateAccessToken(account, adoption.operation.binding.sourceDid)
        val unbound = tokens.generateAccessToken(account)
        val device = tokens.generateDeviceAccessToken(account, adoption.operation.binding.targetDid, UUID.randomUUID().toString())
        private val auth =
            LogDateAccountAuth(
                AccessTokenVerifier(tokens, DeviceSessionGuard.Disabled),
                accounts,
                AtprotoIdentityService(
                    accounts,
                    SigningKeyService(InMemorySigningKeyRepository(), "synthetic-key"),
                    AtprotoIdentityConfig("example.test", "https://example.test"),
                ),
            )
        private val storage =
            object : LogDateBlobStorage by memory {
                override fun putBlobFile(request: LogDateBlobFileWriteRequest): String {
                    writes++
                    return memory.putBlobFile(request)
                }
            }

        fun serve(block: suspend ApplicationTestBuilder.() -> Unit) =
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
                                mediaBlobRepository =
                                    CompositeLogDateMediaBlobRepository(
                                        PostgreSQLLogDateMediaRepository(),
                                        PostgreSQLLogDateAtprotoBlobRepository(),
                                    ),
                                backupRepository = backups,
                                objectUploads = LegacyObjectUploads(PostgreSQLObjectUploads()),
                                backupAccountAuth = auth,
                            )
                        }
                    }
                }
                block()
            }
    }
}
