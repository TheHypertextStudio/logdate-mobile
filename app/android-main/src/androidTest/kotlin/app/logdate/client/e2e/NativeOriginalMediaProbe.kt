package app.logdate.client.e2e

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.media.storage.isRecoveredOriginal
import app.logdate.client.sync.AndroidLogDateSyncWorker
import app.logdate.client.sync.BackupRequestState
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.UploadScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.Koin
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Shared durable media oracle; verification never enqueues another recovery request. */
internal class NativeOriginalMediaProbe(
    private val context: Context,
    private val koin: Koin,
    private val reportFailureState: suspend (UploadScope) -> Unit = {},
) {
    private val database get() = koin.get<LogDateDatabase>()
    private val media get() = koin.get<MediaManager>()
    private val noteId = Uuid.parse("00000000-0000-0000-0000-000000000004")

    suspend fun appliedOriginal(
        marker: File,
        scope: UploadScope,
    ): Boolean {
        val checkpoint = JSONObject(marker.readText())
        val row = database.downloadInboxDao().get(scope.ownerId, scope.serverOrigin, "MEDIA_NATIVE_NOTE", noteId.toString())
        if (row?.operationId != checkpoint.getString("operationId") || row.state != "APPLIED") return false
        val note = database.imageNoteDao().getNoteOneOff(noteId)
        return media.isRecoveredOriginal(note.contentUri, koin.get<StoredMediaReferences>()) &&
            digest(media.readMedia(note.contentUri).data) == checkpoint.getString("sha256")
    }

    suspend fun prepareMissingOriginal(
        marker: File,
        scope: UploadScope,
    ) {
        require(!marker.exists()) { "Do not replace an earlier synthetic recovery checkpoint" }
        val note = database.imageNoteDao().getNoteOneOff(noteId)
        assertTrue(media.isRecoveredOriginal(note.contentUri, koin.get<StoredMediaReferences>()))
        val expectedDigest = digest(media.readMedia(note.contentUri).data)
        val refs = koin.get<MediaSyncRefStore>()
        val remote = requireNotNull(refs.getScoped(noteId, scope)) { "The original must already exist on the real fixture server" }
        val missing = File(context.filesDir, "native-media-acceptance-missing/${Uuid.random()}.png")
        assertFalse(missing.exists())
        val source = "file://${missing.absolutePath}"
        val unavailable = UnavailableMediaReferences.nativeRestore(source)
        val prior =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                .first()
        writeCheckpoint(
            marker,
            JSONObject()
                .put("owner", scope.ownerId)
                .put("origin", scope.serverOrigin)
                .put("id", noteId.toString())
                .put("missing", unavailable)
                .put("sha256", expectedDigest)
                .put("created", note.created.toEpochMilliseconds())
                .put("updated", note.lastUpdated.toEpochMilliseconds())
                .put("priorWorkIds", JSONArray(prior.map { it.id.toString() })),
        )
        // Preserve the original file. Only this synthetic note's mapping is made unavailable;
        // successful recovery must fetch its original through the existing server media record.
        // Later draft fixtures still hold earlier references to this same original. Keep their
        // provenance while the current nonexistent URI continues to require a real download.
        refs.upsert(
            remote.copy(
                localUri = source,
                localAliases =
                    (remote.localAliases + remote.localUri)
                        .asReversed()
                        .distinct()
                        .take(8)
                        .asReversed(),
            ),
        )
        assertFalse(media.exists(source))
        database.imageNoteDao().updateContentUri(noteId, unavailable)
    }

    suspend fun retainDeferredOperation(
        marker: File,
        scope: UploadScope,
    ) {
        val checkpoint = JSONObject(marker.readText())
        val row =
            withTimeout(90_000) {
                while (true) {
                    val current = database.downloadInboxDao().get(scope.ownerId, scope.serverOrigin, "MEDIA_NATIVE_NOTE", noteId.toString())
                    if (current != null && current.payload == checkpoint.getString("missing") && current.reason == "NETWORK_POLICY") {
                        assertNotEquals("APPLIED", current.state)
                        return@withTimeout current
                    }
                    delay(50)
                }
                error("Unreachable")
            }
        withTimeout(10_000) {
            while (true) {
                val status = koin.get<SyncManager>().getSyncStatus()
                if (status.pendingDownloads > 0 && status.requestState != BackupRequestState.COMPLETED) break
                delay(25)
            }
        }
        checkpoint.put("operationId", row.operationId)
        writeCheckpoint(marker, checkpoint)
        assertEquals(checkpoint.getString("missing"), database.imageNoteDao().getNoteOneOff(noteId).contentUri)
    }

    suspend fun verifyAutomaticOriginal(
        marker: File,
        scope: UploadScope,
    ) {
        require(marker.isFile) { "The previous process must retain its deferred operation" }
        val checkpoint = JSONObject(marker.readText())
        assertEquals(scope.ownerId, checkpoint.getString("owner"))
        assertEquals(scope.serverOrigin, checkpoint.getString("origin"))
        var persistenceFailureReported = false
        val recovered =
            withTimeout(120_000) {
                while (true) {
                    if (!persistenceFailureReported) {
                        val pending =
                            database.downloadInboxDao().get(
                                scope.ownerId,
                                scope.serverOrigin,
                                "MEDIA_NATIVE_NOTE",
                                noteId.toString(),
                            )
                        if (pending?.reason == "LOCAL_STORAGE") {
                            reportFailureState(scope)
                            persistenceFailureReported = true
                        }
                    }
                    val note = database.imageNoteDao().getNoteOneOff(noteId)
                    if (note.contentUri != checkpoint.getString("missing") &&
                        media.isRecoveredOriginal(note.contentUri, koin.get<StoredMediaReferences>())
                    ) {
                        return@withTimeout note
                    }
                    delay(50)
                }
                error("Unreachable")
            }
        assertEquals(checkpoint.getString("sha256"), digest(media.readMedia(recovered.contentUri).data))
        assertEquals(checkpoint.getLong("created"), recovered.created.toEpochMilliseconds())
        assertEquals(checkpoint.getLong("updated"), recovered.lastUpdated.toEpochMilliseconds())
        val row = requireNotNull(database.downloadInboxDao().get(scope.ownerId, scope.serverOrigin, "MEDIA_NATIVE_NOTE", noteId.toString()))
        assertEquals(checkpoint.getString("operationId"), row.operationId)
        assertEquals("APPLIED", row.state)
        val prior = checkpoint.getJSONArray("priorWorkIds").let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
        val completion = checkpoint.optString("completionWorkId")
        withTimeoutReporting({ reportImmediateSyncWork(prior, completion, scope) }) {
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                .first { work ->
                    work.any {
                        (it.id.toString() !in prior || it.id.toString() == checkpoint.optString("completionWorkId")) &&
                            (
                                checkpoint
                                    .optString(
                                        "completionWorkId",
                                    ).isEmpty() ||
                                    it.id.toString() == checkpoint.getString("completionWorkId")
                            ) &&
                            completedMediaForScope(it, scope)
                    } &&
                        work.none { !it.state.isFinished }
                }
        }
        assertEquals(0, koin.get<SyncManager>().getSyncStatus().pendingDownloads)
    }

    fun writeCheckpoint(
        marker: File,
        contents: JSONObject,
    ) {
        val file = AtomicFile(marker)
        val output = file.startWrite()
        try {
            output.write(contents.toString().encodeToByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    /** Runs [block] under the 60s completion deadline, logging the immediate sync chain if it expires. */
    private suspend fun <T> withTimeoutReporting(
        report: suspend () -> Unit,
        block: suspend () -> T,
    ): T =
        try {
            withTimeout(60_000) { block() }
        } catch (timeout: TimeoutCancellationException) {
            runCatching { report() }.onFailure { Log.w(TAG, "Immediate sync state unavailable: ${it.javaClass.name}") }
            throw timeout
        }

    private suspend fun reportImmediateSyncWork(
        prior: Set<String>,
        completion: String,
        scope: UploadScope,
    ) {
        val work =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                .first()
        Log.i(TAG, "immediate_sync_work=${work.size} prior=${prior.size} completion=${completion.ifEmpty { "none" }}")
        work.forEach {
            Log.i(
                TAG,
                "work=${it.id} state=${it.state} attempts=${it.runAttemptCount} prior=${it.id.toString() in prior} " +
                    "completed_media=${completedMediaForScope(it, scope)} output=${it.outputData.keyValueMap.keys.sorted()} " +
                    "tags=${it.tags.filterNot { tag -> tag.startsWith("app.logdate") }.sorted()}",
            )
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private const val TAG = "NativeOriginalMediaProbe"

internal fun completedMediaForScope(
    work: WorkInfo,
    scope: UploadScope,
): Boolean =
    work.state == WorkInfo.State.SUCCEEDED &&
        work.outputData.getString("recovered_media_owner") == scope.ownerId &&
        work.outputData.getString("recovered_media_origin") == scope.serverOrigin
