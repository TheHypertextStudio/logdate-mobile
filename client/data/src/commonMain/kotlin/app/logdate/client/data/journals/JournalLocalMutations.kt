package app.logdate.client.data.journals

import app.logdate.client.database.dao.JournalDao
import app.logdate.client.media.storage.MediaReclamationJournal
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.Journal
import kotlin.uuid.Uuid

internal class JournalLocalMutations(
    private val journalDao: JournalDao,
    private val metadata: SyncMetadataService,
    private val transactions: SyncTransactionManager?,
    private val currentScope: suspend () -> JournalMergeScope,
    private val resolve: suspend (Uuid) -> Uuid,
    private val changed: () -> Unit,
    private val receipts: ArchiveRecoveryReceipts,
    private val mediaReclamation: MediaReclamationJournal = MediaReclamationJournal.None,
) {
    suspend fun recoveryScope(): JournalMergeScope? = currentScope().takeIf { it.ownerId.isNotBlank() && it.serverOrigin.isNotBlank() }

    private suspend fun <T> transaction(block: suspend () -> T): T =
        if (transactions ==
            null
        ) {
            block()
        } else {
            transactions.withTransaction(block)
        }

    suspend fun create(journal: Journal): Uuid {
        transaction {
            val scope = currentScope()
            check(resolve(journal.id) == journal.id) { "Journal was merged" }
            journalDao.create(journal.toEntity())
            metadata.enqueuePending(journal.id.toString(), EntityType.JOURNAL, PendingOperation.CREATE)
            receipts.clearLocalDeletion(scope, LocalDeletionType.JOURNAL, journal.id.toString())
            check(currentScope() == scope) { "Journal creation account changed" }
        }
        changed()
        return journal.id
    }

    suspend fun update(journal: Journal) {
        transaction {
            if (resolve(journal.id) != journal.id) return@transaction
            if (mediaReclamation !== MediaReclamationJournal.None) {
                journalDao
                    .getJournalById(journal.id)
                    ?.coverImageUri
                    ?.takeIf { it != journal.coverImageUri }
                    ?.let { recordCoverCandidate(it) }
            }
            journalDao.update(journal.toEntity())
            metadata.enqueuePending(journal.id.toString(), EntityType.JOURNAL, PendingOperation.UPDATE)
        }
        changed()
    }

    suspend fun delete(id: Uuid) {
        transaction {
            val scope = currentScope()
            if (resolve(id) != id) return@transaction
            if (mediaReclamation !== MediaReclamationJournal.None) {
                journalDao.getJournalById(id)?.coverImageUri?.let { recordCoverCandidate(it) }
            }
            receipts.recordLocalDeletion(scope, LocalDeletionType.JOURNAL, id.toString())
            journalDao.delete(id)
            metadata.enqueuePending(id.toString(), EntityType.JOURNAL, PendingOperation.DELETE)
            check(currentScope() == scope) { "Journal deletion account changed" }
        }
        changed()
    }

    suspend fun restore(
        expected: Journal?,
        replacement: Journal,
        scope: JournalMergeScope?,
        recoveryId: String?,
        retainedSource: Boolean = false,
        sourceId: Uuid = replacement.id,
    ): Boolean {
        val saved =
            transaction {
                check(recoveryScope() == scope) { "Journal recovery account changed" }
                val journalId = replacement.id.toString()
                if (scope != null && recoveryId != null && receipts.contains(scope, "JOURNAL", recoveryId, journalId)) {
                    return@transaction false
                }
                val deletedHere =
                    retainedSource &&
                        scope != null &&
                        (
                            receipts.containsLocalDeletion(scope, LocalDeletionType.JOURNAL, sourceId.toString()) ||
                                receipts.containsLocalDeletion(scope, LocalDeletionType.JOURNAL, journalId)
                        )
                when {
                    deletedHere -> {
                        return@transaction finishRecovery(scope, recoveryId, journalId, false)
                    }

                    resolve(replacement.id) != replacement.id || journalDao.getJournalById(replacement.id)?.toModel() != expected -> {
                        return@transaction finishRecovery(scope, recoveryId, journalId, false)
                    }

                    expected == replacement -> {
                        return@transaction finishRecovery(scope, recoveryId, journalId, true)
                    }
                }
                val operation = if (expected == null) PendingOperation.CREATE else PendingOperation.UPDATE
                val uploadScope = scope?.let { UploadScope(it.ownerId, it.serverOrigin) }
                if (!metadata.enqueueRestoreMutation(journalId, EntityType.JOURNAL, operation, uploadScope)) {
                    return@transaction finishRecovery(scope, recoveryId, journalId, false)
                }
                if (!retainedSource && expected == null && scope != null) {
                    receipts.clearLocalDeletion(scope, LocalDeletionType.JOURNAL, journalId)
                }
                if (mediaReclamation !== MediaReclamationJournal.None) {
                    expected
                        ?.coverImageUri
                        ?.takeIf { it != replacement.coverImageUri }
                        ?.let { recordCoverCandidate(it) }
                }
                if (expected == null) journalDao.create(replacement.toEntity()) else journalDao.update(replacement.toEntity())
                finishRecovery(scope, recoveryId, journalId, true)
            }
        if (saved) changed()
        return saved
    }

    /** Records the recovery receipt, confirms the account didn't change, and returns [result]. */
    private suspend fun finishRecovery(
        scope: JournalMergeScope?,
        recoveryId: String?,
        journalId: String,
        result: Boolean,
    ): Boolean {
        if (scope != null && recoveryId != null) receipts.record(scope, "JOURNAL", recoveryId, journalId)
        check(recoveryScope() == scope) { "Journal recovery account changed" }
        return result
    }

    private suspend fun recordCoverCandidate(reference: String) {
        if (MediaReference.parse(reference) !is MediaReference.External) mediaReclamation.recordCandidate(reference)
    }
}
