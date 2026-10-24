package app.logdate.client.sync

import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.EntryDraftRecoveryRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.recovery.DownloadInbox

/** Coordinates existing remote, legacy-native and current-editor media recovery in the sync phase. */
internal class SyncMediaRecoveryCoordinator(
    private val downloadInbox: DownloadInbox?,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalRepository: JournalRepository,
    private val mediaManager: MediaManager,
    private val mediaTransfer: SyncMediaTransfer,
    private val mediaSyncRefStore: MediaSyncRefStore,
    private val transactionManager: SyncTransactionManager,
    private val entryDraftRecovery: EntryDraftRecoveryRepository?,
    private val mediaReferences: StoredMediaReferences,
) {
    suspend fun recover(
        accessToken: String,
        entityType: EntityType,
    ) {
        val inbox = downloadInbox ?: return
        when (entityType) {
            EntityType.NOTE -> {
                app.logdate.client.sync.recovery
                    .SyncMediaRecovery(
                        inbox,
                        journalNotesRepository,
                        mediaTransfer,
                        mediaSyncRefStore,
                        transactionManager,
                    ).recover(accessToken)
            }

            EntityType.JOURNAL -> {
                app.logdate.client.sync.recovery
                    .SyncJournalCoverRecovery(
                        inbox,
                        journalRepository,
                        mediaTransfer,
                        mediaSyncRefStore,
                        transactionManager,
                    ).recover(accessToken)
            }

            EntityType.DRAFT -> {
                app.logdate.client.sync.recovery
                    .SyncDraftMediaRecovery(
                        inbox,
                        journalRepository,
                        mediaTransfer,
                        mediaSyncRefStore,
                        transactionManager,
                    ).recover(accessToken)
            }

            else -> {
                Unit
            }
        }
        app.logdate.client.sync.recovery
            .NativeMediaRecovery(
                inbox,
                journalNotesRepository,
                journalRepository,
                mediaManager,
                mediaTransfer,
                mediaSyncRefStore,
                transactionManager,
            ).recover(accessToken, entityType)
        if (entityType == EntityType.DRAFT) {
            entryDraftRecovery?.let {
                app.logdate.client.sync.recovery
                    .NativeEntryDraftMediaRecovery(
                        it,
                        inbox,
                        mediaTransfer,
                        mediaSyncRefStore,
                        mediaReferences,
                    ).recover(accessToken)
            }
        }
    }
}
