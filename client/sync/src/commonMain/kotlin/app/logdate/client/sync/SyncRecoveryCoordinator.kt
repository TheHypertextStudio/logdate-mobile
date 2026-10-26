package app.logdate.client.sync

import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.FirstSyncEnqueueStore
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox

/** Coordinates inventory and attachment repair independently of scheduling and upload state. */
internal class SyncRecoveryCoordinator(
    private val downloadInbox: DownloadInbox?,
    private val firstSyncEnqueueStore: FirstSyncEnqueueStore,
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val syncMetadataService: SyncMetadataService,
    private val identityKeyManager: IdentityKeyManager?,
    private val mediaTransfer: SyncMediaTransfer,
    private val mediaSyncRefStore: MediaSyncRefStore,
    private val transactionManager: SyncTransactionManager,
) {
    internal val legacyBackfill =
        LegacyLocalBackfill(
            downloadInbox,
            firstSyncEnqueueStore,
            journalRepository,
            journalNotesRepository,
            journalContentRepository,
            syncMetadataService,
        )

    internal suspend fun auditLegacyRecordsIfNeeded() {
        val inbox = downloadInbox ?: return
        if (identityKeyManager?.hasIdentityKey() != true) return
        val selected = inbox.currentScope()
        if (firstSyncEnqueueStore.hasAuditedLegacyScope(selected.owner, selected.origin)) return
        inbox.rewindForLegacyAudit(selected)
        check(selected == inbox.currentScope()) { "Download scope changed" }
        firstSyncEnqueueStore.markAuditedLegacyScope(selected.owner, selected.origin)
    }

    internal suspend fun recoverMedia(
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
    }
}
