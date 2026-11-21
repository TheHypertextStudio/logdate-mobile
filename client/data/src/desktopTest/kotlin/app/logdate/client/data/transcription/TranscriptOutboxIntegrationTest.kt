package app.logdate.client.data.transcription

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.metadata.DatabaseSyncMetadataService
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.shared.config.DefaultLogDateConfigRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TranscriptOutboxIntegrationTest {
    @Test
    fun `visible final speech or final silence leaves transcript and outbox unchanged`() =
        withDatabase { database ->
            val metadata = metadata(database.syncMetadataDao())
            val manager = FakeTranscriptionManager(true)
            val transcripts =
                OfflineFirstTranscriptionRepository(
                    database.transcriptionDao(),
                    database.audioNoteDao(),
                    manager,
                    syncMetadataService = metadata,
                    transactionManager = RoomSyncTransactionManager(database),
                    syncScope = this,
                )
            for (text in listOf("Spoken words", "")) {
                val note = note(database)
                assertTrue(transcripts.acceptSyncedTranscript(note.uid, TranscriptDocument.fromPlainText(text)))
                val saved = database.transcriptionDao().getTranscriptionByNoteId(note.uid)
                assertTrue(transcripts.requestTranscription(note.uid))
                assertEquals(saved, database.transcriptionDao().getTranscriptionByNoteId(note.uid))
            }
            assertEquals(0, manager.enqueueCount)
            assertEquals(0, metadata.getPendingCount())
        }

    @Test
    fun `transcript transaction completes while a pending read waits for the Room writer`() =
        withDatabase { database ->
            val readerEntered = CompletableDeferred<Unit>()
            val writerEntered = CompletableDeferred<Unit>()
            val readerNeedsWriter = CompletableDeferred<Unit>()
            val intercept = AtomicBoolean(true)
            val syncDao = database.syncMetadataDao()
            val metadata =
                metadata(
                    object : SyncMetadataDao by syncDao {
                        override suspend fun getPendingByTypePromotingLegacy(
                            ownerId: String,
                            serverOrigin: String,
                            entityType: String,
                        ): List<PendingUploadEntity> {
                            if (intercept.compareAndSet(true, false)) {
                                readerEntered.complete(Unit)
                                writerEntered.await()
                                readerNeedsWriter.complete(Unit)
                                // Legacy promotion also needs the writer while the read is in progress.
                                syncDao.insertPending(pending("probe", "UPDATE"))
                            }
                            return syncDao.getPendingByTypePromotingLegacy(ownerId, serverOrigin, entityType)
                        }
                    },
                )
            val transcriptDao = database.transcriptionDao()
            val transcripts =
                repository(
                    database,
                    metadata,
                    object : TranscriptionDao by transcriptDao {
                        override suspend fun insertTranscription(transcription: TranscriptionEntity): Long {
                            val id = transcriptDao.insertTranscription(transcription)
                            writerEntered.complete(Unit)
                            readerNeedsWriter.await()
                            return id
                        }
                    },
                )
            val note = note(database)
            val read = async { metadata.getPendingUploads(EntityType.NOTE) }
            withTimeout(5_000) {
                readerEntered.await()
                val save = this@withDatabase.async { transcripts.save(note) }
                assertTrue(save.await())
                assertEquals(setOf("probe", note.uid.toString()), read.await().map { it.entityId }.toSet())
            }
            assertEquals("Durable words", transcripts.getTranscription(note.uid)?.transcriptDocument?.plainText)
        }

    @Test
    fun `transcript mutation preserves expected server version and invalidates in-flight upload acknowledgment`() =
        withDatabase { database ->
            val note = note(database)
            val metadata = metadata(database.syncMetadataDao())
            metadata.enqueuePending(note.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
            val create = metadata.getPendingUploads(EntityType.NOTE).single()
            assertTrue(metadata.bindCreateToServerVersion(EntityType.NOTE, create, 42))
            val transmitted = metadata.getPendingUploads(EntityType.NOTE).single()
            assertTrue(repository(database, metadata).save(note))
            val mutation = metadata.getPendingUploads(EntityType.NOTE).single()
            assertEquals(PendingOperation.UPDATE, mutation.operation)
            assertEquals(42L, mutation.expectedServerVersion)
            assertNotEquals(transmitted.operationId, mutation.operationId)
            assertFalse(metadata.settleIfCurrent(EntityType.NOTE, transmitted, NOW, 43))
            assertEquals(mutation, metadata.getPendingUploads(EntityType.NOTE).single())
            assertTrue(metadata.advancePendingVersionAfterUpload(EntityType.NOTE, transmitted, 43))
            assertEquals(mutation.copy(expectedServerVersion = 43), metadata.getPendingUploads(EntityType.NOTE).single())
        }

    @Test
    fun `own upload advancement preserves newer bases deletion and other scopes`() =
        withDatabase { database ->
            val dao = database.syncMetadataDao()
            val metadata = metadata(dao)
            val note = note(database)
            val row = pending(note.uid.toString(), "UPDATE").copy(expectedServerVersion = 42)
            dao.insertPending(row)
            val uploaded = metadata.getPendingUploads(EntityType.NOTE).single()
            for (replacement in listOf(
                row.copy(expectedServerVersion = 44),
                row.copy(operation = "DELETE"),
                row.copy(operation = "CREATE"),
            )) {
                dao.insertPending(replacement)
                assertFalse(metadata.advancePendingVersionAfterUpload(EntityType.NOTE, uploaded, 43))
                assertEquals(replacement, dao.getPending(OWNER, ORIGIN, "NOTE", note.uid.toString()))
            }
            dao.insertPending(row)
            val foreign = row.copy(serverOrigin = "https://other.example.test")
            dao.insertPending(foreign)
            assertTrue(metadata.advancePendingVersionAfterUpload(EntityType.NOTE, uploaded, 43))
            assertEquals(foreign, dao.getPending(OWNER, foreign.serverOrigin, "NOTE", note.uid.toString()))
            assertFalse(metadata.advancePendingVersionAfterUpload(EntityType.NOTE, uploaded, 41))
        }

    @Test
    fun `transcript mutation retains a queued create and its original queue position`() =
        withDatabase { database ->
            val note = note(database)
            val dao = database.syncMetadataDao()
            val create = pending(note.uid.toString(), "CREATE").copy(createdAt = 10, retryCount = 3)
            dao.insertPending(create)
            assertTrue(repository(database, metadata(dao)).save(note))
            val mutation = dao.getPending(OWNER, ORIGIN, "NOTE", note.uid.toString())!!
            assertEquals("CREATE", mutation.operation)
            assertEquals(10L, mutation.createdAt)
            assertEquals(0, mutation.retryCount)
            assertNotEquals(create.operationId, mutation.operationId)
        }

    @Test
    fun `transcript mutation cannot replace scoped or legacy pending deletion`() =
        withDatabase { database ->
            val dao = database.syncMetadataDao()
            val transcripts = repository(database, metadata(dao))
            for (owner in listOf(OWNER, "")) {
                val note = note(database)
                val deletion = pending(note.uid.toString(), "DELETE").copy(ownerId = owner)
                dao.insertPending(deletion)
                assertTrue(transcripts.save(note))
                assertEquals(deletion, dao.getPending(owner, ORIGIN, "NOTE", note.uid.toString()))
                if (owner.isEmpty()) assertNull(dao.getPending(OWNER, ORIGIN, "NOTE", note.uid.toString()))
            }
        }

    private fun withDatabase(block: suspend CoroutineScope.(LogDateDatabase) -> Unit) =
        runBlocking {
            val file = Files.createTempFile("logdate-transcript-outbox-", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                scope.block(database)
            } finally {
                scope.cancel()
                database.close()
                Files.deleteIfExists(file)
                Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
                Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
            }
        }

    private suspend fun note(database: LogDateDatabase): AudioNoteEntity =
        AudioNoteEntity(contentUri = "memo.m4a", created = NOW, lastUpdated = NOW, durationMs = 4_000).also {
            database.audioNoteDao().addNote(it)
        }

    private fun metadata(dao: SyncMetadataDao) =
        DatabaseSyncMetadataService(
            dao,
            DefaultLogDateConfigRepository(initialBackendUrl = ORIGIN),
            object : CanonicalOwnerProvider {
                override suspend fun getCanonicalOwnerId(): String = OWNER

                override suspend fun hasBoundOwner(): Boolean = true
            },
        )

    private fun CoroutineScope.repository(
        database: LogDateDatabase,
        metadata: DatabaseSyncMetadataService,
        dao: TranscriptionDao = database.transcriptionDao(),
    ) = OfflineFirstTranscriptionRepository(
        dao,
        database.audioNoteDao(),
        FakeTranscriptionManager(true),
        syncMetadataService = metadata,
        transactionManager = RoomSyncTransactionManager(database),
        syncScope = this,
    )

    private suspend fun OfflineFirstTranscriptionRepository.save(note: AudioNoteEntity) =
        updateTranscriptDocument(note.uid, TranscriptDocument.fromPlainText("Durable words"), TranscriptionStatus.COMPLETED)

    private fun pending(
        noteId: String,
        operation: String,
    ) = PendingUploadEntity(OWNER, ORIGIN, "NOTE", noteId, operation, 1)

    private companion object {
        const val OWNER = "owner"
        const val ORIGIN = "https://sync.example.test"
        val NOW = Instant.parse("2026-10-09T12:00:00Z")
    }
}
