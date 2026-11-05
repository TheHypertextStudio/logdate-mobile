@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.ObjectUploadKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class ObjectUploadRecoveryRacePostgresTest {
    @Test fun anAttachingWriterWinsAgainstAnAlreadySelectedRecoveryCandidate() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val owner = operation.binding.accountId
            val request =
                LogDateBlobWriteRequest(
                    owner.toJavaUUID(),
                    LogDateBlobNamespace.BACKUP,
                    Uuid.random().toString(),
                    "synthetic",
                    "application/octet-stream",
                    byteArrayOf(1, 2, 3),
                )
            val upload = uploads.begin(IdentityWriteExpectation(owner, 0), null, storage.plannedPath(request), ObjectUploadKind.BACKUP)
            storage.putBlob(request)
            uploads.stored(upload)
            clock.time += 3.minutes
            val candidate = uploads.pendingCleanup().single()
            val entered = CompletableDeferred<Int>()
            val release = CompletableDeferred<Unit>()
            val id = Uuid.random().toJavaUUID()
            val backups = PostgreSQLLogDateBackupRepository()
            coroutineScope {
                val writer =
                    async(Dispatchers.IO) {
                        uploads.attach(upload, {
                            backups.createBackup(
                                owner.toJavaUUID(),
                                LogDateBackup(id, owner.toJavaUUID(), "synthetic", "{}", upload.path, 1, 3),
                                0,
                            )
                            val pid =
                                accountWriteConnection().createStatement().use {
                                    it.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                        check(rows.next())
                                        rows.getInt(1)
                                    }
                                }
                            entered.complete(pid)
                            runBlocking { release.await() }
                            upload.path
                        })
                    }
                try {
                    val pid = withTimeout(5000) { entered.await() }
                    val recovery =
                        async(Dispatchers.IO) {
                            uploads.recover(candidate) { error("The attached canonical file cannot be deleted") }
                        }
                    withTimeout(10000) {
                        while (true) {
                            val blocked =
                                source.connection.use { observer ->
                                    observer
                                        .prepareStatement(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                                        ).use {
                                            it.setInt(1, pid)
                                            it.executeQuery().use { rows ->
                                                check(rows.next())
                                                rows.getBoolean(1)
                                            }
                                        }
                                }
                            if (blocked) break
                            delay(10)
                        }
                    }
                    release.complete(Unit)
                    assertEquals(upload.path, writer.await())
                    assertFalse(recovery.await())
                } finally {
                    release.complete(Unit)
                }
            }
            assertNotNull(backups.getBackup(owner.toJavaUUID(), id))
            assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(upload.path)?.toList())
            assertTrue(uploads.pendingCleanup().isEmpty())
        }
}
