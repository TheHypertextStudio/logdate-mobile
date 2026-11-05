package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.migration.LegacyMediaCopier
import app.logdate.server.migration.LegacyMediaCopyMismatchException
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.migration.SpaceMigrationResult
import app.logdate.server.migration.StoredPrecisionClock
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceUploadReservation
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.space.SpaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LegacyMediaCopyCommitPostgresTest {
    @Test
    fun lostMetadataAcknowledgementKeepsCommittedBytesReadable() =
        withFixture {
            val failure = IllegalStateException("Synthetic lost metadata acknowledgement")
            val copier = failingCopier(failure, afterCommit = true)
            assertSame(failure, runCatching { copier.copy(id, space, sourceMedia.mediaId) }.exceptionOrNull())
            val path = storage.copies.single()
            assertContentEquals(bytes, storage.getBlob(path))
            val stored = assertNotNull(spaces.getBlob(space, cid))
            assertEquals(path, stored.storagePath)
            assertContentEquals(bytes, storage.getBlob(stored.storagePath))
            assertSource()
        }

    @Test
    fun cancellationAfterMetadataCommitKeepsCommittedBytesReadable() =
        withFixture {
            val failure = CancellationException("Synthetic cancellation after commit")
            assertSame(failure, runCatching { failingCopier(failure, true).copy(id, space, sourceMedia.mediaId) }.exceptionOrNull())
            val stored = assertNotNull(spaces.getBlob(space, cid))
            assertContentEquals(bytes, storage.getBlob(stored.storagePath))
            assertSource()
        }

    @Test
    fun precommitFailureCleansOnlyTheUnreferencedCandidate() =
        withFixture {
            val failure = IllegalStateException("Synthetic failure before metadata")
            assertSame(failure, runCatching { failingCopier(failure, false).copy(id, space, sourceMedia.mediaId) }.exceptionOrNull())
            val path = storage.copies.single()
            assertNull(storage.getBlob(path))
            assertEquals(true, cleanupCompleted(path))
            assertNull(spaces.getBlob(space, cid))
            assertSource()
        }

    @Test
    fun anUnverifiableMetadataOutcomeRetainsTheCandidate() =
        withFixture {
            val failure = IllegalStateException("Synthetic metadata result unavailable")
            val copier = failingCopier(failure, false, lookupFails = true)
            assertSame(failure, runCatching { copier.copy(id, space, sourceMedia.mediaId) }.exceptionOrNull())
            assertContentEquals(bytes, storage.getBlob(storage.copies.single()))
            assertSource()
        }

    @Test
    fun aLosingCandidateReferencedElsewhereCannotBeDeleted() =
        withFixture {
            val copier = LegacyMediaCopier(media, storage, spaces)
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId))
            val canonical = assertNotNull(spaces.getBlob(space, cid))
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            storage.afterCommit = { path ->
                metadata.upsertMedia(other, sourceMedia.copy(mediaId = "shared", userId = other, storagePath = path))
            }
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId))
            val shared = assertNotNull(metadata.getMedia(other, "shared"))
            assertContentEquals(bytes, storage.getBlob(assertNotNull(shared.storagePath)))
            assertEquals(canonical.storagePath, spaces.getBlob(space, cid)?.storagePath)
            assertSource()
        }

    @Test
    fun anUnreferencedDuplicateCandidateIsRetiredWithoutDeletingCanonicalBytes() =
        withFixture {
            val copier = LegacyMediaCopier(media, storage, spaces)
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId))
            val canonical = assertNotNull(spaces.getBlob(space, cid))
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId))
            val losingPath = storage.copies.last()
            assertNull(storage.getBlob(losingPath))
            assertEquals(true, cleanupCompleted(losingPath))
            assertContentEquals(bytes, storage.getBlob(canonical.storagePath))
            assertSource()
        }

    @Test
    fun readbackMismatchCannotDeleteAnObjectReferencedElsewhere() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            storage.afterCommit = { path ->
                metadata.upsertMedia(other, sourceMedia.copy(mediaId = "shared", userId = other, storagePath = path))
            }
            storage.corruptReadback = true
            assertFailsWith<LegacyMediaCopyMismatchException> {
                LegacyMediaCopier(media, storage, spaces).copy(id, space, sourceMedia.mediaId)
            }
            val shared = assertNotNull(metadata.getMedia(other, "shared"))
            assertContentEquals(bytes, storage.getBlob(assertNotNull(shared.storagePath)))
            assertSource()
        }

    @Test
    fun failedCandidateDeletionHasADurableClaimForRecovery() =
        withFixture {
            val failure = IllegalStateException("Synthetic failure before metadata")
            storage.failDeletion = true
            assertSame(failure, runCatching { failingCopier(failure, false).copy(id, space, sourceMedia.mediaId) }.exceptionOrNull())
            val path = storage.copies.single()
            assertEquals(false, cleanupCompleted(path))
            assertContentEquals(bytes, storage.getBlob(path))
            storage.failDeletion = false
            assertTrue(media.cleanupUnreferencedObject(id, path) { storage.deleteLegacyObject(it) })
            assertEquals(true, cleanupCompleted(path))
            assertNull(storage.getBlob(path))
            assertSource()
        }

    @Test
    fun staleMigrationIsRefusedBeforeOpeningAProviderWriter() =
        withFixture {
            val original = startMigration()
            advance(original)
            assertFailsWith<IdentityWriteRefusedException> { durableCopier().copy(id, space, sourceMedia.mediaId, original) }
            assertTrue(storage.copies.isEmpty())
            assertSource()
        }

    @Test
    fun changedEpochIsRefusedBeforeOpeningAProviderWriter() =
        withFixture {
            val original = startMigration()
            changeEpoch()
            assertFailsWith<IdentityWriteRefusedException> { durableCopier().copy(id, space, sourceMedia.mediaId, original) }
            assertTrue(storage.copies.isEmpty())
            assertSource()
        }

    @Test
    fun migrationChangingDuringProviderCopyCannotAttachMetadata() =
        withFixture {
            val original = startMigration()
            storage.afterCommit = { runBlocking { advance(original) } }
            assertFailsWith<IdentityWriteRefusedException> { durableCopier().copy(id, space, sourceMedia.mediaId, original) }
            val candidate = storage.copies.single()
            assertNull(spaces.getBlob(space, cid))
            assertNull(storage.getBlob(candidate))
            assertEquals("RETIRED", uploadPhase(candidate))
            assertSource()
        }

    @Test
    fun aLostProviderCommitAcknowledgementRetainsWritingAdmissionAndBytes() =
        withFixture {
            val original = startMigration()
            val lost = IllegalStateException("Synthetic provider acknowledgement loss")
            storage.afterCommit = { throw lost }
            val failure = assertFailsWith<IllegalStateException> { durableCopier().copy(id, space, sourceMedia.mediaId, original) }
            assertEquals(lost.message, failure.message)
            val candidate = storage.copies.single()
            assertContentEquals(bytes, storage.getBlob(candidate))
            assertEquals("WRITING", uploadPhase(candidate))
            assertNull(spaces.getBlob(space, cid))
            assertSource()
        }

    @Test
    fun durableMetadataAcknowledgementLossPreservesAttachedDestination() =
        withFixture {
            val original = startMigration()
            val lost = IllegalStateException("Synthetic metadata acknowledgement loss")
            val uncertain =
                object : SpaceStore by spaces {
                    override suspend fun putAdmittedBlob(
                        accountId: UUID,
                        space: String,
                        blob: SpaceBlob,
                        admissionId: String,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob {
                        spaces.putAdmittedBlob(accountId, space, blob, admissionId, quota, reservation, identityEpoch)
                        throw lost
                    }
                }
            assertSame(lost, runCatching { durableCopier(uncertain).copy(id, space, sourceMedia.mediaId, original) }.exceptionOrNull())
            val canonical = assertNotNull(spaces.getBlob(space, cid))
            assertContentEquals(bytes, storage.getBlob(canonical.storagePath))
            assertEquals("ATTACHED", uploadPhase(canonical.storagePath))
            assertSource()
        }

    @Test
    fun durableDuplicateCopyRetiresOnlyItsLosingAdmission() =
        withFixture {
            val original = startMigration()
            val copier = durableCopier()
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId, original))
            val canonical = assertNotNull(spaces.getBlob(space, cid))
            assertIs<SpaceMigrationResult.Done<*>>(copier.copy(id, space, sourceMedia.mediaId, original))
            val loser = storage.copies.last()
            assertNull(storage.getBlob(loser))
            assertEquals("RETIRED", uploadPhase(loser))
            assertEquals("ATTACHED", uploadPhase(canonical.storagePath))
            assertContentEquals(bytes, storage.getBlob(canonical.storagePath))
            assertSource()
        }

    private class Fixture(
        val database: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val space = SpaceRef.personal(requireNotNull(owner.did)).uri
        val metadata = PostgreSQLLogDateMediaRepository()
        val media = CompositeLogDateMediaBlobRepository(metadata, PostgreSQLLogDateAtprotoBlobRepository())
        val spaces = PostgreSQLSpaceStore()
        val storage = RecordingStorage()
        val bytes = "LDCE2:synthetic-ciphertext".encodeToByteArray()
        val cid =
            studio.hypertext.atproto.repo.Cid
                .rawSha256(bytes)
                .value
        lateinit var sourceMedia: LogDateMedia

        fun durableCopier(store: SpaceStore = spaces) =
            LegacyMediaCopier(media, storage, store, SpaceObjectUploads(PostgreSQLObjectUploads()))

        suspend fun startMigration(): SpaceMigration {
            val now = StoredPrecisionClock().now()
            val migration = SpaceMigration(id, Uuid.random().toString(), SpaceMigrationPhase.UPLOADING, now, now)
            assertTrue(PostgreSQLSpaceMigrationStore().replace(null, migration))
            return migration
        }

        suspend fun advance(original: SpaceMigration) {
            assertTrue(PostgreSQLSpaceMigrationStore().replace(original, original.next(StoredPrecisionClock().now()) { copy() }))
        }

        suspend fun changeEpoch() =
            database.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun uploadPhase(path: String): String? =
            database.blueskyTransaction {
                prepareStatement("SELECT phase FROM object_upload_intents WHERE storage_path = ?").use {
                    it.setString(1, path)
                    it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }

        fun seed() {
            val path = storage.putBlob(LogDateBlobWriteRequest(id, LogDateBlobNamespace.MEDIA, "source", "source.enc", "audio/mp4", bytes))
            sourceMedia =
                metadata.upsertMedia(
                    id,
                    LogDateMedia(
                        "source",
                        "entry",
                        id,
                        "source.enc",
                        "audio/mp4",
                        bytes.size.toLong(),
                        ByteArray(0),
                        path,
                        1,
                        0,
                        DeviceId("d"),
                    ),
                )
        }

        fun failingCopier(
            failure: Throwable,
            afterCommit: Boolean,
            lookupFails: Boolean = false,
        ): LegacyMediaCopier {
            val delegate = spaces
            val uncertain =
                object : SpaceStore by delegate {
                    override suspend fun putBlob(
                        accountId: UUID,
                        space: String,
                        blob: SpaceBlob,
                        quota: SpaceQuota?,
                        reservation: SpaceUploadReservation?,
                        identityEpoch: Long,
                    ): SpaceBlob {
                        if (afterCommit) delegate.putBlob(accountId, space, blob, quota, reservation, identityEpoch)
                        throw failure
                    }

                    override suspend fun getBlob(
                        space: String,
                        cid: String,
                    ): SpaceBlob? {
                        if (lookupFails) error("Synthetic metadata lookup failure")
                        return delegate.getBlob(space, cid)
                    }
                }
            return LegacyMediaCopier(media, storage, uncertain)
        }

        suspend fun cleanupCompleted(path: String): Boolean? =
            database.blueskyTransaction {
                prepareStatement("SELECT completed FROM legacy_object_deletions WHERE storage_path = ?").use {
                    it.setString(1, path)
                    it.executeQuery().use { rows -> if (rows.next()) rows.getBoolean(1) else null }
                }
            }

        fun assertSource() {
            assertEquals(sourceMedia, metadata.getMedia(id, sourceMedia.mediaId))
            assertContentEquals(bytes, storage.getBlob(assertNotNull(sourceMedia.storagePath)))
        }
    }

    private class RecordingStorage(
        private val delegate: InMemoryLogDateBlobStorage = InMemoryLogDateBlobStorage(),
    ) : LogDateBlobStorage by delegate {
        val copies = mutableListOf<String>()
        var afterCommit: ((String) -> Unit)? = null
        var corruptReadback = false
        var failDeletion = false

        override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
            assertNull(TransactionManager.currentOrNull(), "Provider writer must open outside SQL")
            val writer = delegate.openBlobWriter(request)
            copies += writer.storagePath
            return object : LogDateBlobWriter by writer {
                override fun commit() {
                    writer.commit()
                    afterCommit?.invoke(writer.storagePath)
                }
            }
        }

        override fun openBlob(storagePath: String): LogDateBlobStream? {
            if (corruptReadback && storagePath in copies) return LogDateBlobStream(1, ByteArrayInputStream(byteArrayOf(0)))
            return delegate.openBlob(storagePath)
        }

        override fun deleteBlobIfExists(storagePath: String) {
            assertNull(TransactionManager.currentOrNull(), "Provider deletion must run outside SQL")
            if (failDeletion) error("Synthetic object deletion failure")
            delegate.deleteBlobIfExists(storagePath)
        }
    }

    private fun withFixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { database ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Copy owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val fixture = Fixture(database, owner)
            fixture.seed()
            fixture.action()
        }
}
