@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploads
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class LegacyObjectUploadAdmissionPostgresTest {
    @Test fun bothNamespacesPersistIntentBeforeProviderIoAndExcludeTheAdoptionFence() =
        eachNamespace {
            val operation = begin()
            onWrite = {
                assertNoIdleTransaction()
                assertEquals(listOf("WRITING"), phases())
                assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
            }
            upload()
            assertEquals(listOf("ATTACHED"), phases())
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
        }

    @Test fun aCurrentFenceRefusesBeforeEitherNamespaceWrites() =
        eachNamespace {
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(begin()))
            assertFailsWith<IdentityWriteRefusedException> { upload() }
            assertEquals(0, writes)
        }

    @Test fun lateWritesForADeletedOwnerRetireTheirExactPathWithoutRegisteringMetadata() =
        eachNamespace {
            onWrite = { assertTrue(runBlocking { accounts.deleteAccount(owner.id) }) }
            assertFailsWith<IdentityWriteRefusedException> { upload() }
            assertEquals(listOf("RETIRED"), phases())
            assertEquals(emptyList(), storage.listOwnerBlobPaths(owner.id.toJavaUUID()))
        }

    @Test fun providerCancellationRetainsUnknownOutcomeInsteadOfDroppingItsBarrier() =
        eachNamespace {
            onWrite = { throw CancellationException("Synthetic provider cancellation") }
            assertFailsWith<CancellationException> { upload() }
            assertEquals(listOf("WRITING"), phases())
        }

    @Test fun aLostRegistrationAcknowledgementKeepsTheCanonicalArchiveOrMedia() =
        eachNamespace {
            val delegate = journal
            journal =
                object : ObjectUploads by delegate {
                    override fun <T> attach(
                        upload: ObjectUpload,
                        publish: () -> T,
                        ownsResult: (T) -> Boolean,
                    ): T {
                        delegate.attach(upload, publish, ownsResult)
                        throw IllegalStateException("Synthetic registration acknowledgement loss")
                    }
                }
            assertFailsWith<IllegalStateException> { upload() }
            assertEquals(listOf("ATTACHED"), phases())
            assertEquals(1, storage.listOwnerBlobPaths(owner.id.toJavaUUID()).size)
            assertEquals(0, deletions)
        }

    @Test fun aWriteOnceReceiptLoserRetiresOnlyTheNewCandidate() =
        eachNamespace {
            val canonical = upload()
            val replay = upload(existing = canonical)
            assertEquals(canonical, replay)
            assertEquals(listOf("ATTACHED", "RETIRED").sorted(), phases().sorted())
            assertEquals(listOf(canonical.path), storage.listOwnerBlobPaths(owner.id.toJavaUUID()))
        }

    @Test fun legacyMediaRefusesAMovedStorageModeWhileBackupsRemainAvailable() =
        eachNamespace {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            if (namespace == LogDateBlobNamespace.MEDIA) {
                assertFailsWith<IdentityWriteRefusedException> { upload() }
                assertEquals(0, writes)
            } else {
                upload()
                assertEquals(listOf("ATTACHED"), phases())
            }
        }

    @Test fun backupFinalizationUsesTheCapturedNonzeroEpoch() =
        withFixture(LogDateBlobNamespace.BACKUP) {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            upload(epoch = 1)
            assertEquals(listOf("ATTACHED"), phases())
        }

    @Test fun failedCleanupRestoresItsNamespaceAndNullableRepoAfterRestart() =
        eachNamespace {
            onWrite = { assertTrue(runBlocking { accounts.deleteAccount(owner.id) }) }
            onDelete = { throw IllegalStateException("Synthetic deletion failure") }
            assertFailsWith<IdentityWriteRefusedException> { upload() }
            assertEquals(listOf("DELETE_PENDING"), phases())
            val restarted = PostgreSQLObjectUploads()
            val work = restarted.pendingCleanup().single()
            assertEquals(namespace.name, work.kind.name)
            assertEquals(null, work.repo)
            assertEquals(owner.id, work.origin.accountId)
            assertTrue(
                restarted.cleanup(work) { path ->
                    assertNoIdleTransaction()
                    storage.deleteBlobIfExists(path)
                    true
                },
            )
            assertEquals(listOf("RETIRED"), phases())
        }

    private data class Stored(
        val path: String,
    )

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val namespace: LogDateBlobNamespace,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val storage = InMemoryLogDateBlobStorage()
        val media = PostgreSQLLogDateMediaRepository()
        val backups = PostgreSQLLogDateBackupRepository()
        var journal: ObjectUploads = PostgreSQLObjectUploads()
        var onWrite: () -> Unit = {}
        var onDelete: () -> Unit = {}
        var writes = 0
        var deletions = 0
        private val adoption = PostgreSQLIdentityAdoptionRepository(source)

        suspend fun upload(
            epoch: Long = 0,
            existing: Stored? = null,
        ): Stored {
            val id = Uuid.random().toJavaUUID()
            val request =
                LogDateBlobWriteRequest(
                    owner.id.toJavaUUID(),
                    namespace,
                    id.toString(),
                    "synthetic.bin",
                    "application/octet-stream",
                    byteArrayOf(1, 2, 3),
                )
            return LegacyObjectUploads(journal).upload(
                owner = owner.id.toJavaUUID(),
                namespace = namespace,
                epoch = epoch,
                plannedPath = { storage.plannedPath(request) },
                write = {
                    writes++
                    onWrite()
                    storage.putBlob(request)
                },
                canonicalPath = Stored::path,
                publish = { path, originalEpoch ->
                    if (existing != null) {
                        existing
                    } else {
                        if (namespace == LogDateBlobNamespace.MEDIA) {
                            media.upsertMedia(
                                owner.id.toJavaUUID(),
                                LogDateMedia(
                                    id.toString(),
                                    "entry",
                                    owner.id.toJavaUUID(),
                                    "synthetic.bin",
                                    "application/octet-stream",
                                    3,
                                    byteArrayOf(),
                                    path,
                                    1,
                                    0,
                                    DeviceId("synthetic-device"),
                                ),
                                originalEpoch,
                            )
                        } else {
                            backups.createBackup(
                                owner.id.toJavaUUID(),
                                LogDateBackup(
                                    id,
                                    owner.id.toJavaUUID(),
                                    "synthetic-device",
                                    "{}",
                                    path,
                                    1,
                                    3,
                                ),
                                originalEpoch,
                            )
                        }
                        Stored(path)
                    }
                },
                deleteObject = { path ->
                    deletions++
                    assertNoIdleTransaction()
                    onDelete()
                    storage.deleteBlobIfExists(path)
                    true
                },
            )
        }

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun hold(operation: IdentityAdoptionOperation) =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
            }

        fun phases(): List<String> =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement("SELECT phase FROM object_upload_intents WHERE account_id = ? ORDER BY id").use {
                        it.setObject(1, owner.id.toJavaUUID())
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                }
            }

        fun assertNoIdleTransaction() =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'",
                    ).use {
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
    }

    private fun eachNamespace(block: suspend Fixture.() -> Unit) {
        for (namespace in listOf(LogDateBlobNamespace.MEDIA, LogDateBlobNamespace.BACKUP)) withFixture(namespace, block)
    }

    private fun withFixture(
        namespace: LogDateBlobNamespace,
        block: suspend Fixture.() -> Unit,
    ) = withBlueskyOAuthDatabase { source ->
        val id = Uuid.random()
        val account =
            Account(
                id,
                id.toHexString(),
                "Synthetic upload owner",
                did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                createdAt = Clock.System.now(),
            )
        assertTrue(PostgreSQLAccountRepository().create(account))
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, created_at, " +
                    "publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, id.toJavaUUID())
                it.setString(3, account.did)
                it.setTimestamp(4, Clock.System.now().timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
        Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(id)), namespace).block()
    }
}
