package app.logdate.client.data.notes.drafts

import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NoteType
import app.logdate.client.repository.journals.PendingMediaRecord
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults
import platform.Foundation.setValue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * iOS implementation of LocalEntryDraftStore using NSUserDefaults.
 */
class IosLocalEntryDraftStore : LocalEntryDraftStore {
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val json = Json { ignoreUnknownKeys = true }
    private val draftsKeyPrefix = "entry_draft_"
    private val draftsIndexKey = "entry_drafts_index"

    /**
     * Serializable version of EntryDraft for storage
     */
    @Serializable
    private data class SerializableEntryDraft(
        val id: String,
        val notes: List<SerializableJournalNote>,
        val createdAt: Long,
        val updatedAt: Long,
        val pendingMedia: List<PendingMediaRecord> = emptyList(),
        val selectedJournalIds: List<String> = emptyList(),
    )

    @Serializable
    private data class SerializableJournalNote(
        val id: String,
        val type: String,
        val content: String,
        val createdAt: Long,
        val lastUpdated: Long? = null,
        val syncVersion: Long = 0,
        val location: NoteLocation? = null,
        val durationMs: Long = 0,
        val caption: String = "",
        val timeZoneId: String? = null,
    )

    private fun EntryDraft.toSerializable(): SerializableEntryDraft =
        SerializableEntryDraft(
            id = id.toString(),
            notes = notes.map { it.toSerializable() },
            createdAt = createdAt.toEpochMilliseconds(),
            updatedAt = updatedAt.toEpochMilliseconds(),
            pendingMedia = pendingMedia,
            selectedJournalIds = selectedJournalIds.map { it.toString() },
        )

    private fun JournalNote.toSerializable(): SerializableJournalNote {
        val noteContent =
            when (this) {
                is JournalNote.Handwriting -> unreadableContent ?: document.encode()
                is JournalNote.Text -> this.content
                is JournalNote.Audio -> this.mediaRef
                is JournalNote.Image -> this.mediaRef
                is JournalNote.Video -> this.mediaRef
            }

        return SerializableJournalNote(
            id = this.uid.toString(),
            type = this.type.toString(),
            content = noteContent,
            createdAt = this.creationTimestamp.toEpochMilliseconds(),
            lastUpdated = this.lastUpdated.toEpochMilliseconds(),
            syncVersion = this.syncVersion,
            location = this.location,
            timeZoneId = this.timeZoneId,
            durationMs = (this as? JournalNote.Audio)?.durationMs ?: 0,
            caption =
                when (this) {
                    is JournalNote.Image -> caption

                    is JournalNote.Video -> caption

                    is JournalNote.Handwriting,
                    is JournalNote.Text,
                    is JournalNote.Audio,
                    -> ""
                },
        )
    }

    private fun SerializableEntryDraft.toDomain(): EntryDraft =
        EntryDraft(
            id = Uuid.parse(id),
            notes = notes.map { it.toDomain() },
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            pendingMedia = pendingMedia,
            selectedJournalIds =
                selectedJournalIds.map(Uuid::parse),
        )

    private fun SerializableJournalNote.toDomain(): JournalNote {
        val noteType = NoteType.valueOf(type)
        val noteId = Uuid.parse(id)

        val timestamp = Instant.fromEpochMilliseconds(createdAt)
        val updatedTimestamp = Instant.fromEpochMilliseconds(lastUpdated ?: createdAt)

        return when (noteType) {
            NoteType.HANDWRITING -> {
                toHandwritingNote(noteId, timestamp, updatedTimestamp)
            }

            NoteType.TEXT -> {
                toTextNote(noteId, timestamp, updatedTimestamp)
            }

            NoteType.AUDIO -> {
                JournalNote.Audio(
                    uid = noteId,
                    creationTimestamp = timestamp,
                    lastUpdated = updatedTimestamp,
                    mediaRef = content,
                    durationMs = durationMs,
                    syncVersion = syncVersion,
                    location = location,
                    timeZoneId = timeZoneId,
                )
            }

            NoteType.IMAGE -> {
                JournalNote.Image(
                    uid = noteId,
                    creationTimestamp = timestamp,
                    lastUpdated = updatedTimestamp,
                    mediaRef = content,
                    caption = caption,
                    syncVersion = syncVersion,
                    location = location,
                    timeZoneId = timeZoneId,
                )
            }

            NoteType.VIDEO -> {
                JournalNote.Video(
                    uid = noteId,
                    creationTimestamp = timestamp,
                    lastUpdated = updatedTimestamp,
                    mediaRef = content,
                    caption = caption,
                    syncVersion = syncVersion,
                    location = location,
                    timeZoneId = timeZoneId,
                )
            }

            NoteType.LOCATION -> {
                throw IllegalArgumentException("A draft cannot contain a LOCATION journal note")
            }
        }
    }

    private fun SerializableJournalNote.toTextNote(
        noteId: Uuid,
        created: Instant,
        updated: Instant,
    ): JournalNote.Text =
        JournalNote.Text(
            uid = noteId,
            creationTimestamp = created,
            lastUpdated = updated,
            content = content,
            syncVersion = syncVersion,
            location = location,
            timeZoneId = timeZoneId,
        )

    private fun SerializableJournalNote.toHandwritingNote(
        noteId: Uuid,
        created: Instant,
        updated: Instant,
    ): JournalNote.Handwriting {
        val decoded =
            runCatching {
                app.logdate.shared.model.HandwritingDocument
                    .decode(content)
            }.getOrNull()
        return JournalNote.Handwriting(
            uid = noteId,
            creationTimestamp = created,
            lastUpdated = updated,
            document =
                decoded ?: app.logdate.shared.model
                    .HandwritingDocument(),
            unreadableContent = if (decoded == null) content else null,
            syncVersion = syncVersion,
            location = location,
            timeZoneId = timeZoneId,
        )
    }

    override suspend fun saveDraft(draft: EntryDraft) {
        val key = draftsKeyPrefix + draft.id.toString()
        val serializedDraft = json.encodeToString(draft.toSerializable())
        // Validate every byte that this operation depends on before changing any stored value.
        if (userDefaults.stringForKey(key) != null) {
            getDraft(draft.id)
        }
        val currentIndex = getDraftIndex()

        userDefaults.setValue(serializedDraft, key)
        if (!currentIndex.contains(draft.id.toString())) {
            val updatedIndex = currentIndex + draft.id.toString()
            userDefaults.setValue(json.encodeToString(updatedIndex), draftsIndexKey)
        }
    }

    private fun getDraftIndex(): List<String> {
        val indexJson = userDefaults.stringForKey(draftsIndexKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(indexJson).also { ids ->
                ids.forEach(Uuid::parse)
            }
        } catch (e: Exception) {
            Napier.e("Failed to read the local draft index", e)
            throw EntryDraftStorageException("The local draft index is unreadable", e)
        }
    }

    override suspend fun getDraft(id: Uuid): EntryDraft? {
        val key = draftsKeyPrefix + id.toString()
        val serialized = userDefaults.stringForKey(key) ?: return null

        return try {
            val serializedDraft = json.decodeFromString<SerializableEntryDraft>(serialized)
            serializedDraft.toDomain()
        } catch (e: Exception) {
            Napier.e("Failed to read local draft $id", e)
            throw EntryDraftStorageException("Local draft $id is unreadable", e)
        }
    }

    override suspend fun getAllDrafts(): List<EntryDraft> {
        val draftIds = getDraftIndex()
        return draftIds.map { encodedId ->
            val id =
                try {
                    Uuid.parse(encodedId)
                } catch (e: IllegalArgumentException) {
                    Napier.e("Invalid draft identity in the local index", e)
                    throw EntryDraftStorageException("The local draft index contains an invalid identity", e)
                }
            getDraft(id)
                ?: throw EntryDraftStorageException("Indexed local draft $id is missing")
        }
    }

    override suspend fun deleteDraft(id: Uuid): Boolean {
        val key = draftsKeyPrefix + id.toString()
        val exists = userDefaults.stringForKey(key) != null
        // A corrupt index must not turn a failed delete into data loss.
        val currentIndex = getDraftIndex()

        if (exists) {
            userDefaults.removeObjectForKey(key)

            val updatedIndex = currentIndex.filter { it != id.toString() }
            userDefaults.setValue(json.encodeToString(updatedIndex), draftsIndexKey)
        }

        return exists
    }

    override suspend fun clearAllDrafts() {
        val draftIds = getDraftIndex()

        draftIds.forEach { id ->
            userDefaults.removeObjectForKey(draftsKeyPrefix + id)
        }

        userDefaults.removeObjectForKey(draftsIndexKey)
    }
}
