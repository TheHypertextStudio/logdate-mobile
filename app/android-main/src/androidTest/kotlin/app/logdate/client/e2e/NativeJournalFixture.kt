package app.logdate.client.e2e

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.media.audio.CrashSafeRecording
import app.logdate.client.media.storage.LocalMediaRef
import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import app.logdate.client.sync.AutomaticMediaRecovery
import app.logdate.client.sync.AutomaticMediaRecoveryResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.diagnostics.DiagnosticReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class NativeJournalFixture(
    private val journals: JournalRepository,
    private val notes: JournalNotesRepository,
    private val links: JournalContentRepository,
    private val media: MediaManager,
    private val syncMetadata: SyncMetadataService,
) {
    suspend fun removeQuotaOnlyNoteAndCompact(
        context: android.content.Context,
        database: LogDateDatabase,
    ) {
        notes.removeById(id(999))
        syncMetadata
            .getPendingUploads(EntityType.NOTE)
            .filter { it.entityId == id(999).toString() }
            .forEach { pending ->
                syncMetadata.markAsSynced(pending.entityId, EntityType.NOTE, Clock.System.now(), version = 0)
            }
        check(!syncMetadata.hasPending(EntityType.NOTE, id(999).toString())) {
            "Quota-only note left a pending payload in the synthetic source"
        }
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        db.execSQL("VACUUM")
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        assertNull(notes.getNoteById(id(999)), "Quota-only fixture row survived cleanup")
        val fileSize = context.getDatabasePath("logdate").length()
        val pageCount =
            db.query("PRAGMA page_count").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        val freePages =
            db.query("PRAGMA freelist_count").use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
        check(fileSize < MAX_NATIVE_TRANSFER_FIXTURE_DATABASE_BYTES) {
            "Synthetic transfer fixture exceeds its size guard after removing the quota-only row " +
                "($fileSize bytes, database=${database.openHelper.databaseName}, pages=$pageCount, freePages=$freePages)"
        }
    }

    fun exceedCloudQuota(context: android.content.Context) {
        val payload = context.filesDir.resolve("db_recovery/native-backup-quota-acceptance.bin")
        val directory = checkNotNull(payload.parentFile)
        if (!directory.isDirectory) check(directory.mkdirs())
        check(payload.createNewFile()) { "Quota acceptance payload already exists" }
        val random = java.util.Random(0x4c6f6744617465L)
        val buffer = ByteArray(64 * 1024)
        payload.outputStream().buffered().use { output ->
            repeat(28 * 1024 * 1024 / buffer.size) {
                random.nextBytes(buffer)
                output.write(buffer)
            }
        }
    }

    companion object {
        fun cleanupQuotaPayload(context: android.content.Context) {
            val payload = context.filesDir.resolve("db_recovery/native-backup-quota-acceptance.bin")
            if (payload.exists()) check(payload.delete()) { "Could not remove quota acceptance payload" }
        }
    }

    suspend fun seed(
        context: android.content.Context,
        includePendingCameraDraft: Boolean = false,
    ) {
        assertNull(journals.getJournalById(id(1)), "fixture already exists; use fresh emulator data")
        journals.create(Journal(id = id(1), title = "Native transfer journal"))
        journals.create(Journal(id = id(2), title = "Second transferred journal"))
        nativeTransferPhase("source_journals_created")
        val now = Clock.System.now()
        notes.create(JournalNote.Text(id(3), now, now, "Text retained through native backup"), id(1))
        links.addContentToJournal(id(3), id(2))
        nativeTransferPhase("source_text_created")
        val unfinishedCapture = File(context.filesDir, "camera-captures/logdate-camera-unfinished.mp4")
        check(unfinishedCapture.parentFile?.mkdirs() == true || unfinishedCapture.parentFile?.isDirectory == true)
        unfinishedCapture.writeBytes("unfinished camera output".encodeToByteArray())
        val image = save("proof.png", "image/png")
        nativeTransferPhase("source_image_saved")
        val audio = save("proof.wav", "audio/wav")
        nativeTransferPhase("source_audio_saved")
        val video = save("proof.mp4", "video/mp4")
        nativeTransferPhase("source_video_saved")
        seedRecordingTransferFixture(context)
        // D2D selects live journal references; an orphan file is deliberately not a selected original.
        val recording = File(context.filesDir, "audio_notes/transfer-recording.m4a").toURI().toString()
        notes.create(
            JournalNote.Audio(uid = id(15), creationTimestamp = now, lastUpdated = now, mediaRef = recording, durationMs = 500L),
            id(2),
        )
        assertEquals(
            GlobalContext.get().get<StoredMediaReferences>().storedReference(recording),
            assertIs<JournalNote.Audio>(notes.getNoteById(id(15))).mediaRef,
        )
        notes.create(JournalNote.Image(id(4), now, now, image, caption = "Original photo"), id(1))
        notes.create(JournalNote.Audio(uid = id(5), creationTimestamp = now, lastUpdated = now, mediaRef = audio, durationMs = 500L), id(2))
        notes.create(JournalNote.Video(id(6), now, now, video, caption = "Original video"), id(1))
        notes.create(JournalNote.Text(id(7), now, now, "Deleted before transfer"), id(1))
        notes.removeById(id(7))
        journals.saveDraft(
            EditorDraft(
                id = id(10),
                blocks =
                    listOf(
                        SerializableTextBlock(id(11), now, content = "Unfinished native-transfer draft"),
                        SerializableImageBlock(id(12), now, uri = image),
                        SerializableAudioBlock(id(13), now, uri = audio),
                        SerializableVideoBlock(id(14), now, uri = video),
                    ),
                selectedJournalIds = listOf(id(1), id(2)),
            ),
        )
        nativeTransferPhase("source_rich_draft_saved")
        if (includePendingCameraDraft) {
            val capture = File(context.filesDir, "camera-captures/pending-camera-transfer.jpg")
            check(capture.parentFile?.mkdirs() == true || capture.parentFile?.isDirectory == true)
            check(capture.createNewFile()) { "Pending camera acceptance original already exists" }
            capture.writeBytes("pending camera original".encodeToByteArray())
            val videoCapture = File(context.filesDir, "camera-captures/pending-camera-transfer.mp4")
            check(videoCapture.createNewFile()) { "Pending camera video acceptance original already exists" }
            videoCapture.writeBytes("pending camera video original".encodeToByteArray())
            AndroidLocalEntryDraftStore(context).saveDraft(
                EntryDraft(
                    id = id(20),
                    notes = emptyList(),
                    createdAt = now,
                    updatedAt = now,
                    pendingMedia =
                        listOf(
                            PendingMediaRecord(
                                blockId = id(21),
                                mediaType = PendingMediaType.CAMERA_PHOTO,
                                createdAt = now,
                                filePath = capture.absolutePath,
                                position = 0,
                                caption = "Pending camera transfer",
                            ),
                            PendingMediaRecord(
                                blockId = id(22),
                                mediaType = PendingMediaType.CAMERA_VIDEO,
                                createdAt = now,
                                filePath = videoCapture.absolutePath,
                                position = 1,
                                caption = "Pending camera video transfer",
                            ),
                        ),
                ),
            )
        }
    }

    private fun seedRecordingTransferFixture(context: android.content.Context) {
        val final = File(context.filesDir, "audio_notes/transfer-recording.m4a")
        check(final.parentFile?.mkdirs() == true || final.parentFile?.isDirectory == true)
        check(final.createNewFile()) { "Recording transfer fixture already exists" }
        final.writeBytes(asset("completed-recording.m4a"))
        val inFlight = CrashSafeRecording.inFlightFile(final, isolateForBackup = true)
        inFlight.writeBytes("unfinished raw recording".encodeToByteArray())
        val remux = CrashSafeRecording.remuxStagingFile(inFlight, final)
        remux.writeBytes("unfinished remux".encodeToByteArray())
        check(remux.readBytes().contentEquals("unfinished remux".encodeToByteArray())) {
            "The source fixture did not seed an interrupted remux"
        }
    }

    suspend fun verifyAndEdit(
        originalMediaIncluded: Boolean = true,
        expectedJournalTitle: String = "Native transfer journal",
    ) {
        verifyFixture(originalMediaIncluded, expectedJournalTitle)
        val now = Clock.System.now()
        if (notes.getNoteById(id(8)) == null) {
            notes.create(JournalNote.Text(id(8), now, now, "Written offline on replacement phone"), id(2))
        }
        assertEquals("Written offline on replacement phone", (notes.getNoteById(id(8)) as? JournalNote.Text)?.content)
    }

    suspend fun verifyFixture(
        originalMediaIncluded: Boolean = true,
        expectedJournalTitle: String = "Native transfer journal",
    ) {
        assertEquals(expectedJournalTitle, journals.getJournalById(id(1))?.title)
        assertEquals("Second transferred journal", journals.getJournalById(id(2))?.title)
        assertEquals("Text retained through native backup", (notes.getNoteById(id(3)) as? JournalNote.Text)?.content)
        assertNull(notes.getNoteById(id(7)), "deleted content reappeared")
        assertTrue(
            syncMetadata.getPendingUploads(EntityType.NOTE).any {
                it.entityId == id(7).toString() && it.operation == PendingOperation.DELETE
            },
            "The transferred deletion must remain queued for the next authenticated sync",
        )
        assertTrue(notes.getAllJournalNoteLinks().containsAll(listOf(id(1) to id(3), id(2) to id(3))))
        val image = assertNotNull(notes.getNoteById(id(4)) as? JournalNote.Image)
        val audio = assertNotNull(notes.getNoteById(id(5)) as? JournalNote.Audio)
        val video = assertNotNull(notes.getNoteById(id(6)) as? JournalNote.Video)
        val recording = assertIs<JournalNote.Audio>(notes.getNoteById(id(15)))
        assertEquals(500L, recording.durationMs)
        assertTrue(links.observeJournalsForContent(id(15)).first().any { it.id == id(2) })
        val draft = assertNotNull(journals.getDraft(id(10)))
        assertEquals(listOf(id(1), id(2)), draft.selectedJournalIds)
        assertEquals("Unfinished native-transfer draft", (draft.blocks[0] as? SerializableTextBlock)?.content)
        val originals =
            listOf(
                image.mediaRef to "proof.png",
                audio.mediaRef to "proof.wav",
                video.mediaRef to "proof.mp4",
                assertNotNull((draft.blocks[1] as? SerializableImageBlock)?.uri) to "proof.png",
                assertNotNull((draft.blocks[2] as? SerializableAudioBlock)?.uri) to "proof.wav",
                assertNotNull((draft.blocks[3] as? SerializableVideoBlock)?.uri) to "proof.mp4",
            )
        if (originalMediaIncluded) {
            originals.forEach { (reference, name) -> verifyMedia(reference, name) }
        } else {
            originals.forEach { (reference, _) ->
                assertFalse(media.exists(reference), "Cloud backup unexpectedly delivered original media")
            }
            verifyMissingOriginalCanWaitForSignIn(image.uid, "image", image.mediaRef, image.mediaRef)
        }
    }

    fun verifyUnfinishedCameraCapture(
        context: android.content.Context,
        inPlace: Boolean,
    ) {
        val capture = File(context.filesDir, "camera-captures/logdate-camera-unfinished.mp4")
        if (inPlace) {
            assertContentEquals(
                "unfinished camera output".encodeToByteArray(),
                capture.readBytes(),
                "In-place restore must retain excluded source data",
            )
        } else {
            assertFalse(capture.exists(), "unfinished CameraX output was transferred")
        }
    }

    suspend fun verifyPartialTransfer(expectedMissingMediaPath: String) {
        assertEquals("Native transfer journal", journals.getJournalById(id(1))?.title)
        assertEquals("Second transferred journal", journals.getJournalById(id(2))?.title)
        assertEquals("Text retained through native backup", (notes.getNoteById(id(3)) as? JournalNote.Text)?.content)
        assertNull(notes.getNoteById(id(7)), "deleted content reappeared")
        require(expectedMissingMediaPath.startsWith("files/media/objects/"))
        val missingReference = LocalMediaRef(MediaCollection.Library, expectedMissingMediaPath.removePrefix("files/media/")).toString()
        val missingFile =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                expectedMissingMediaPath.removePrefix("files/"),
            )
        assertFalse(missingFile.exists(), "The native transport unexpectedly delivered its deliberately omitted original")
        val mediaReferences =
            withTimeout(30_000) {
                var references = readPartialMediaReferences()

                suspend fun settled(): Boolean =
                    partialNativeMediaSettled(
                        references.map { it.first },
                        missingReference,
                        GlobalContext.get().get<MediaFileResolver>(),
                        media::exists,
                    )
                while (!settled()) {
                    delay(50)
                    references = readPartialMediaReferences()
                }
                references
            }
        val unavailable = mediaReferences.filter { (reference, _) -> UnavailableMediaReferences.isUnavailable(reference) }
        val available = mediaReferences.filterNot { (reference, _) -> UnavailableMediaReferences.isUnavailable(reference) }
        assertTrue(unavailable.isNotEmpty(), "The omitted original was not retained as recoverable media")
        assertTrue(available.isNotEmpty(), "The partial restore incorrectly marked every original unavailable")
        unavailable.forEach { (reference, _) ->
            assertNotNull(UnavailableMediaReferences.nativeSource(reference), "Missing original lost its source reference")
        }
        available.forEach { (reference, name) -> verifyMedia(reference, name) }

        val missingNote =
            listOf(
                assertNotNull(notes.getNoteById(id(4)) as? JournalNote.Image).let { Triple(it.uid, "image", it.mediaRef) },
                assertNotNull(notes.getNoteById(id(5)) as? JournalNote.Audio).let { Triple(it.uid, "audio", it.mediaRef) },
                assertNotNull(notes.getNoteById(id(6)) as? JournalNote.Video).let { Triple(it.uid, "video", it.mediaRef) },
            ).first { (_, _, reference) -> UnavailableMediaReferences.isUnavailable(reference) }
        verifyMissingOriginalCanWaitForSignIn(missingNote.first, missingNote.second, missingNote.third, missingNote.third)
    }

    private suspend fun readPartialMediaReferences(): List<Pair<String, String>> {
        val image = assertNotNull(notes.getNoteById(id(4)) as? JournalNote.Image)
        val audio = assertNotNull(notes.getNoteById(id(5)) as? JournalNote.Audio)
        val video = assertNotNull(notes.getNoteById(id(6)) as? JournalNote.Video)
        val draft = assertNotNull(journals.getDraft(id(10)))
        assertEquals("Unfinished native-transfer draft", (draft.blocks[0] as? SerializableTextBlock)?.content)
        return listOf(
            image.mediaRef to "proof.png",
            audio.mediaRef to "proof.wav",
            video.mediaRef to "proof.mp4",
            assertNotNull((draft.blocks[1] as? SerializableImageBlock)?.uri) to "proof.png",
            assertNotNull((draft.blocks[2] as? SerializableAudioBlock)?.uri) to "proof.wav",
            assertNotNull((draft.blocks[3] as? SerializableVideoBlock)?.uri) to "proof.mp4",
        )
    }

    private suspend fun verifyMissingOriginalCanWaitForSignIn(
        entityId: Uuid,
        kind: String,
        sourceReference: String,
        unavailableReference: String,
    ) {
        assertNull(GlobalContext.get().get<SessionStorage>().getSession(), "Native restore unexpectedly recovered sign-in")
        val owner =
            GlobalContext
                .get()
                .get<CanonicalOwnerProvider>()
                .getCanonicalOwnerId()
        val origin =
            GlobalContext
                .get()
                .get<LogDateConfigRepository>()
                .getCurrentBackendUrl()
                .trimEnd('/')
        val result =
            GlobalContext.get().get<AutomaticMediaRecovery>().recover(
                entityId,
                null,
                kind,
                sourceReference,
                unavailableReference,
                UploadScope(owner, origin),
            )
        assertEquals(
            DiagnosticReason.SIGN_IN_REQUIRED,
            assertIs<AutomaticMediaRecoveryResult.Pending>(result).reason,
            "Missing originals must remain pending until their cloud recovery scope is available",
        )
    }

    private suspend fun save(
        name: String,
        type: String,
    ): String {
        val bytes = asset(name)
        return media.saveMedia(MediaPayload(name, type, bytes.size.toLong(), bytes))
    }

    private suspend fun verifyMedia(
        reference: String,
        name: String,
    ) {
        val bytes = media.readMedia(reference).data
        assertContentEquals(asset(name), bytes, "original media bytes changed")
        if (name.endsWith(".png")) {
            assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } else {
            play(bytes, name.substringAfterLast('.'))
        }
    }

    private fun play(
        bytes: ByteArray,
        extension: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("native-playback", ".$extension", context.cacheDir)
        val player = MediaPlayer()
        try {
            file.writeBytes(bytes)
            val completion = CountDownLatch(1)
            player.setOnCompletionListener { completion.countDown() }
            player.setDataSource(file.absolutePath)
            player.prepare()
            assertTrue(player.duration > 0)
            player.start()
            assertTrue(completion.await(20, TimeUnit.SECONDS), "offline playback did not complete")
        } finally {
            player.release()
            file.delete()
        }
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("recovery/$name")
            .use { it.readBytes() }

    private fun id(number: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")
}

private const val MAX_NATIVE_TRANSFER_FIXTURE_DATABASE_BYTES = 24L * 1024 * 1024
