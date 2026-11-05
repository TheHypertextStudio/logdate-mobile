@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.migration.StoredPrecisionClock
import app.logdate.server.space.SpaceBlob
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.space.SpaceRef
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import studio.hypertext.atproto.repo.Cid
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LegacyMigrationUploadBindingPostgresTest {
    @Test
    fun capturedMigrationIdentityAndVersionSurviveRepositoryRestart() =
        fixture {
            val upload = admit()
            assertEquals(migration.migrationId, upload.legacyMigrationId)
            assertEquals(1L, upload.legacyMigrationVersion)
            PostgreSQLObjectUploads().stored(upload)
            source.blueskyTransaction {
                prepareStatement("SELECT legacy_migration_id, legacy_migration_version FROM object_upload_intents WHERE id = ?").use {
                    it.setString(1, upload.id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(migration.migrationId, rows.getString(1))
                        assertEquals(1L, rows.getLong(2))
                    }
                }
            }
        }

    @Test
    fun anotherMigrationCannotAdmitAFileCopy() =
        fixture {
            assertFailsWith<IdentityWriteRefusedException> { admit(migrationId = "replacement") }
            assertEquals(0L, intentCount())
        }

    @Test
    fun anOldMigrationRevisionCannotAdmitAFileCopy() =
        fixture {
            val advanced = migration.next(StoredPrecisionClock().now()) { copy() }
            assertTrue(migrations.replace(migration, advanced))
            assertFailsWith<IdentityWriteRefusedException> { admit() }
            assertEquals(0L, intentCount())
        }

    @Test
    fun aSwitchedMigrationCannotAdmitEvenWithItsCurrentRevision() =
        fixture {
            val now = StoredPrecisionClock().now()
            val switched = migration.next(now) { copy(phase = SpaceMigrationPhase.SWITCHED, switchedAt = now, switchedAtSeq = 0) }
            assertTrue(migrations.replaceAndSwitch(migration, switched, AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1))
            assertFailsWith<IdentityWriteRefusedException> { admit(version = switched.version) }
            assertEquals(0L, intentCount())
        }

    @Test
    fun aLateMetadataWriteCannotAttachAfterTheMigrationChanges() =
        fixture {
            val upload = admit()
            store(upload)
            val advanced = migration.next(StoredPrecisionClock().now()) { copy() }
            assertTrue(migrations.replace(migration, advanced))
            assertFailsWith<IdentityWriteRefusedException> { attach(upload) }
            assertNull(spaces.getBlob(space, cid))
            assertEquals("STORED", phase(upload))
            assertTrue(journal.cleanup(upload) { storage.deleteLegacyObject(it) })
            assertNull(storage.getBlob(upload.path))
            assertEquals("RETIRED", phase(upload))
        }

    @Test
    fun anAdmittedMigrationBindingCannotBeRewritten() =
        fixture {
            val upload = admit()
            val failure =
                assertFailsWith<SQLException> {
                    source.blueskyTransaction {
                        prepareStatement(
                            "UPDATE object_upload_intents SET legacy_migration_id = 'replacement', " +
                                "legacy_migration_version = 99 WHERE id = ?",
                        ).use {
                            it.setString(1, upload.id)
                            it.executeUpdate()
                        }
                    }
                }
            assertEquals("23514", failure.sqlState)
        }

    @Test
    fun validMigrationCopyMetadataAndItsAdmissionAttachTogether() =
        fixture {
            val upload = admit()
            store(upload)
            val attached = attach(upload)
            assertEquals(upload.path, attached.storagePath)
            assertEquals("ATTACHED", phase(upload))
            assertEquals(attached, spaces.getBlob(space, cid))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val migration: SpaceMigration,
    ) {
        val journal = PostgreSQLObjectUploads()
        val migrations = PostgreSQLSpaceMigrationStore()
        val spaces = PostgreSQLSpaceStore()
        val storage = InMemoryLogDateBlobStorage()
        val space = SpaceRef.personal(requireNotNull(owner.did)).uri
        val bytes = "LDCE2:synthetic-private-copy".encodeToByteArray()
        val cid = Cid.rawSha256(bytes).value
        val requests = mutableMapOf<String, LogDateBlobWriteRequest>()

        fun admit(
            migrationId: String = migration.migrationId,
            version: Long = migration.version,
        ): ObjectUpload {
            val request =
                LogDateBlobWriteRequest(
                    owner.id.toJavaUUID(),
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = bytes,
                )
            val path = storage.plannedPath(request)
            requests[path] = request
            return journal.beginMigration(IdentityWriteExpectation(owner.id, 0), path, space, migrationId, version)
        }

        fun store(upload: ObjectUpload) {
            assertNull(TransactionManager.currentOrNull())
            assertEquals(upload.path, storage.putBlob(requests.getValue(upload.path)))
            journal.stored(upload)
        }

        suspend fun attach(upload: ObjectUpload): SpaceBlob =
            spaces.putAdmittedBlob(
                owner.id.toJavaUUID(),
                space,
                SpaceBlob(cid, "application/octet-stream", bytes.size.toLong(), upload.path),
                upload.id,
            )

        suspend fun phase(upload: ObjectUpload): String? =
            source.blueskyTransaction {
                prepareStatement("SELECT phase FROM object_upload_intents WHERE id = ?").use {
                    it.setString(1, upload.id)
                    it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }

        suspend fun intentCount(): Long =
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM object_upload_intents WHERE account_id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getLong(1)
                    }
                }
            }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Synthetic copy owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = StoredPrecisionClock().now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val migration =
                SpaceMigration(id.toJavaUUID(), Uuid.random().toString(), SpaceMigrationPhase.UPLOADING, owner.createdAt, owner.createdAt)
            assertTrue(PostgreSQLSpaceMigrationStore().replace(null, migration))
            Fixture(source, owner, migration).block()
        }
}
