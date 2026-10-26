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
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

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
        )

    suspend fun downloadJournals(
        accessToken: String,
        since: Instant,
    ): SyncResult {
        val syncableRepository = journalRepository as? SyncableJournalRepository
        val downloadScope = downloadInbox?.currentScope()
        val requestScope = tokenRefresher.currentRequestScope()
        val mergeScope = requestScope?.let { JournalMergeScope(it.ownerId, it.serverOrigin) }
        val result =
            downloadEngine.download(
                strategy =
                    DownloadStrategy(
                        entityType = EntityType.JOURNAL,
                        logLabel = "journal",
                        fetchChanges = { _, cursor -> fetchJournalChanges(cursor, requestScope, downloadScope) },
                        localItems = { journalRepository.allJournalsObserved.first().associateBy { it.id } },
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
                        applyReplace = { _, replacement ->
                            if (syncableRepository != null) {
                                syncableRepository.updateFromSync(replacement)
                            } else {
                                journalRepository.update(replacement)
                            }
                        },
                        applyDelete = { id ->
                            if (syncableRepository != null) syncableRepository.deleteFromSync(id) else journalRepository.delete(id)
                        },
                    ),
                accessToken = accessToken,
                since = since,
            )
        journalRepository.reconcileJournalRedirects(mergeScope)
        return result
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
        val syncableRepository = journalNotesRepository as? SyncableJournalNotesRepository
        return downloadEngine.download(
            strategy =
                DownloadStrategy(
                    entityType = EntityType.NOTE,
                    logLabel = "note",
                    fetchChanges = { token, cursor ->
                        tokenRefresher
                            .withFreshToken(
                                { t -> cloudContentDataSource.getContentChanges(t, cursor, SYNC_PAGE_SIZE) },
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
                    },
                    localItems = { journalNotesRepository.allNotesObserved.first().associateBy { it.uid } },
                    localItem = journalNotesRepository::getNoteById,
                    idOf = { it.uid },
                    syncVersionOf = { it.syncVersion },
                    lastUpdatedOf = { it.lastUpdated },
                    conflictResolver = noteConflictResolver,
                    sameUploadedFields = ::sameNoteFields,
                    sameCreateBaseFields = ::sameAudioCreateBaseFields,
                    acknowledgeUpload = ::acknowledgeNoteCreate,
                    hydrate = ::hydrateNote,
                    applyCreate = { note ->
                        if (syncableRepository != null) syncableRepository.createFromSync(note) else journalNotesRepository.create(note)
                    },
                    // Notes have no update-in-place sync path -- a replacement is always applied as
                    // remove-then-recreate, matching how a fresh download from the server is applied.
                    applyReplace = { existing, replacement ->
                        if (syncableRepository != null) {
                            syncableRepository.deleteFromSync(existing.uid)
                            syncableRepository.createFromSync(replacement)
                        } else {
                            journalNotesRepository.remove(existing)
                            journalNotesRepository.create(replacement)
                        }
                    },
                    applyDelete = { id ->
                        if (syncableRepository != null) syncableRepository.deleteFromSync(id) else journalNotesRepository.removeById(id)
                    },
                    afterDelete = { id -> mediaSyncRefStore.delete(id) },
                ),
            accessToken = accessToken,
            since = since,
        )
    }

    private suspend fun hydrateNote(
        accessToken: String,
        note: JournalNote,
    ): JournalNote {
        // Pending work must compare the original remote fields without replacing its media mapping.
        if (syncMetadataService.hasPending(EntityType.NOTE, note.uid.toString())) return note
        val accepted = preserveTranscript(note)
        return if (downloadInbox == null) mediaTransfer.downloadIfNeeded(accessToken, accepted) else accepted
    }

    private suspend fun preserveTranscript(replacement: JournalNote): JournalNote {
        val existing = journalNotesRepository.getNoteById(replacement.uid)
        return if (existing is JournalNote.Audio &&
            replacement is JournalNote.Audio &&
            replacement.transcript == null &&
            remoteMediaRef(existing) == replacement.mediaRef &&
            existing.durationMs == replacement.durationMs
        ) {
            replacement.copy(transcript = existing.transcript)
        } else {
            replacement
        }
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

private fun Instant.wirePrecision(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())

private fun JournalNote.withVersion(version: Long): JournalNote =
    when (this) {
        is JournalNote.Text -> copy(syncVersion = version)
        is JournalNote.Image -> copy(syncVersion = version)
        is JournalNote.Video -> copy(syncVersion = version)
        is JournalNote.Audio -> copy(syncVersion = version)
    }

/** Compare only fields carried by the existing content contract, after resolving cached media. */
private fun JournalNote.uploadedFields(
    version: Long,
    ref: String?,
): JournalNote {
    val created = creationTimestamp.wirePrecision()
    val updated = lastUpdated.wirePrecision()
    val uploadedLocation = location?.takeIf { it.hasLocation }
    return when (this) {
        is JournalNote.Text -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                location = uploadedLocation,
                timeZoneId = null,
            )
        }

        is JournalNote.Image -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = ref.orEmpty(),
                location = uploadedLocation,
                timeZoneId = null,
            )
        }

        is JournalNote.Video -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = ref.orEmpty(),
                location = uploadedLocation,
                timeZoneId = null,
            )
        }

        is JournalNote.Audio -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = ref.orEmpty(),
                location = uploadedLocation,
                timeZoneId = null,
            )
        }
    }
}
