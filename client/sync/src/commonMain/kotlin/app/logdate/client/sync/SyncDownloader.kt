package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudAssociationDataSource
import app.logdate.client.sync.cloud.CloudContentDataSource
import app.logdate.client.sync.cloud.CloudDraftDataSource
import app.logdate.client.sync.cloud.CloudJournalDataSource
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.SPACE_CHECKPOINT
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceMediaReference
import app.logdate.client.sync.space.SpaceStagedRecords
import app.logdate.client.sync.space.SpaceSync
import app.logdate.client.sync.space.mappers.SpaceJournal
import app.logdate.shared.model.Journal
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The concrete per-[EntityType] download side of sync: journals and notes fit
 * [SyncDownloadEngine]'s pluggable-[ConflictResolver] shape and are thin strategy definitions;
 * drafts (a simpler newer-wins heuristic) and associations (additions/deletions, not
 * changes/deletions) don't, and have their own hand-rolled paginate-apply loops instead, in
 * [DraftDownloader] and [AssociationDownloader].
 *
 * @param mapCloudApiError,mapException Reuse [DefaultSyncManager]'s own error mapping (including
 *   its `lastErrorFlow` side effect) instead of duplicating it here, so a download failure is
 *   reported identically to an upload failure.
 */
internal class SyncDownloader(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    journalContentRepository: JournalContentRepository,
    private val cloudJournalDataSource: CloudJournalDataSource,
    private val cloudContentDataSource: CloudContentDataSource,
    cloudDraftDataSource: CloudDraftDataSource,
    cloudAssociationDataSource: CloudAssociationDataSource,
    private val journalConflictResolver: ConflictResolver<Journal>,
    private val noteConflictResolver: ConflictResolver<JournalNote>,
    private val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager,
    private val mediaSyncRefStore: MediaSyncRefStore,
    private val downloadEngine: SyncDownloadEngine,
    private val mediaTransfer: SyncMediaTransfer,
    private val tokenRefresher: SyncTokenRefresher,
    mapCloudApiError: (CloudApiException) -> SyncResult,
    mapException: (Exception, String) -> SyncResult,
    private val downloadInbox: app.logdate.client.sync.recovery.DownloadInbox? = null,
    private val downloadsMedia: Boolean = true,
    private val remoteApplyFilter: RemoteApplyFilter = RemoteApplyFilter.ALL,
    private val space: SpaceSync? = null,
) {
    private val draftDownloader =
        DraftDownloader(
            journalRepository = journalRepository,
            cloudDraftDataSource = cloudDraftDataSource,
            syncMetadataService = syncMetadataService,
            transactionManager = transactionManager,
            downloadEngine = downloadEngine,
            tokenRefresher = tokenRefresher,
            mapCloudApiError = mapCloudApiError,
            mapException = mapException,
            downloadInbox = downloadInbox,
            space = space,
        )

    private val associationDownloader =
        AssociationDownloader(
            journalContentRepository = journalContentRepository,
            cloudAssociationDataSource = cloudAssociationDataSource,
            syncMetadataService = syncMetadataService,
            transactionManager = transactionManager,
            downloadEngine = downloadEngine,
            tokenRefresher = tokenRefresher,
            mapCloudApiError = mapCloudApiError,
            mapException = mapException,
            downloadInbox = downloadInbox,
            space = space,
            resolveJournal = journalRepository::resolveJournalId,
        )

    suspend fun downloadJournals(
        accessToken: String,
        since: Instant,
    ): SyncResult {
        val syncableRepository = journalRepository as? SyncableJournalRepository
        val downloadScope = downloadInbox?.currentScope()
        val requestScope = tokenRefresher.currentRequestScope()
        val mergeScope = requestScope?.let { JournalMergeScope(it.ownerId, it.serverOrigin) }
        val spaceRead = spaceRead<SpaceJournal>(EntityType.JOURNAL, ::enqueueSpaceCover)?.let { JournalSpaceRead(it, mergeScope) }
        val result =
            downloadEngine.download(
                strategy =
                    DownloadStrategy(
                        entityType = EntityType.JOURNAL,
                        logLabel = "journal",
                        fetchChanges = { _, cursor -> spaceRead?.page() ?: fetchJournalChanges(cursor, requestScope, downloadScope) },
                        localItem = journalRepository::getJournalById,
                        idOf = { it.id },
                        syncVersionOf = { it.syncVersion },
                        lastUpdatedOf = { it.lastUpdated },
                        conflictResolver = journalConflictResolver,
                        sameUploadedFields = ::sameJournalFields,
                        acknowledgeUpload = { local, remote ->
                            if (syncableRepository != null) {
                                syncableRepository.updateSyncMetadata(local.id, remote.syncVersion, remote.lastUpdated)
                                true
                            } else {
                                false
                            }
                        },
                        applyCreate = { journal ->
                            if (syncableRepository !=
                                null
                            ) {
                                syncableRepository.createFromSync(journal)
                            } else {
                                journalRepository.create(journal)
                            }
                        },
                        applyReplace = { existing, remote ->
                            // Only the personal space carries the favorite; a server's journal reads as never favorited.
                            val replacement = if (spaceRead != null) remote else remote.copy(isFavorited = existing.isFavorited)
                            if (syncableRepository != null) {
                                syncableRepository.updateFromSync(replacement)
                            } else {
                                journalRepository.update(replacement)
                            }
                        },
                        applyDelete = { id ->
                            if (syncableRepository != null) syncableRepository.deleteFromSync(id) else journalRepository.delete(id)
                        },
                        checkpoint = if (spaceRead != null) SPACE_CHECKPOINT else EntityType.JOURNAL.name,
                        settle = spaceRead?.let { read -> read.records::settle },
                        settleFailed = { id -> spaceRead?.records?.settleFailed(id) },
                        appliesRemoteOverUnchangedLocal = spaceRead != null,
                    ),
                accessToken = accessToken,
                since = since,
            )
        journalRepository.reconcileJournalRedirects(mergeScope)
        return result
    }

    /** Runs one download pass, in which the personal space's change feed is pulled once for every kind. */
    suspend fun <R> downloadPass(block: suspend () -> R): R {
        val sync = space ?: return block()
        return sync.downloadPass(block)
    }

    /** Reads [entityType]'s records from the personal space, when this device syncs that kind there now. */
    private suspend fun <T : Any> spaceRead(
        entityType: EntityType,
        afterStored: suspend (Uuid) -> Unit = {},
    ): SpaceRead<T>? {
        val sync = space ?: return null
        val kind = sync.activeKind(entityType) ?: return null
        // The registry pairs each entity type with the kind of its own item type.
        @Suppress("UNCHECKED_CAST")
        return SpaceRead(sync, kind as SpaceKind<T>, afterStored)
    }

    /** Queues the personal-space file a journal applied from the space has as its cover, for media recovery. */
    private suspend fun enqueueSpaceCover(id: Uuid) {
        val inbox = downloadInbox ?: return
        if (!downloadsMedia) return
        val journal = journalRepository.getJournalById(id)?.takeIf { it.id == id } ?: return
        if (journal.coverImageUri?.let { SpaceMediaReference.parse(it) } == null) return
        inbox.enqueueSpaceJournalMedia(id.toString(), journal.syncVersion)
    }

    /** Queues the personal-space file a note applied from the space refers to, for media recovery. */
    private suspend fun enqueueSpaceMedia(id: Uuid) {
        val inbox = downloadInbox ?: return
        if (!downloadsMedia) return
        val note = journalNotesRepository.getNoteById(id) ?: return
        val reference = note.mediaRefOrNull()?.takeIf { SpaceMediaReference.parse(it) != null } ?: return
        inbox.enqueueSpaceMedia(id.toString(), note.syncVersion, reference)
    }

    /** One download's read of a kind's staged personal-space records, settled as each item is applied. */
    private class SpaceRead<T : Any>(
        private val sync: SpaceSync,
        private val kind: SpaceKind<T>,
        private val afterStored: suspend (Uuid) -> Unit,
    ) {
        private var staged: SpaceStagedRecords<T>? = null

        suspend fun page(): Result<ChangesPage<T>> {
            val records = staged ?: sync.read(kind).getOrElse { return Result.failure(it) }.also { staged = it }
            val page = records.page()
            return Result.success(ChangesPage(page.changes, page.deletions.map(Uuid::parse), page.readAt, page.hasMore))
        }

        suspend fun settle(
            id: Uuid,
            outcome: RemoteOutcome,
        ) {
            staged?.settle(id.toString(), outcome)
            if (outcome == RemoteOutcome.STORED || outcome == RemoteOutcome.MERGED) afterStored(id)
        }

        suspend fun settleFailed(id: Uuid) {
            staged?.settleFailed(id.toString())
        }
    }

    /**
     * Journals from the personal space. A record that says its journal was merged into another is
     * applied as a merge, with its entries moving to the surviving journal once that journal is
     * here, and is never offered to the download as a journal.
     */
    private inner class JournalSpaceRead(
        val records: SpaceRead<SpaceJournal>,
        private val mergeScope: JournalMergeScope?,
    ) {
        suspend fun page(): Result<ChangesPage<Journal>> {
            val page = records.page().getOrElse { return Result.failure(it) }
            val (merged, journals) = page.changes.partition { it.mergedInto != null }
            val failure = merged.mapNotNull { applyMerge(it) }.firstOrNull()
            if (failure != null) return Result.failure(failure)
            return Result.success(ChangesPage(journals.map { it.journal }, page.deletions, page.lastSyncTimestamp, page.hasMore))
        }

        /** Applies the merge [item] records, or returns why it couldn't be applied. */
        private suspend fun applyMerge(item: SpaceJournal): Exception? {
            val id = item.journal.id
            val survivor = requireNotNull(item.mergedInto)
            return try {
                transactionManager.withTransaction {
                    journalRepository.applyJournalRedirect(id, survivor, mergeScope)
                    records.settle(id, RemoteOutcome.STORED)
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                records.settleFailed(id)
                error
            }
        }
    }

    private suspend fun fetchJournalChanges(
        cursor: Instant,
        requestScope: UploadScope?,
        downloadScope: DownloadScope?,
    ): Result<ChangesPage<Journal>> =
        tokenRefresher
            .withFreshToken(
                { token -> cloudJournalDataSource.getJournalChanges(token, cursor, SYNC_PAGE_SIZE) },
                "getJournalChanges",
                requestScope,
            ).map { page ->
                val mergeScope = requestScope?.let { JournalMergeScope(it.ownerId, it.serverOrigin) }
                transactionManager.withTransaction {
                    check(downloadScope == downloadInbox?.currentScope()) { "Download scope changed" }
                    page.mergeRedirects.forEach { (source, destination) ->
                        journalRepository.applyJournalRedirect(source, destination, mergeScope)
                        val version = page.mergeRedirectVersions[source]
                        if (version != null && downloadScope != null) {
                            downloadInbox?.applied("JOURNAL", source.toString(), version, downloadScope)
                        }
                    }
                    check(downloadScope == downloadInbox?.currentScope()) { "Download scope changed" }
                }
                ChangesPage(
                    page.changes,
                    page.deletions,
                    page.lastSyncTimestamp,
                    page.hasMore,
                    page.unreadable,
                    page.failures,
                    page.unreadableVersions,
                )
            }

    suspend fun downloadContent(
        accessToken: String,
        since: Instant,
    ): SyncResult {
        val spaceRead = spaceRead<JournalNote>(EntityType.NOTE, ::enqueueSpaceMedia)
        return downloadEngine.download(
            strategy =
                DownloadStrategy(
                    entityType = EntityType.NOTE,
                    logLabel = "note",
                    fetchChanges = spaceRead?.let { read -> { _, _ -> read.page() } } ?: { _, cursor -> fetchContentChanges(cursor) },
                    localItem = journalNotesRepository::getNoteById,
                    idOf = { it.uid },
                    syncVersionOf = { it.syncVersion },
                    lastUpdatedOf = { it.lastUpdated },
                    conflictResolver = noteConflictResolver,
                    sameUploadedFields = ::sameNoteFields,
                    sameCreateBaseFields = ::sameAudioCreateBaseFields,
                    acknowledgeUpload = ::acknowledgeNoteCreate,
                    // A note joined from personal-space records is the whole note, transcript included.
                    hydrate = if (spaceRead != null) { _, note -> preserveWorkingText(note) } else ::hydrateNote,
                    applyCreate = ::createNote,
                    // Notes have no update-in-place sync path -- a replacement is always applied as
                    // remove-then-recreate, matching how a fresh download from the server is applied.
                    applyReplace = ::replaceNote,
                    applyDelete = ::deleteNote,
                    afterDelete = { id -> mediaSyncRefStore.delete(id) },
                    appliesRemoteCreate = remoteApplyFilter::appliesNote,
                    enqueuesMediaRecovery = downloadsMedia,
                    checkpoint = if (spaceRead != null) SPACE_CHECKPOINT else EntityType.NOTE.name,
                    settle = spaceRead?.let { read -> read::settle },
                    settleFailed = { id -> spaceRead?.settleFailed(id) },
                    appliesRemoteOverUnchangedLocal = spaceRead != null,
                ),
            accessToken = accessToken,
            since = since,
        )
    }

    private suspend fun createNote(note: JournalNote) {
        val syncable = journalNotesRepository as? SyncableJournalNotesRepository
        if (syncable != null) syncable.createFromSync(note) else journalNotesRepository.create(note)
    }

    private suspend fun replaceNote(
        existing: JournalNote,
        replacement: JournalNote,
    ) {
        val syncable = journalNotesRepository as? SyncableJournalNotesRepository
        if (syncable != null) {
            syncable.deleteFromSync(existing.uid)
            syncable.createFromSync(replacement)
        } else {
            journalNotesRepository.remove(existing)
            journalNotesRepository.create(replacement)
        }
    }

    private suspend fun deleteNote(id: Uuid) {
        val syncable = journalNotesRepository as? SyncableJournalNotesRepository
        if (syncable != null) syncable.deleteFromSync(id) else journalNotesRepository.removeById(id)
    }

    private suspend fun fetchContentChanges(cursor: Instant): Result<ChangesPage<JournalNote>> =
        tokenRefresher
            .withFreshToken(
                { token -> cloudContentDataSource.getContentChanges(token, cursor, SYNC_PAGE_SIZE) },
                "getContentChanges",
            ).map {
                ChangesPage(
                    it.changes,
                    it.deletions,
                    it.lastSyncTimestamp,
                    it.hasMore,
                    it.unreadable,
                    it.failures,
                    it.unreadableVersions,
                )
            }

    private suspend fun hydrateNote(
        accessToken: String,
        note: JournalNote,
    ): JournalNote {
        // Pending work must compare the original remote fields without replacing its media mapping.
        if (syncMetadataService.hasPending(EntityType.NOTE, note.uid.toString())) return note
        val accepted = preserveTranscript(note)
        return if (downloadInbox == null && downloadsMedia) mediaTransfer.downloadIfNeeded(accessToken, accepted) else accepted
    }

    private suspend fun preserveTranscript(replacement: JournalNote): JournalNote {
        if (replacement !is JournalNote.Audio) return replacement
        val existing = journalNotesRepository.getNoteById(replacement.uid) as? JournalNote.Audio ?: return replacement
        val sourceReference = remoteMediaRef(existing)
        val accepted = replacement.withLocalAudioWorkingText(existing, sourceReference)
        return if (accepted is JournalNote.Audio &&
            accepted.transcript == null &&
            sourceReference == accepted.mediaRef &&
            existing.durationMs == accepted.durationMs
        ) {
            accepted.copy(transcript = existing.transcript)
        } else {
            accepted
        }
    }

    private suspend fun preserveWorkingText(replacement: JournalNote): JournalNote {
        if (replacement !is JournalNote.Audio) return replacement
        val existing = journalNotesRepository.getNoteById(replacement.uid) as? JournalNote.Audio ?: return replacement
        return replacement.withLocalAudioWorkingText(existing, remoteMediaRef(existing))
    }

    private fun sameJournalFields(
        local: Journal,
        remote: Journal,
    ): Boolean =
        local.copy(
            syncVersion = remote.syncVersion,
            isFavorited = remote.isFavorited,
            coverImageUri = remote.coverImageUri,
            created = local.created.wirePrecision(),
            lastUpdated = local.lastUpdated.wirePrecision(),
        ) == remote

    private suspend fun sameNoteFields(
        local: JournalNote,
        remote: JournalNote,
    ): Boolean {
        val ref = remoteMediaRef(local)
        return local.uploadedFields(remote.syncVersion, ref) == remote.uploadedFields(remote.syncVersion, remote.mediaRefOrNull())
    }

    private suspend fun remoteMediaRef(note: JournalNote): String? {
        val mapping = mediaSyncRefStore.get(note.uid)
        val localRef = note.mediaRefOrNull()
        return localRef?.let { mapping?.takeIf { cached -> mediaTransfer.sameLocalFile(cached.localUri, it) }?.remoteUrl } ?: localRef
    }

    private suspend fun sameAudioCreateBaseFields(
        local: JournalNote,
        remote: JournalNote,
    ): Boolean {
        val document = (local as? JournalNote.Audio)?.transcript ?: return false
        if (remote !is JournalNote.Audio ||
            document.revision <= (remote.transcript?.revision ?: -1)
        ) {
            return false
        }
        // The server stamps lastUpdated; transcript-only changes do not edit the source fields.
        return sameNoteFields(
            local.copy(lastUpdated = remote.lastUpdated, transcript = null, transcription = ""),
            remote.copy(transcript = null, transcription = ""),
        )
    }

    private suspend fun acknowledgeNoteCreate(
        local: JournalNote,
        remote: JournalNote,
    ): Boolean {
        val repository = journalNotesRepository as? SyncableJournalNotesRepository ?: return false
        repository.updateSyncMetadata(local.withVersion(remote.syncVersion), remote.syncVersion, remote.lastUpdated)
        return true
    }

    suspend fun downloadDrafts(
        accessToken: String,
        since: Instant,
    ): SyncResult = draftDownloader.download(since)

    suspend fun downloadAssociations(
        accessToken: String,
        since: Instant,
    ): SyncResult = associationDownloader.download(since)

    companion object {
        /**
         * Deliberately small. The server reads each record out of the account's repo, so the cost
         * of a page grows with the size of the page *and* the journal behind it; asking for 200 at
         * once stopped completing inside the request timeout once an account held a few hundred
         * entries, and a download that times out fails the whole sync. Raise this once a page is
         * cheap to serve again.
         */
        const val SYNC_PAGE_SIZE = 25
    }
}
