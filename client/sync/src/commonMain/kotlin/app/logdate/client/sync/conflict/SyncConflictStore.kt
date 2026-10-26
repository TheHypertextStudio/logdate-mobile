package app.logdate.client.sync.conflict

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.Journal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncConflictRecord(
    val id: String,
    val entityType: String,
    val entityId: String,
    val localVersion: Long?,
    val remoteVersion: Long?,
    val localUpdatedAt: Long?,
    val remoteUpdatedAt: Long?,
    val reason: String,
    val detectedAt: Long,
    val scope: UploadScope? = null,
    val deletedEdit: DeletedEdit? = null,
    val recoveryId: String? = null,
    val recovered: Boolean = false,
    val recoveryNoteIds: Map<String, String> = emptyMap(),
)

@Serializable
data class DeletedEdit(
    val note: JournalNote? = null,
    val journal: Journal? = null,
    val journalIds: List<String> = emptyList(),
    val notes: List<JournalNote> = emptyList(),
    val draft: app.logdate.shared.model.EditorDraft? = null,
)

interface SyncConflictStore {
    suspend fun list(): List<SyncConflictRecord>

    suspend fun add(record: SyncConflictRecord)

    suspend fun remove(id: String)

    suspend fun clear()
}

class KeyValueSyncConflictStore(
    private val storage: KeyValueStorage,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SyncConflictStore {
    private val mutex = Mutex()

    override suspend fun list(): List<SyncConflictRecord> = mutex.withLock { readAll() }

    override suspend fun add(record: SyncConflictRecord) =
        mutex.withLock {
            val updated = readAll().filterNot { it.id == record.id } + record
            persist(updated)
        }

    override suspend fun remove(id: String) =
        mutex.withLock {
            persist(readAll().filterNot { it.id == id })
        }

    override suspend fun clear() = mutex.withLock { storage.remove(CONFLICT_KEY) }

    private suspend fun persist(records: List<SyncConflictRecord>) {
        val encoded = json.encodeToString(records)
        try {
            storage.putString(CONFLICT_KEY, encoded)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw app.logdate.client.sync
                .SyncStorageUnavailableException()
        }
    }

    private suspend fun readAll(): List<SyncConflictRecord> {
        val raw = storage.getString(CONFLICT_KEY) ?: return emptyList()
        // An undecodable store may contain the only surviving edit. Never overwrite it.
        return json.decodeFromString<List<SyncConflictRecord>>(raw)
    }

    private companion object {
        const val CONFLICT_KEY = "sync_conflict_queue"
    }
}

/** References held by recovery must survive ordinary note/draft cleanup, across accounts. */
suspend fun SyncConflictStore.retainedMediaReferences(): Set<String> =
    list()
        .flatMap { record ->
            val edit = record.deletedEdit ?: return@flatMap emptyList()
            listOfNotNull(edit.note?.let { it.mediaRefOrNull() }, edit.journal?.coverImageUri) +
                edit.notes.mapNotNull { it.mediaRefOrNull() } +
                edit.draft?.blocks.orEmpty().flatMap { block ->
                    when (block) {
                        is app.logdate.shared.model.SerializableImageBlock -> listOfNotNull(block.uri)
                        is app.logdate.shared.model.SerializableVideoBlock -> listOfNotNull(block.uri, block.thumbnailUri)
                        is app.logdate.shared.model.SerializableAudioBlock -> listOfNotNull(block.uri)
                        is app.logdate.shared.model.SerializableCameraBlock -> listOfNotNull(block.uri)
                        else -> emptyList()
                    }
                }
        }.toSet()
