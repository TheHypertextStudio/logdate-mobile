package app.logdate.client.data.restore

import app.logdate.client.data.journals.ArchiveRecoveryReceipts
import app.logdate.client.data.journals.LocalDeletionType
import app.logdate.client.data.notes.EmptyNotePlaceResolver
import app.logdate.client.data.notes.JournalNoteRecoveryPersistence
import app.logdate.client.data.transcription.OfflineFirstTranscriptionRepository
import app.logdate.client.data.transcription.TranscriptRecoveryDeletions
import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionSegmentEntity
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.media.audio.transcription.TranscriptionManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.SyncMetadataService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedTranscriptionRecoveryPersistenceTest {
    private class Fixture {
        val scope = JournalMergeScope("owner", "https://example.test")
        var current: JournalMergeScope? = scope
        val now = Instant.fromEpochMilliseconds(13)
        val note = AudioNoteEntity("logdate-media://audio/original", durationMs = 321, created = now, lastUpdated = now)
        var audio: AudioNoteEntity? = note
        var row: TranscriptionEntity? = null
        var segments = emptyList<TranscriptionSegmentEntity>()
        var operation: String? = "CREATE"
        var fault = false
        var foreign: TranscriptionEntity? = null
        val receiptKeys = mutableSetOf<String>()
        val receipts =
            object : ArchiveRecoveryReceipts {
                override suspend fun contains(
                    scope: JournalMergeScope,
                    type: String,
                    recoveryId: String,
                    entityId: String,
                ) = "$scope/$type/$recoveryId/$entityId" in receiptKeys

                override suspend fun containsLocalDeletion(
                    scope: JournalMergeScope,
                    type: LocalDeletionType,
                    entityId: String,
                ) = "local:$scope/$type/$entityId" in receiptKeys

                override suspend fun recordLocalDeletion(
                    scope: JournalMergeScope,
                    type: LocalDeletionType,
                    entityId: String,
                ) {
                    receiptKeys +=
                        "local:$scope/$type/$entityId"
                }

                override suspend fun clearLocalDeletion(
                    scope: JournalMergeScope,
                    type: LocalDeletionType,
                    entityId: String,
                ) {
                    receiptKeys -=
                        "local:$scope/$type/$entityId"
                }

                override suspend fun record(
                    scope: JournalMergeScope,
                    type: String,
                    recoveryId: String,
                    entityId: String,
                ) {
                    receiptKeys += "$scope/$type/$recoveryId/$entityId"
                }
            }
        val transactions =
            object : SyncTransactionManager {
                override suspend fun <T> withTransaction(block: suspend () -> T): T {
                    val before = row
                    val beforeSegments = segments
                    val beforeOperation = operation
                    val beforeReceipts = receiptKeys.toSet()
                    return try {
                        block()
                    } catch (error: Throwable) {
                        row = before
                        segments = beforeSegments
                        operation = beforeOperation
                        receiptKeys.clear()
                        receiptKeys += beforeReceipts
                        throw error
                    }
                }
            }

        inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name.substringBefore('-'), args.orEmpty())
            } as T

        val transcripts: TranscriptionDao =
            proxy { name, args ->
                when (name) {
                    "getTranscriptionByNoteId" -> {
                        row
                    }

                    "observeAllTranscriptions" -> {
                        MutableStateFlow(row?.let { listOf(it) }.orEmpty())
                    }

                    "deleteSegmentsByNoteId" -> {
                        val size = segments.size
                        segments = emptyList()
                        size
                    }

                    "deleteTranscriptionByNoteId" -> {
                        val count = if (row == null) 0 else 1
                        row = null
                        count
                    }

                    "getTranscriptionById" -> {
                        foreign?.takeIf { it.id == args[0] } ?: row?.takeIf { it.id == args[0] }
                    }

                    "getSegmentsByNoteId" -> {
                        segments
                    }

                    "insertTranscription" -> {
                        row = args[0] as TranscriptionEntity
                        1L
                    }

                    "updateTranscription" -> {
                        row = args[0] as TranscriptionEntity
                        1
                    }

                    "replaceSegmentsForNote" -> {
                        if (fault) throw IllegalStateException("Interrupted segment write")
                        @Suppress("UNCHECKED_CAST")
                        segments = args[1] as List<TranscriptionSegmentEntity>
                        Unit
                    }

                    else -> {
                        error("Unexpected transcript DAO call: $name")
                    }
                }
            }
        val audioDao: AudioNoteDao =
            proxy { name, args ->
                when (name) {
                    "findNoteOneOff", "getNoteOneOff" -> {
                        audio
                    }

                    "updateForRestore" -> {
                        audio = args[0] as AudioNoteEntity
                        1
                    }

                    "getAllNotes" -> {
                        MutableStateFlow(audio?.let { listOf(it) }.orEmpty())
                    }

                    else -> {
                        error("Unexpected audio call $name")
                    }
                }
            }
        var enqueued = 0
        val manager: TranscriptionManager =
            proxy { name, _ ->
                when (name) {
                    "enqueueTranscription" -> {
                        enqueued++
                        true
                    }

                    "cancelTranscription" -> {
                        true
                    }

                    else -> {
                        error(name)
                    }
                }
            }
        val deletions =
            object : TranscriptRecoveryDeletions {
                override suspend fun contains(noteId: Uuid) =
                    receipts.containsLocalDeletion(scope, LocalDeletionType.TRANSCRIPTION, noteId.toString())

                override suspend fun record(noteId: Uuid) =
                    receipts.recordLocalDeletion(scope, LocalDeletionType.TRANSCRIPTION, noteId.toString())

                override suspend fun clear(noteId: Uuid) =
                    receipts.clearLocalDeletion(scope, LocalDeletionType.TRANSCRIPTION, noteId.toString())
            }

        fun repository() =
            OfflineFirstTranscriptionRepository(
                transcripts,
                audioDao,
                manager,
                transactionManager = transactions,
                recoveryDeletions = deletions,
            )

        val metadata: SyncMetadataService =
            proxy { name, _ ->
                check(name == "enqueueRestoreMutation")
                if (operation == "DELETE") {
                    false
                } else {
                    if (operation != "CREATE") operation = "UPDATE"
                    true
                }
            }

        fun persistence() = RetainedTranscriptionRecoveryPersistence(transcripts, audioDao, transactions, metadata, receipts) { current }

        fun source() =
            RetainedTranscriptionSnapshot(
                TranscriptionEntity(
                    note.uid,
                    "retained words",
                    "{raw document}",
                    "fr-CA",
                    "CLOUD_REFINEMENT",
                    "model",
                    7,
                    note.contentUri,
                    note.durationMs,
                    true,
                    2,
                    TranscriptionStatus.COMPLETED,
                    "retained diagnostic",
                    now,
                    now,
                ),
                listOf(
                    TranscriptionSegmentEntity(
                        note.uid,
                        "segment-one",
                        "retained words",
                        11,
                        17,
                        "speaker",
                        .72f,
                        "CLOUD_REFINEMENT",
                        true,
                        7,
                    ),
                ),
            )

        suspend fun apply(
            expected: RetainedTranscriptionSnapshot? = null,
            replacement: RetainedTranscriptionSnapshot = source(),
            guard: suspend () -> Unit = {
            },
        ) = persistence().compareAndSet(expected, replacement, note, scope, "retained:source", guard)
    }

    @Test fun preservesEveryTypedFieldAndSegmentsWithExistingCreateOutbox() =
        runBlocking {
            val fixture = Fixture()
            val source = fixture.source()
            assertTrue(fixture.apply(replacement = source))
            assertEquals(source, fixture.persistence().snapshot(fixture.note.uid))
            assertEquals("CREATE", fixture.operation)
            assertEquals(1, fixture.receiptKeys.size)
        }

    @Test fun workingTranscriptOrSegmentEditsFailCasWithoutMutation() =
        runBlocking {
            val fixture = Fixture()
            val source = fixture.source()
            fixture.row = source.row.copy(text = "working words")
            assertFalse(fixture.apply(replacement = source))
            assertEquals("working words", fixture.row?.text)
            fixture.row = source.row
            fixture.segments = source.segments.map { it.copy(text = "working segment") }
            assertFalse(fixture.apply(expected = source, replacement = source.copy(row = source.row.copy(language = "de"))))
            assertEquals("working segment", fixture.segments.single().text)
            assertTrue(fixture.receiptKeys.isEmpty())
        }

    @Test fun noteDeletionReplacementAndQueuedDeletionRemainUntouched() =
        runBlocking {
            for (kind in 0..2) {
                val fixture = Fixture()
                when (kind) {
                    0 -> fixture.audio = fixture.note.copy(deletedAt = fixture.now)
                    1 -> fixture.audio = fixture.note.copy(contentUri = "working replacement")
                    2 -> fixture.operation = "DELETE"
                }
                assertFalse(fixture.apply())
                assertEquals(null, fixture.row)
                assertTrue(fixture.receiptKeys.isEmpty())
            }
        }

    @Test fun rowSegmentsReceiptAndOutboxRollbackTogetherOnInterruptedWrite() =
        runBlocking {
            val fixture = Fixture()
            fixture.operation = null
            fixture.fault = true
            assertFailsWith<IllegalStateException> { fixture.apply() }
            assertEquals(null, fixture.row)
            assertTrue(fixture.segments.isEmpty())
            assertTrue(fixture.receiptKeys.isEmpty())
            assertEquals(null, fixture.operation)
            fixture.fault = false
            assertTrue(fixture.apply())
        }

    @Test fun staleOwnerOriginAndSourceGenerationCannotCommit() =
        runBlocking {
            for (kind in 0..2) {
                val fixture = Fixture()
                assertFailsWith<IllegalStateException> {
                    fixture.apply(guard = {
                        when (kind) {
                            0 -> fixture.current = fixture.scope.copy(ownerId = "other")
                            1 -> fixture.current = fixture.scope.copy(serverOrigin = "https://other.test")
                            2 -> error("Retained generation superseded")
                        }
                    })
                }
                assertEquals(null, fixture.row)
                assertTrue(fixture.segments.isEmpty())
                assertTrue(fixture.receiptKeys.isEmpty())
            }
        }

    @Test fun committedReceiptSurvivesCheckpointLossAndNeverResurrectsDeletedTranscript() =
        runBlocking {
            val fixture = Fixture()
            val source = fixture.source()
            assertTrue(fixture.apply(replacement = source))
            fixture.row = fixture.row?.copy(text = "edited after recovery")
            assertTrue(fixture.apply(replacement = source))
            assertEquals("edited after recovery", fixture.row?.text)
            fixture.row = null
            fixture.segments = emptyList()
            assertFalse(fixture.apply(replacement = source))
            assertEquals(null, fixture.row)
            assertEquals(1, fixture.receiptKeys.size)
        }

    @Test fun unrelatedTranscriptPrimaryKeyCannotBeReplaced() =
        runBlocking {
            val fixture = Fixture()
            val source = fixture.source()
            fixture.foreign = source.row.copy(noteId = Uuid.random(), text = "unrelated working transcript")
            assertFalse(fixture.apply(replacement = source))
            assertEquals(null, fixture.row)
            assertEquals("unrelated working transcript", fixture.foreign?.text)
        }

    @Test fun cancellationAndCrossNoteSegmentsCannotProducePartialContent() =
        runBlocking {
            val fixture = Fixture()
            assertFailsWith<CancellationException> { fixture.apply(guard = { throw CancellationException("superseded") }) }
            val source = fixture.source()
            assertFailsWith<IllegalArgumentException> {
                fixture.apply(replacement = source.copy(segments = source.segments.map { it.copy(noteId = Uuid.random()) }))
            }
            assertEquals(null, fixture.row)
            assertTrue(fixture.receiptKeys.isEmpty())
        }

    @Test fun transcriptOnlyDeletionSurvivesRestartUntilExplicitUserRequest() =
        runTest {
            val fixture = Fixture()
            val source = fixture.source()
            fixture.row = source.row
            fixture.segments = source.segments
            assertTrue(fixture.repository().deleteTranscription(fixture.note.uid))
            assertTrue(fixture.deletions.contains(fixture.note.uid))
            assertFalse(fixture.apply(replacement = source))
            val restarted = fixture.repository()
            restarted.startAutomaticTranscriptions(backgroundScope)
            runCurrent()
            assertEquals(0, fixture.enqueued)
            assertEquals(null, restarted.beginTranscription(fixture.note.uid, fixture.note.contentUri))
            assertTrue(restarted.requestTranscription(fixture.note.uid))
            assertFalse(fixture.deletions.contains(fixture.note.uid))
            assertEquals(1, fixture.enqueued)
        }

    @Test fun plainTextLegacyRowsAndUnorderedSegmentsRetainTheirExactFields() =
        runBlocking {
            val fixture = Fixture()
            val source = fixture.source().let { it.copy(row = it.row.copy(documentJson = null, status = TranscriptionStatus.FAILED)) }
            assertTrue(fixture.apply(replacement = source))
            assertEquals(source, fixture.persistence().snapshot(fixture.note.uid))
            val two = source.copy(segments = source.segments + source.segments.single().copy(segmentId = "second", text = "other segment"))
            assertEquals(two.contentFingerprint(), two.copy(segments = two.segments.reversed()).contentFingerprint())
            assertTrue(two.contentFingerprint() != two.copy(row = two.row.copy(modelId = "changed")).contentFingerprint())
            assertTrue(two.contentFingerprint() != two.copy(segments = two.segments.map { it.copy(revision = 99) }).contentFingerprint())
        }

    @Test fun retainedLateMediaRepairRebindsOnlyUriWithoutNormalizingTypedTranscript() =
        runBlocking {
            val fixture = Fixture()
            val source =
                fixture.source().let {
                    it.copy(row = it.row.copy(documentJson = Json.encodeToString(TranscriptDocument.fromPlainText("retained words"))))
                }
            fixture.row = source.row
            fixture.segments = source.segments
            val transcriptRepository = fixture.repository()

            suspend fun current(): JournalNote.Audio? =
                fixture.audio?.let { audio ->
                    JournalNote.Audio(
                        mediaRef = audio.contentUri,
                        durationMs = audio.durationMs,
                        uid = audio.uid,
                        creationTimestamp = audio.created,
                        lastUpdated = audio.lastUpdated,
                        transcript = transcriptRepository.getTranscription(audio.uid)?.transcriptDocument,
                    )
                }
            val persistence =
                JournalNoteRecoveryPersistence(
                    fixture.proxy<TextNoteDao> { name, _ -> error(name) },
                    fixture.proxy<ImageNoteDao> { name, _ -> error(name) },
                    fixture.audioDao,
                    fixture.proxy<VideoNoteDao> { name, _ -> error(name) },
                    fixture.proxy<MediaCaptionDao> { name, _ ->
                        check(name == "upsertCaption")
                        Unit
                    },
                    transcriptRepository,
                    fixture.transactions,
                    fixture.metadata,
                    StoredMediaReferences.Unchanged,
                    { current() },
                    { fixture.current },
                    {},
                    {},
                    fixture.receipts,
                    EmptyNotePlaceResolver,
                )
            val before = checkNotNull(current())
            assertTrue(
                persistence.compareAndSet(
                    before,
                    before.copy(mediaRef = "logdate-media://audio/recovered"),
                    fixture.scope,
                    "retained-media-repair",
                    retainedSource = true,
                    sourceId = before.uid,
                ),
            )
            assertEquals(source.row.copy(mediaUri = "logdate-media://audio/recovered"), fixture.row)
            assertEquals(source.segments, fixture.segments)
            assertEquals("CREATE", fixture.operation)
        }
}
