package app.logdate.client.data.journals

import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalContentRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.util.platformDataDispatcher
import app.logdate.shared.model.Journal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Repository implementation for managing associations between content and journals.
 * Uses local database as source of truth.
 */
class OfflineFirstJournalContentRepository(
    private val journalContentDao: JournalContentDao,
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager = PassthroughJournalContentTransactionManager,
    private val dispatcher: CoroutineDispatcher = platformDataDispatcher,
    private val recoveryReceipts: ArchiveRecoveryReceipts = ArchiveRecoveryReceipts.None,
) : JournalContentRepository,
    SyncableJournalContentRepository {
    override fun observeJournalItemCounts(): Flow<Map<Uuid, Int>> =
        journalContentDao.observeAllLinks().map { links ->
            links.groupingBy { it.journalId }.eachCount()
        }

    override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> =
        journalNotesRepository.observeNotesInJournal(journalId)

    override fun observeJournalsForContent(contentId: Uuid): Flow<List<Journal>> {
        // Use the dedicated JournalContentDao to get journal IDs
        return journalContentDao
            .getJournalsForContent(contentId)
            .combine(journalRepository.allJournalsObserved) { journalIds, allJournals ->
                // Get all journals that match the journal IDs
                allJournals.filter { journal ->
                    journalIds.contains(journal.id)
                }
            }
    }

    override suspend fun addContentToJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) = withContext(dispatcher) {
        addContentsToJournal(listOf(contentId), journalId)
        Unit
    }

    override suspend fun addContentToJournalForRestore(
        contentId: Uuid,
        journalId: Uuid,
        scope: JournalMergeScope?,
        recoveryId: String?,
    ): Boolean = addContentToJournalForRecovery(contentId, journalId, scope, recoveryId, retainedSource = false)

    override suspend fun addContentToJournalForRetainedRestore(
        contentId: Uuid,
        journalId: Uuid,
        scope: JournalMergeScope?,
        recoveryId: String?,
    ): Boolean = addContentToJournalForRecovery(contentId, journalId, scope, recoveryId, retainedSource = true)

    private suspend fun addContentToJournalForRecovery(
        contentId: Uuid,
        journalId: Uuid,
        scope: JournalMergeScope?,
        recoveryId: String?,
        retainedSource: Boolean,
    ): Boolean =
        transactionManager.withTransaction {
            check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership recovery account changed" }
            if (journalNotesRepository.getNoteById(contentId) == null) return@withTransaction false
            val uploadScope = scope?.let { UploadScope(it.ownerId, it.serverOrigin) }
            if (syncMetadataService.hasPendingDeletion(contentId.toString(), EntityType.NOTE, uploadScope)) return@withTransaction false
            val destination = journalRepository.resolveJournalId(journalId)
            if (journalRepository.getJournalById(destination) == null ||
                syncMetadataService.hasPendingDeletion(destination.toString(), EntityType.JOURNAL, uploadScope)
            ) {
                return@withTransaction false
            }
            val key = AssociationPendingKey(destination, contentId).toPendingId()
            val receiptKey = AssociationPendingKey(journalId, contentId).toPendingId()
            if (retainedSource &&
                scope != null &&
                (
                    recoveryReceipts.containsLocalDeletion(scope, LocalDeletionType.NOTE, contentId.toString()) ||
                        recoveryReceipts.containsLocalDeletion(scope, LocalDeletionType.JOURNAL, journalId.toString()) ||
                        recoveryReceipts.containsLocalDeletion(scope, LocalDeletionType.JOURNAL, destination.toString()) ||
                        recoveryReceipts.containsLocalDeletion(scope, LocalDeletionType.ASSOCIATION, receiptKey) ||
                        recoveryReceipts.containsLocalDeletion(scope, LocalDeletionType.ASSOCIATION, key)
                )
            ) {
                if (recoveryId != null) recoveryReceipts.record(scope, "ASSOCIATION", recoveryId, receiptKey)
                return@withTransaction false
            }
            if (scope != null &&
                recoveryId != null &&
                recoveryReceipts.contains(scope, "ASSOCIATION", recoveryId, receiptKey)
            ) {
                return@withTransaction false
            }
            if (!syncMetadataService.enqueueRestoreMutation(
                    key,
                    EntityType.ASSOCIATION,
                    PendingOperation.CREATE,
                    uploadScope,
                )
            ) {
                return@withTransaction false
            }
            if (!retainedSource && scope != null) {
                recoveryReceipts.clearLocalDeletion(scope, LocalDeletionType.ASSOCIATION, receiptKey)
            }
            journalContentDao.addContentToJournal(JournalContentEntityLink(destination, contentId))
            if (scope != null && recoveryId != null) recoveryReceipts.record(scope, "ASSOCIATION", recoveryId, receiptKey)
            check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership recovery account changed" }
            true
        }

    override suspend fun addContentsToJournal(
        contentIds: Collection<Uuid>,
        journalId: Uuid,
    ): Int =
        withContext(dispatcher) {
            val uniqueContentIds = contentIds.distinct()
            if (uniqueContentIds.isEmpty()) return@withContext 0

            transactionManager.withTransaction {
                val scope = journalNotesRepository.getNoteRecoveryScope()
                val destination = journalRepository.resolveJournalId(journalId)
                val newContentIds =
                    uniqueContentIds.filterNot { contentId ->
                        journalContentDao.isContentInJournal(destination, contentId)
                    }

                newContentIds.forEach { contentId ->
                    if (scope != null) {
                        recoveryReceipts.clearLocalDeletion(
                            scope,
                            LocalDeletionType.ASSOCIATION,
                            AssociationPendingKey(journalId, contentId).toPendingId(),
                        )
                    }
                    journalContentDao.addContentToJournal(JournalContentEntityLink(destination, contentId))
                }
                newContentIds.forEach { contentId ->
                    syncMetadataService.enqueuePending(
                        entityId = AssociationPendingKey(destination, contentId).toPendingId(),
                        entityType = EntityType.ASSOCIATION,
                        operation = PendingOperation.CREATE,
                    )
                }
                check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership creation account changed" }
                newContentIds.size
            }
        }

    override suspend fun removeContentFromJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) = withContext(dispatcher) {
        val removed =
            transactionManager.withTransaction {
                if (journalRepository.resolveJournalId(journalId) != journalId) return@withTransaction false
                val scope = journalNotesRepository.getNoteRecoveryScope()
                val key = AssociationPendingKey(journalId, contentId).toPendingId()
                if (scope != null) recoveryReceipts.recordLocalDeletion(scope, LocalDeletionType.ASSOCIATION, key)
                journalContentDao.removeContentFromJournal(journalId, contentId)
                syncMetadataService.enqueuePending(key, EntityType.ASSOCIATION, PendingOperation.DELETE)
                check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership deletion account changed" }
                true
            }
        if (!removed) return@withContext

        Unit
    }

    override suspend fun addContentToJournals(
        contentId: Uuid,
        journalIds: List<Uuid>,
    ) = withContext(dispatcher) {
        journalIds.forEach { journalId ->
            addContentToJournal(contentId, journalId)
        }
    }

    override suspend fun removeContentFromAllJournals(contentId: Uuid) =
        withContext(dispatcher) {
            transactionManager.withTransaction {
                val scope = journalNotesRepository.getNoteRecoveryScope()
                val journalIds = journalContentDao.getJournalsForContent(contentId).first()
                journalIds.forEach { journalId ->
                    val key = AssociationPendingKey(journalId, contentId).toPendingId()
                    if (scope != null) recoveryReceipts.recordLocalDeletion(scope, LocalDeletionType.ASSOCIATION, key)
                    syncMetadataService.enqueuePending(key, EntityType.ASSOCIATION, PendingOperation.DELETE)
                }
                journalContentDao.removeContentFromAllJournals(contentId)
                check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership deletion account changed" }
            }
        }

    override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> {
        if (contentIds.isEmpty()) return flowOf(emptyMap())
        return journalContentDao
            .getJournalsForContents(contentIds.toList())
            .combine(journalRepository.allJournalsObserved) { links, allJournals ->
                val journalMap = allJournals.associateBy { it.id }
                links
                    .groupBy(
                        keySelector = { it.contentId },
                        valueTransform = { journalMap[it.journalId] },
                    ).mapValues { (_, journals) -> journals.filterNotNull() }
            }
    }

    override suspend fun addContentToJournalFromSync(
        contentId: Uuid,
        journalId: Uuid,
    ) = withContext(dispatcher) {
        transactionManager.withTransaction {
            val scope = journalNotesRepository.getNoteRecoveryScope()
            val destination = journalRepository.resolveJournalId(journalId)
            journalContentDao.addContentToJournal(JournalContentEntityLink(destination, contentId))
            if (scope != null) {
                recoveryReceipts.clearLocalDeletion(
                    scope,
                    LocalDeletionType.ASSOCIATION,
                    AssociationPendingKey(journalId, contentId).toPendingId(),
                )
                recoveryReceipts.clearLocalDeletion(
                    scope,
                    LocalDeletionType.ASSOCIATION,
                    AssociationPendingKey(destination, contentId).toPendingId(),
                )
            }
            if (destination != journalId) {
                syncMetadataService.enqueueCreateIfAbsent(
                    AssociationPendingKey(destination, contentId).toPendingId(),
                    EntityType.ASSOCIATION,
                )
            }
            check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership sync account changed" }
        }
    }

    override suspend fun removeContentFromJournalFromSync(
        contentId: Uuid,
        journalId: Uuid,
    ) = withContext(dispatcher) {
        transactionManager.withTransaction {
            if (journalRepository.resolveJournalId(journalId) == journalId) {
                val scope = journalNotesRepository.getNoteRecoveryScope()
                val key = AssociationPendingKey(journalId, contentId).toPendingId()
                if (scope != null) recoveryReceipts.recordLocalDeletion(scope, LocalDeletionType.ASSOCIATION, key)
                journalContentDao.removeContentFromJournal(journalId, contentId)
                check(journalNotesRepository.getNoteRecoveryScope() == scope) { "Membership deletion account changed" }
            }
        }
    }
}

private object PassthroughJournalContentTransactionManager : SyncTransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}
