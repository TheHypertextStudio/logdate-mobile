package app.logdate.client.e2e

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.logdate.client.domain.export.ExportDraft
import app.logdate.client.domain.export.ExportJournalNoteRelation
import app.logdate.client.domain.export.ExportMediaFile
import app.logdate.client.domain.export.ExportMediaManifest
import app.logdate.client.domain.export.ExportMetadata
import app.logdate.client.domain.export.ExportNote
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.core.restore.RestoreSummary
import app.logdate.feature.core.restore.RestoreWorker
import app.logdate.shared.model.Journal
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** Run seed, repair, and verify in separate instrumentation processes, with force-stop between them. */
@RunWith(AndroidJUnit4::class)
class ArchiveMediaRecoveryE2ETest : ArchiveRecoveryCancellationFixture() {
    @Test
    fun archiveRecoveryAcrossProcessRestart(): Unit =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            require(context.packageName.endsWith(".debug"))
            ensureLogDateApplicationStarted(context.applicationContext as android.app.Application)
            val arguments = InstrumentationRegistry.getArguments()
            arguments.getString("archiveRecoveryFixture")?.let {
                assertFalse(fixture.exists(), "Transferred test IDs must not replace an existing acceptance fixture")
                fixture.writeText(it)
            }
            val mode = arguments.getString("archiveRecoveryMode")
            org.junit.Assume.assumeTrue(
                "Archive recovery runs through its multi-process acceptance harness",
                mode in
                    setOf(
                        "seed",
                        "repair",
                        "automatic",
                        "waiting",
                        "source-waiting",
                        "verify",
                        "cleanup-rejected-fixture",
                        "cancel-running",
                        "paused",
                        "resume-cancelled",
                        "verify-cancelled",
                    ),
            )
            if (mode in setOf("cancel-running", "paused", "resume-cancelled", "verify-cancelled")) {
                require(arguments.getString("archiveCancellationAcceptance") == "true")
            }
            when (mode) {
                "seed" -> {
                    seed(recoverableSource = arguments.getString("archiveCancellationAcceptance") == "true")
                }

                "repair" -> {
                    repair()
                }

                "automatic" -> {
                    automaticRepair()
                }

                "waiting" -> {
                    verifyRetainedWaitingState()
                }

                "source-waiting" -> {
                    verifyRetainedWaitingState(settleSourceWork = true)
                }

                "verify" -> {
                    verify()
                }

                "cleanup-rejected-fixture" -> {
                    cleanupRejectedFixture()
                }

                "cancel-running" -> {
                    cancelRunningRecovery()
                }

                "paused" -> {
                    verifyCancelledAfterRestart()
                }

                "resume-cancelled" -> {
                    resumeCancelledRecovery()
                }

                "verify-cancelled" -> {
                    verifyCancelledCompletion()
                    verify()
                }

                else -> {
                    error("Choose seed, repair, or verify for the owned emulator")
                }
            }
        }

    private fun cleanupRejectedFixture() {
        val id = Uuid.parse(requireNotNull(InstrumentationRegistry.getArguments().getString("rejectedFixtureId"))).toString()
        val recordFile = context.filesDir.resolve("archive_recovery/records/$id.json")
        if (!recordFile.exists()) return
        val record = JSONObject(recordFile.readText())
        require(!record.optBoolean("metadataApplied") && (record.optJSONArray("pending")?.length() ?: 0) == 0)
        val generation = record.optString("sourceGeneration").takeIf { it.isNotBlank() }
        val source = context.filesDir.resolve("archive_recovery/sources/$id${generation?.let { ".$it" }.orEmpty()}.zip")
        java.util.zip.ZipFile(source).use { zip ->
            val metadata =
                JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("metadata.json"))).bufferedReader().use { it.readText() })
            require(metadata.getString("userId") == "synthetic-recovery" && metadata.optString("version", "2.0").startsWith("2."))
            require(zip.getEntry("manifest.json") == null && zip.getEntry("notes.json") != null)
        }
        androidx.work.WorkManager
            .getInstance(context)
            .cancelUniqueWork("archive_metadata_recovery_$id")
            .result
            .get()
        check(source.delete())
        check(recordFile.delete())
    }

    private suspend fun seed(recoverableSource: Boolean = false) {
        assertFalse(fixture.exists(), "Use a new acceptance fixture, preserving existing emulator content")
        val state =
            JSONObject().apply {
                listOf("note", "draft", "video", "text", "journal", "extraJournal").forEach { put(it, Uuid.random().toString()) }
            }
        val archive = archive(state, complete = recoverableSource)
        val bindings = RestoreWorkerTestBindings()
        val original = media
        val unavailable =
            object : MediaManager by original {
                override suspend fun saveMediaFromFile(
                    sourceFilePath: String,
                    fileName: String,
                    mimeType: String,
                ): String {
                    if (fileName.startsWith("replacement-")) throw IOException("Synthetic original save interrupted")
                    return original.saveMediaFromFile(sourceFilePath, fileName, mimeType)
                }
            }
        val summary =
            try {
                if (recoverableSource) loadKoinModules(module { single<MediaManager> { unavailable } })
                summary(worker(archive).doWork())
            } finally {
                bindings.restore()
            }
        assertEquals(2, summary.unavailableOriginals)
        assertEquals(0, summary.unavailablePreviews)
        assertEquals(1, summary.notesImported)
        assertEquals(1, summary.draftsImported)
        assertEquals(1, summary.mediaImported)
        state.put("recoveryId", requireNotNull(summary.recoveryId))
        state.put("originalSource", archive.absolutePath)
        val noteId = state.id("note")
        val originalNote = assertIs<JournalNote.Image>(notes.getNoteById(noteId))
        assertTrue(originalNote.mediaRef.startsWith("logdate-unavailable://archive/${summary.recoveryId}/"))
        assertTrue(
            notes.compareAndSetNoteForRestore(
                originalNote,
                originalNote.copy(caption = "Edited after partial recovery", lastUpdated = Clock.System.now()),
                notes.getNoteRecoveryScope(),
            ),
        )
        val draft = assertNotNull(journals.getDraftForRestore(state.id("draft")))
        val video = assertIs<SerializableVideoBlock>(draft.blocks[1])
        assertTrue(assertNotNull(video.uri).startsWith("logdate-unavailable://archive/${summary.recoveryId}/"))
        val thumbnail = assertNotNull(video.thumbnailUri)
        assertContentEquals(asset("proof.png"), media.readMedia(thumbnail).data)
        state.put("thumbnail", thumbnail)
        journals.saveDraft(
            draft.copy(
                blocks =
                    listOf(
                        video.copy(caption = "Edited video caption"),
                        SerializableTextBlock(state.id("text"), Clock.System.now(), content = "New text after recovery"),
                    ),
            ),
        )
        journals.create(Journal(state.id("extraJournal"), "Recovery membership added on replacement"))
        links.addContentToJournal(noteId, state.id("extraJournal"))
        fixture.writeText(state.toString())
        assertTrue(
            context.filesDir
                .resolve("archive_recovery/sources")
                .listFiles()
                .orEmpty()
                .any { it.name.startsWith("${summary.recoveryId}.") },
        )
        archive.delete()
    }

    private suspend fun verifyRetainedWaitingState(settleSourceWork: Boolean = false) {
        require(koin.get<app.logdate.client.datastore.SessionStorage>().getSession() == null)
        verifyRetainedArchiveWaitingState(
            context,
            JSONObject(fixture.readText()),
            notes,
            journals,
            settleSourceWork,
            ::verifyEdits,
        )
    }

    private suspend fun automaticRepair() {
        val state = JSONObject(fixture.readText())
        val complete = archive(state, complete = true)
        try {
            val work =
                androidx.work
                    .OneTimeWorkRequestBuilder<RestoreWorker>()
                    .setInputData(
                        Data
                            .Builder()
                            .putString(RestoreWorker.RECOVERY_ID_KEY, state.getString("recoveryId"))
                            .putString(RestoreWorker.SOURCE_URI_KEY, Uri.fromFile(complete).toString())
                            .putBoolean(RestoreWorker.REPLACEMENT_SOURCE_KEY, true)
                            .build(),
                    ).build()
            val manager = androidx.work.WorkManager.getInstance(context)
            manager
                .enqueueUniqueWork(
                    "archive_metadata_recovery_${state.getString("recoveryId")}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    work,
                ).result
                .get()
            kotlinx.coroutines.withTimeout(30_000) {
                while (manager
                        .getWorkInfoById(work.id)
                        .get()
                        ?.state
                        ?.isFinished != true
                ) {
                    kotlinx.coroutines.delay(100)
                }
            }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(work.id).get()?.state)
            verifyEdits(state)
            verifyOriginals(state)
        } finally {
            complete.delete()
        }
    }

    private suspend fun repair() {
        val state = JSONObject(fixture.readText())
        verifyEdits(state)
        // The original picker file no longer exists; the same request must use its private source.
        val resumed = worker(File(state.getString("originalSource")), state.getString("recoveryId"))
        val resumedSummary = summary(resumed.doWork())
        assertEquals(2, resumedSummary.unavailableOriginals)
        verifyEdits(state)
        val replacement = archive(state, complete = true)
        try {
            val repaired = summary(worker(replacement, state.getString("recoveryId"), replacement = true).doWork())
            assertEquals(0, repaired.unavailableOriginals)
            assertEquals(0, repaired.unavailablePreviews)
            assertEquals(3, repaired.mediaImported)
            assertEquals(2, repaired.mediaRecoveryChecked)
            assertEquals(2, repaired.mediaRecoveryTotal)
            verifyEdits(state)
            verifyOriginals(state)
            assertFalse(
                context.filesDir.resolve("archive_recovery/sources").listFiles().orEmpty().any {
                    it.name.startsWith("${repaired.recoveryId}.")
                },
            )
        } finally {
            replacement.delete()
        }
    }

    private suspend fun verify() {
        val state = JSONObject(fixture.readText())
        verifyEdits(state)
        verifyOriginals(state)
        val photoBeforeReplay = assertIs<JournalNote.Image>(notes.getNoteById(state.id("note"))).mediaRef
        val videoBeforeReplay =
            assertNotNull(journals.getDraftForRestore(state.id("draft")))
                .blocks
                .filterIsInstance<SerializableVideoBlock>()
                .single()
                .uri
        val mediaObjectsBeforeReplay = mediaObjectPaths()
        val recordedMediaImported =
            JSONObject(context.filesDir.resolve("archive_recovery/records/${state.getString("recoveryId")}.json").readText())
                .getJSONObject("summary")
                .getInt("mediaImported")
        val completed = summary(worker(File(state.getString("originalSource")), state.getString("recoveryId")).doWork())
        assertEquals(0, completed.unavailableOriginals)
        assertEquals(recordedMediaImported, completed.mediaImported, "replay must not change the cumulative imported-media count")
        assertEquals(photoBeforeReplay, assertIs<JournalNote.Image>(notes.getNoteById(state.id("note"))).mediaRef)
        assertEquals(
            videoBeforeReplay,
            assertNotNull(journals.getDraftForRestore(state.id("draft")))
                .blocks
                .filterIsInstance<SerializableVideoBlock>()
                .single()
                .uri,
        )
        assertEquals(mediaObjectsBeforeReplay, mediaObjectPaths(), "replay must not create additional stored media objects")
        val record = JSONObject(context.filesDir.resolve("archive_recovery/records/${state.getString("recoveryId")}.json").readText())
        assertTrue(record.getBoolean("metadataApplied"))
        assertEquals(0, record.optJSONArray("pending")?.length() ?: 0)
    }

    private fun mediaObjectPaths(): Set<String> =
        context.filesDir
            .resolve("media/objects/sha256")
            .walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(context.filesDir).invariantSeparatorsPath }
            .toSet()

    override suspend fun verifyEdits(state: JSONObject) {
        val note = assertIs<JournalNote.Image>(notes.getNoteById(state.id("note")))
        assertEquals("Edited after partial recovery", note.caption)
        val draft = assertNotNull(journals.getDraftForRestore(state.id("draft")))
        assertEquals(listOf(state.id("video"), state.id("text")), draft.blocks.map { it.id })
        val video = assertIs<SerializableVideoBlock>(draft.blocks.first())
        assertEquals("Edited video caption", video.caption)
        val thumbnail = assertNotNull(video.thumbnailUri)
        val references = koin.get<StoredMediaReferences>()
        assertEquals(references.storedReference(state.getString("thumbnail")), references.storedReference(thumbnail))
        assertTrue(media.exists(thumbnail), "The retained preview must resolve on this installation")
        assertContentEquals(asset("proof.png"), media.readMedia(thumbnail).data)
        assertEquals("New text after recovery", assertIs<SerializableTextBlock>(draft.blocks[1]).content)
        val memberships =
            links
                .observeJournalsForContent(state.id("note"))
                .first()
                .map { it.id }
                .toSet()
        assertEquals(setOf(state.id("journal"), state.id("extraJournal")), memberships)
    }

    override suspend fun verifyOriginals(state: JSONObject) {
        val photo = assertIs<JournalNote.Image>(notes.getNoteById(state.id("note")))
        val bytes = media.readMedia(photo.mediaRef).data
        assertContentEquals(asset("proof.png"), bytes)
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val draft = assertNotNull(journals.getDraftForRestore(state.id("draft")))
        val video = assertIs<SerializableVideoBlock>(draft.blocks.first())
        val videoBytes = media.readMedia(assertNotNull(video.uri)).data
        assertContentEquals(asset("proof.mp4"), videoBytes)
        val file = File.createTempFile("archive-recovered-video-", ".mp4", context.cacheDir)
        val player = MediaPlayer()
        try {
            file.writeBytes(videoBytes)
            val finished = CountDownLatch(1)
            player.setOnCompletionListener { finished.countDown() }
            player.setDataSource(file.absolutePath)
            player.prepare()
            assertTrue(player.duration > 0)
            player.start()
            assertTrue(finished.await(20, TimeUnit.SECONDS))
        } finally {
            player.release()
            file.delete()
        }
    }

    private fun worker(
        archive: File,
        recoveryId: String? = null,
        replacement: Boolean = false,
    ): RestoreWorker =
        TestListenableWorkerBuilder<RestoreWorker>(context)
            .setInputData(
                Data
                    .Builder()
                    .putString(RestoreWorker.SOURCE_URI_KEY, Uri.fromFile(archive).toString())
                    .putString(RestoreWorker.RECOVERY_ID_KEY, recoveryId)
                    .putBoolean(RestoreWorker.REPLACEMENT_SOURCE_KEY, replacement)
                    .putBoolean(RestoreWorker.DELETE_SOURCE_AFTER_RESTORE_KEY, recoveryId != null && !replacement)
                    .build(),
            ).build()

    private fun summary(result: ListenableWorker.Result): RestoreSummary =
        json.decodeFromString(
            assertNotNull(
                assertIs<ListenableWorker.Result.Success>(
                    result,
                    "Worker result: $result",
                ).outputData.getString(RestoreWorker.SUMMARY_JSON_KEY),
            ),
        )

    private fun archive(
        state: JSONObject,
        complete: Boolean,
    ): File {
        val now = Clock.System.now() - 2.days
        val note =
            ExportNote(
                state.getString("note"),
                "image",
                mediaPath = "old/photo.png",
                caption = "Archive caption",
                createdAt = now,
                updatedAt = now,
            )
        val video =
            SerializableVideoBlock(
                state.id("video"),
                now,
                uri = "old/video.mp4",
                thumbnailUri = "old/preview.png",
                caption = "Archive video",
            )
        val text = SerializableTextBlock(state.id("text"), now, content = "Archive text")
        val draft = ExportDraft(state.getString("draft"), content = "", createdAt = now, updatedAt = now, blocks = listOf(text, video))
        val manifest =
            ExportMediaManifest(
                listOf(
                    ExportMediaFile(
                        sourceUri = "old/photo.png",
                        exportPath = if (complete) "media/replacement-photo.png" else "media/absent-photo.png",
                    ),
                    ExportMediaFile(
                        sourceUri = "old/video.mp4",
                        exportPath = if (complete) "media/replacement-video.mp4" else "media/absent-video.mp4",
                    ),
                    ExportMediaFile(sourceUri = "old/preview.png", exportPath = "media/preview.png"),
                ),
            )
        val metadata =
            ExportMetadata(
                version = ExportSchemaVersion.V1_2,
                exportDate = now,
                userId = "synthetic-recovery",
                deviceId = "synthetic-source",
                appVersion = "1.0",
                stats = ExportStats(1, 1, 1, 3),
            )
        val files =
            mapOf(
                "metadata.json" to json.encodeToString(metadata),
                "journals.json" to json.encodeToString(mapOf("journals" to listOf(Journal(state.id("journal"), "Archive media recovery")))),
                "notes.json" to json.encodeToString(mapOf("notes" to listOf(note))),
                "drafts.json" to json.encodeToString(mapOf("drafts" to listOf(draft))),
                "journal_notes.json" to
                    json.encodeToString(
                        mapOf(
                            "journal_notes" to listOf(ExportJournalNoteRelation(state.getString("journal"), state.getString("note"), now)),
                        ),
                    ),
                "media_manifest.json" to json.encodeToString(manifest),
            )
        return File.createTempFile("archive-media-recovery-", ".zip", context.cacheDir).also { target ->
            ZipOutputStream(target.outputStream()).use { zip ->
                files.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.encodeToByteArray())
                    zip.closeEntry()
                }
                val attachments = mutableMapOf("media/preview.png" to asset("proof.png"))
                if (complete) {
                    attachments["media/replacement-photo.png"] = asset("proof.png")
                    attachments["media/replacement-video.mp4"] =
                        asset("proof.mp4")
                }
                attachments.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    override fun asset(name: String): ByteArray =
        instrumentation.context.assets
            .open("recovery/$name")
            .use { it.readBytes() }
}
