package app.logdate.client.e2e

import android.app.job.JobScheduler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.database.NativeJournalSnapshotScope
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.database.verifyNativeJournalDatabaseSnapshot
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.restore.NATIVE_MEDIA_INVENTORY_PATH
import app.logdate.client.device.restore.NATIVE_SOURCE_RECEIPT_PATH
import app.logdate.client.device.restore.NativeJournalReplayCustody
import app.logdate.client.device.restore.verifyNativeJournalMediaInventory
import app.logdate.client.device.restore.verifyNativeJournalSourceReceipt
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.PendingMediaType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The harness selects this on a fresh managed device, before normal startup opens Room. */
internal suspend fun verifyDraftOnlyNativeSource(context: android.content.Context) {
    val database = context.getDatabasePath("logdate")
    assertFalse(database.exists(), "Draft-only source must begin before the first journal open")
    val owner = Uuid.random().toString()
    createAndroidMainDataStore(context).edit { preferences ->
        preferences[stringPreferencesKey("identity.canonical_owner_id")] = owner
        preferences[stringPreferencesKey("backend_url")] = "https://cloud.logdate.app"
    }
    val directory = File(context.filesDir, "user_media").apply { check(mkdirs() || isDirectory) }
    val original = File(directory, "draft-only-original.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    File(directory, "unreferenced-original.png").writeBytes(byteArrayOf(5, 6, 7, 8))
    val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    AndroidLocalEntryDraftStore(context).saveDraft(
        EntryDraft(
            Uuid.random(),
            listOf(JournalNote.Image(creationTimestamp = now, lastUpdated = now, mediaRef = original.toURI().toString())),
            now,
            now,
        ),
    )
    val snapshotClass = Class.forName("app.logdate.client.data.restore.NativeJournalTransferSnapshot")
    val snapshot =
        snapshotClass
            .getDeclaredConstructor(
                android.content.Context::class.java,
            ).apply { isAccessible = true }
            .newInstance(context)
    try {
        snapshotClass.getDeclaredMethod("prepare").apply { isAccessible = true }.invoke(snapshot)
        val current = File(context.filesDir, "native_journal_transfer/current")
        val source = File(current, "database/logdate")
        val preferences = File(current, "files/logdate.preferences_pb")
        val receipt =
            verifyNativeJournalSourceReceipt(
                File(current, NATIVE_SOURCE_RECEIPT_PATH),
                preferences,
                mapOf("database/logdate" to source),
            )
        val key = checkNotNull(PassphraseBackupStore(context).read())
        try {
            assertEquals(
                NativeJournalSnapshotScope(owner, false, "https://cloud.logdate.app", receipt.preferencesSha256),
                verifyNativeJournalDatabaseSnapshot(source, key),
            )
        } finally {
            key.fill(0)
        }
        val inventory =
            verifyNativeJournalMediaInventory(
                File(current, NATIVE_MEDIA_INVENTORY_PATH),
                checkNotNull(receipt.mediaInventorySha256),
            )
        assertNotNull(inventory.entry("files/user_media/draft-only-original.png"))
        assertNull(inventory.entry("files/user_media/unreferenced-original.png"))
        val emitted = mutableListOf<File>()
        snapshotClass
            .getDeclaredMethod("backupSelectedMedia", Function1::class.java)
            .apply { isAccessible = true }
            .invoke(snapshot, { file: File -> emitted += file })
        assertEquals(listOf(original.canonicalFile), emitted.map(File::getCanonicalFile))
    } finally {
        snapshotClass.getDeclaredMethod("finish").apply { isAccessible = true }.invoke(snapshot)
    }
}

internal fun verifyPendingCameraSource(context: android.content.Context) {
    val snapshotClass = Class.forName("app.logdate.client.data.restore.NativeJournalTransferSnapshot")
    val snapshot =
        snapshotClass
            .getDeclaredConstructor(android.content.Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    try {
        snapshotClass.getDeclaredMethod("prepare").apply { isAccessible = true }.invoke(snapshot)
        val current = File(context.filesDir, "native_journal_transfer/current")
        val database = File(current, "database/logdate")
        val preferences = File(current, "files/logdate.preferences_pb")
        val receipt =
            verifyNativeJournalSourceReceipt(
                File(current, NATIVE_SOURCE_RECEIPT_PATH),
                preferences,
                mapOf("database/logdate" to database),
            )
        val sourceDrafts = File(context.filesDir, "datastore/entry_drafts.preferences_pb")
        val pinnedDrafts = File(current, "files/datastore/entry_drafts.preferences_pb")
        assertNotNull(receipt.entryDraftsSha256)
        assertContentEquals(sourceDrafts.readBytes(), pinnedDrafts.readBytes())
        val inventory =
            verifyNativeJournalMediaInventory(
                File(current, NATIVE_MEDIA_INVENTORY_PATH),
                assertNotNull(receipt.mediaInventorySha256),
            )
        val cameraSources =
            listOf(
                "files/camera-captures/pending-camera-transfer.jpg",
                "files/camera-captures/pending-camera-transfer.mp4",
            ).associateWith { path ->
                val source = File(context.filesDir, path.removePrefix("files/"))
                assertNotNull(inventory.entry(path))
                assertFalse(File(current, path).exists(), "Large camera originals must not be staged a second time")
                source to source.readBytes()
            }
        try {
            val emittedBeforeMutation = mutableListOf<File>()
            val video = cameraSources.getValue("files/camera-captures/pending-camera-transfer.mp4").first
            assertFailsWith<InvocationTargetException> {
                snapshotClass
                    .getDeclaredMethod("backupSelectedMedia", Function1::class.java)
                    .apply { isAccessible = true }
                    .invoke(snapshot, { file: File ->
                        emittedBeforeMutation += file
                        if (file.canonicalFile == video.canonicalFile) file.writeText("changed during emission")
                    })
            }
            assertTrue(emittedBeforeMutation.any { it.canonicalFile == video.canonicalFile })
        } finally {
            cameraSources.values.forEach { (source, bytes) -> source.writeBytes(bytes) }
        }
        val emitted = mutableListOf<File>()
        snapshotClass
            .getDeclaredMethod("backupSelectedMedia", Function1::class.java)
            .apply { isAccessible = true }
            .invoke(snapshot, { file: File -> emitted += file })
        cameraSources.values.forEach { (source, _) ->
            assertEquals(1, emitted.count { it.canonicalFile == source.canonicalFile })
        }
    } finally {
        snapshotClass.getDeclaredMethod("finish").apply { isAccessible = true }.invoke(snapshot)
    }
}

internal suspend fun verifyPendingCameraTransferDraft(context: android.content.Context) {
    ensureLogDateApplicationStarted(context.applicationContext as android.app.Application)
    val draftsStore = AndroidLocalEntryDraftStore(context)

    suspend fun custodyDiagnostics(): String {
        val observed = draftsStore.getAllDrafts()
        val retained = NativeJournalReplayCustody(context).sealedPayloads()
        val admittedCount =
            File(context.noBackupFilesDir, "native-journal-staged-sources")
                .listFiles()
                .orEmpty()
                .count { it.isDirectory && File(it, "admitted").isFile }
        val pendingJob = context.getSystemService(JobScheduler::class.java).getPendingJob(0x4e4a5235) != null
        return "cameraDrafts=${observed.count { draft ->
            draft.pendingMedia.any { it.mediaType == PendingMediaType.CAMERA_PHOTO || it.mediaType == PendingMediaType.CAMERA_VIDEO }
        }}, " +
            "retainedFiles=${retained.size}, hasReceipt=${retained.any { it.path.endsWith("native_source_scope.json") }}, " +
            "hasDrafts=${retained.any { it.path.endsWith("entry_drafts.preferences_pb") }}, " +
            "hasInventory=${retained.any { it.path.endsWith("native_media_inventory.json") }}, " +
            "cameraFileCount=${retained.count { it.path.contains("/camera-captures/") }}, " +
            "admittedSourceCount=$admittedCount, retainedReconciliationPending=$pendingJob, " +
            "stageMarker=${File(context.noBackupFilesDir, "native-journal-transfer-staged").exists()}"
    }
    val cameraDrafts =
        withTimeoutOrNull(90_000) {
            delay(10_000)
            Napier.i("Pending camera admission after 10s: ${custodyDiagnostics()}", tag = "NativeBackupTransferProbe")
            while (true) {
                val drafts =
                    draftsStore.getAllDrafts().filter { draft ->
                        draft.pendingMedia.any {
                            it.mediaType == PendingMediaType.CAMERA_PHOTO || it.mediaType == PendingMediaType.CAMERA_VIDEO
                        }
                    }
                if (drafts.size >= 2) return@withTimeoutOrNull drafts
                delay(100)
            }
            error("unreachable")
        } ?: run {
            error(
                "Pending camera draft admission timed out: ${custodyDiagnostics()}",
            )
        }
    val sourceId = Uuid.parse("00000000-0000-0000-0000-000000000020")
    val sourceDraft = assertNotNull(cameraDrafts.singleOrNull { it.id == sourceId }, "Source camera draft was not restored")
    val imported = assertNotNull(cameraDrafts.singleOrNull { it.id != sourceId }, "Pending camera draft was not admitted exactly once")
    assertEquals(2, cameraDrafts.size, "Camera custody must leave one source draft and one imported draft")
    assertEquals(2, imported.pendingMedia.size)
    val expectedContents =
        mapOf(
            PendingMediaType.CAMERA_PHOTO to "pending camera original",
            PendingMediaType.CAMERA_VIDEO to "pending camera video original",
        )
    imported.pendingMedia.forEach { record ->
        val restoredPath = assertNotNull(record.filePath)
        val sourcePath = assertNotNull(sourceDraft.pendingMedia.single { it.mediaType == record.mediaType }.filePath)
        assertNotEquals(sourcePath, restoredPath, "Imported draft retained the source-device path")
        assertTrue(restoredPath.startsWith(File(context.filesDir, "camera-captures").absolutePath))
        val restoredOriginal = File(restoredPath)
        assertTrue(restoredOriginal.isFile, "Recovered camera original is missing")
        assertContentEquals(requireNotNull(expectedContents[record.mediaType]).encodeToByteArray(), restoredOriginal.readBytes())
    }
}
