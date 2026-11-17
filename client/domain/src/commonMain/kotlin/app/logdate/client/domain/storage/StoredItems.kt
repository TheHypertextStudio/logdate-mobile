package app.logdate.client.domain.storage

import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A kind of stored thing that takes up LogDate Cloud space and can be cleared out. */
enum class StoredKind {
    Photos,
    Videos,
    Recordings,
    WrittenEntries,
}

/**
 * One stored thing: enough to recognise it, and how much room it takes.
 *
 * [sizeBytes] is the size of its file on this device, or of its text for a written entry. It is
 * null when the file is not on this device, since nothing here knows how large it is.
 * [text] is the start of a written entry, or of what was said in a recording.
 */
data class StoredItem(
    val id: Uuid,
    val kind: StoredKind,
    val sizeBytes: Long?,
    val createdAt: Instant,
    val mediaRef: String? = null,
    val text: String? = null,
    val durationMs: Long? = null,
)

/** How large the file behind a stored media reference is, or null when it is not on this device. */
fun interface StoredMediaSizes {
    fun sizeOf(mediaRef: String): Long?
}

/** Lists everything of one kind, largest first. Things of unknown size come last, newest first. */
class ObserveStoredItemsUseCase(
    private val notesRepository: JournalNotesRepository,
    private val mediaSizes: StoredMediaSizes,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    operator fun invoke(kind: StoredKind): Flow<List<StoredItem>> =
        notesRepository.allNotesObserved
            .map { notes -> notes.mapNotNull { it.toStoredItem(kind) }.sortedWith(LARGEST_FIRST) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    private fun JournalNote.toStoredItem(kind: StoredKind): StoredItem? =
        when {
            this is JournalNote.Image && kind == StoredKind.Photos -> {
                StoredItem(uid, kind, mediaSizes.sizeOf(mediaRef), creationTimestamp, mediaRef = mediaRef)
            }

            this is JournalNote.Video && kind == StoredKind.Videos -> {
                StoredItem(uid, kind, mediaSizes.sizeOf(mediaRef), creationTimestamp, mediaRef = mediaRef, durationMs = durationMs)
            }

            this is JournalNote.Audio && kind == StoredKind.Recordings -> {
                StoredItem(
                    id = uid,
                    kind = kind,
                    sizeBytes = mediaSizes.sizeOf(mediaRef),
                    createdAt = creationTimestamp,
                    mediaRef = mediaRef,
                    text = transcription.excerpt(),
                    durationMs = durationMs.takeIf { it > 0 },
                )
            }

            this is JournalNote.Text && kind == StoredKind.WrittenEntries -> {
                StoredItem(uid, kind, content.encodeToByteArray().size.toLong(), creationTimestamp, text = content.excerpt())
            }

            else -> {
                null
            }
        }

    private companion object {
        val LARGEST_FIRST: Comparator<StoredItem> =
            compareByDescending<StoredItem> { it.sizeBytes ?: -1L }.thenByDescending { it.createdAt }

        const val EXCERPT_LENGTH = 160
        val WHITESPACE = Regex("\\s+")

        /** The first words on one line, or null when there are none. */
        fun String.excerpt(): String? = trim().replace(WHITESPACE, " ").take(EXCERPT_LENGTH).takeIf { it.isNotEmpty() }
    }
}

/** What a deletion did: the items that are gone, and the ones that could not be deleted. */
data class StoredItemsDeletion(
    val deleted: List<StoredItem>,
    val failed: List<StoredItem>,
) {
    /** The room the deleted items took on this device. Items of unknown size add nothing. */
    val freedBytes: Long get() = deleted.sumOf { it.sizeBytes ?: 0L }
}

/**
 * Deletes several stored things. Each goes the way a single note does, so the deletion reaches the
 * server on the next sync and the account's storage drops. One failure does not stop the rest.
 */
class DeleteStoredItemsUseCase(
    private val removeNote: RemoveNoteUseCase,
) {
    suspend operator fun invoke(items: List<StoredItem>): StoredItemsDeletion {
        val deleted = mutableListOf<StoredItem>()
        val failed = mutableListOf<StoredItem>()
        for (item in items) {
            try {
                removeNote(item.id)
                deleted += item
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not delete stored item ${item.id}", error)
                failed += item
            }
        }
        return StoredItemsDeletion(deleted, failed)
    }
}
