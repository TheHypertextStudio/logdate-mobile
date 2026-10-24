package app.logdate.client.sync.v2.receipts

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import kotlin.uuid.Uuid

class StaleWatchCapture : IllegalStateException("The watch capture's delivery context changed")

/** A capture writer whose note and outbox changes share one context-validated transaction. */
class WatchCaptureNotes(
    private val delegate: SyncableJournalNotesRepository,
    private val transactions: SyncTransactionManager,
    private val outbox: SyncMetadataService,
    private val isCurrent: suspend () -> Boolean,
    private val onUpdated: () -> Unit,
    private val rememberCapture: suspend (JournalNote) -> Unit,
    private val canStore: suspend (Uuid) -> Boolean = { true },
) : SyncableJournalNotesRepository by delegate {
    override suspend fun getNoteById(noteId: Uuid): JournalNote? {
        requireCurrent()
        val note = delegate.getNoteById(noteId)
        requireCurrent()
        return note
    }

    override suspend fun create(note: JournalNote): Uuid = guarded { createCapture(note) }

    private suspend fun createCapture(note: JournalNote): Uuid {
        if (!canStore(note.uid)) return note.uid
        rememberCapture(note)
        requireCurrent()
        return delegate.create(note)
    }

    override suspend fun createFromSync(note: JournalNote) {
        val updated =
            guarded {
                if (!canStore(note.uid)) return@guarded false
                val existing = getNoteById(note.uid)
                when {
                    existing == null -> {
                        createCapture(note)
                        false
                    }

                    existing.lastUpdated < note.lastUpdated && !outbox.hasPending(EntityType.NOTE, note.uid.toString()) -> {
                        requireCurrent()
                        delegate.deleteFromSync(existing.uid)
                        delegate.createFromSync(note.withServerVersion(existing.syncVersion))
                        outbox.enqueuePending(note.uid.toString(), EntityType.NOTE, PendingOperation.UPDATE)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        if (updated) onUpdated()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        transactions.withTransaction {
            requireCurrent()
            val result = block()
            requireCurrent()
            result
        }

    private suspend fun requireCurrent() {
        if (!isCurrent()) throw StaleWatchCapture()
    }

    private fun JournalNote.withServerVersion(version: Long): JournalNote =
        when (this) {
            is JournalNote.Text -> copy(syncVersion = version)
            is JournalNote.Handwriting -> copy(syncVersion = version)
            is JournalNote.Audio -> copy(syncVersion = version)
            is JournalNote.Image -> copy(syncVersion = version)
            is JournalNote.Video -> copy(syncVersion = version)
        }
}
