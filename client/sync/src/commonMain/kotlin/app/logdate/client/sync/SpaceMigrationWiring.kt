package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.location.HistoryRecordRoutes
import app.logdate.client.sync.location.HistoryRoute
import app.logdate.client.sync.location.MovedHistory
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceRecordRegistry
import app.logdate.client.sync.space.SpaceSyncDependencies
import app.logdate.client.sync.space.mappers.SpaceDraft
import app.logdate.client.sync.space.mappers.SpaceJournal
import app.logdate.client.sync.space.mappers.StoredLocationParts
import app.logdate.client.sync.space.migration.LegacyKindCheck
import app.logdate.client.sync.space.migration.MediaOwner
import app.logdate.client.sync.space.migration.MigrationKind
import app.logdate.client.sync.space.migration.MigrationMediaPreparer
import app.logdate.client.sync.space.migration.SpaceJournalMigration
import app.logdate.client.sync.space.migration.SpaceMigrationDependencies
import app.logdate.client.sync.space.migration.SpaceMigrationEnvironment
import app.logdate.client.sync.space.migration.dataKind
import app.logdate.client.sync.space.migration.legacyMembershipId
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.sync.LegacyRecordKinds
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/** The kinds of data the move uploads, in the order it uploads them: places before what names them. */
private val MOVED_TYPES =
    listOf(
        EntityType.JOURNAL,
        EntityType.NOTE,
        EntityType.ASSOCIATION,
        EntityType.DRAFT,
        EntityType.MOMENT,
        EntityType.PLACE,
        EntityType.PLACE_VISIT,
        EntityType.PLACE_CORRECTION,
        EntityType.PLACE_ENTRY_LINK,
        EntityType.PLACE_JOURNEY,
        EntityType.LOCATION_SAMPLES,
    )

/** The kinds legacy sync queues through the outbox, and the legacy inventory kind each one is. */
private val OUTBOX_KINDS =
    mapOf(
        EntityType.JOURNAL to LegacyRecordKinds.JOURNALS,
        EntityType.NOTE to LegacyRecordKinds.ENTRIES,
        EntityType.ASSOCIATION to LegacyRecordKinds.ASSOCIATIONS,
        EntityType.DRAFT to LegacyRecordKinds.DRAFTS,
        EntityType.MOMENT to LegacyRecordKinds.MOMENTS,
    )

/** The move of this account's journal into its personal space, when both the space and the move are wired. */
internal fun DefaultSyncManager.createJournalMove(deps: SpaceMigrationDependencies): SpaceJournalMigration? {
    val spaceDependencies = spaceSyncDependencies ?: return null
    val inbox = downloadInbox ?: return null
    val environment =
        SpaceMigrationEnvironment(
            deps = deps,
            space = spaceDependencies,
            tokens = tokenRefresher,
            metadata = syncMetadataService,
            inbox = inbox,
            mediaManager = mediaManager,
            mediaReferences = mediaReferences,
            legacyRefs = mediaSyncRefStore,
            legacyMedia = cloudMediaDataSource,
            history = historyEngine,
            legacyDownloadFinished = { scope -> firstSyncEnqueueStore.hasAuditedLegacyScope(scope.ownerId, scope.serverOrigin) },
            hasIdentityKey = { identityKeyManager?.hasIdentityKey() ?: true },
            kinds = { preparer -> movedKinds(spaceDependencies.registry, deps, preparer) },
            legacyChecks = legacyChecks(spaceDependencies, deps),
            mediaOwners = { mediaOwners() },
            download = { downloadRemoteChanges() },
            upload = { uploadPendingChanges() },
            whilePaused = { block -> whilePaused { block() } },
            refreshAccount = { refreshServerAccount()?.getOrThrow() },
            rebase = { scope -> rebaseForRoute(scope) },
            requeueForLegacy = { deleted -> requeueForLegacy(deps, deleted) },
        )
    return SpaceJournalMigration(environment)
}

private suspend fun DefaultSyncManager.movedKinds(
    registry: SpaceRecordRegistry,
    deps: SpaceMigrationDependencies,
    preparer: MigrationMediaPreparer?,
): List<MigrationKind> {
    val history = historyEngine?.takeIf { it.isEnabled() }
    return MOVED_TYPES.mapNotNull { type ->
        @Suppress("UNCHECKED_CAST")
        val kind = registry.forEntityType(type) as? SpaceKind<Any> ?: return@mapNotNull null
        val ids: suspend () -> Collection<String> =
            when (type) {
                EntityType.JOURNAL -> {
                    ({ journalRepository.allJournalsObserved.first().map { it.id.toString() } })
                }

                EntityType.NOTE -> {
                    ({ journalNotesRepository.allNotesObserved.first().map { it.uid.toString() } })
                }

                EntityType.ASSOCIATION -> {
                    ({ membershipIds() })
                }

                EntityType.DRAFT -> {
                    if (profile.syncsDrafts) {
                        (
                            {
                                journalRepository.getAllDraftsForSync().map { it.id.toString() }
                            }
                        )
                    } else {
                        return@mapNotNull null
                    }
                }

                EntityType.MOMENT -> {
                    if (momentSyncConfig?.enabled?.invoke() == true) ({ deps.liveMomentIds() }) else return@mapNotNull null
                }

                else -> {
                    if (history !=
                        null
                    ) {
                        ({ history.recordsForMove()?.let { historyItems(registry, it)[type] }.orEmpty() })
                    } else {
                        return@mapNotNull null
                    }
                }
            }
        MigrationKind(kind, type.dataKind(), ids, prepareFor(type, preparer))
    }
}

private fun prepareFor(
    type: EntityType,
    preparer: MigrationMediaPreparer?,
): suspend (Any) -> Any {
    val media = preparer ?: return { it }
    return when (type) {
        EntityType.NOTE -> ({ media.note(it as JournalNote) })
        EntityType.DRAFT -> ({ media.draft(it as SpaceDraft) })
        EntityType.JOURNAL -> ({ media.journal(it as SpaceJournal) })
        else -> ({ it })
    }
}

/** Every location history item this device keeps, by kind, from its history records and saved places. */
private suspend fun historyItems(
    registry: SpaceRecordRegistry,
    history: MovedHistory,
): Map<EntityType, Set<String>> {
    val items = mutableMapOf<EntityType, MutableSet<String>>()
    val hours = mutableSetOf<Pair<String, Instant>>()
    for (record in history.records) {
        when (val route = HistoryRecordRoutes.routeOf(record)) {
            is HistoryRoute.Hour -> hours += route.device to route.hourStart
            is HistoryRoute.Item -> if (!record.deleted) items.getOrPut(route.type) { mutableSetOf() } += route.id
            else -> Unit
        }
    }
    items.getOrPut(EntityType.PLACE) { mutableSetOf() } += history.savedPlaceIds
    val samples = registry.forEntityType(EntityType.LOCATION_SAMPLES)?.local as? StoredLocationParts
    if (samples != null) {
        val parts = items.getOrPut(EntityType.LOCATION_SAMPLES) { mutableSetOf() }
        hours.forEach { (device, hour) -> parts += samples.partIds(device, hour) }
    }
    return items
}

private suspend fun DefaultSyncManager.membershipIds(): List<String> {
    val notes = journalNotesRepository.allNotesObserved.first().map { it.uid }
    return notes.chunked(MEMBERSHIP_CHUNK).flatMap { chunk ->
        journalContentRepository.observeJournalsForContents(chunk.toSet()).first().flatMap { (contentId, journals) ->
            journals.map { AssociationPendingKey(it.id, contentId).toPendingId() }
        }
    }
}

private const val MEMBERSHIP_CHUNK = 500

private fun DefaultSyncManager.legacyChecks(
    space: SpaceSyncDependencies,
    deps: SpaceMigrationDependencies,
): List<LegacyKindCheck> {
    fun registered(type: EntityType) = space.registry.forEntityType(type) != null
    return listOf(
        outboxCheck(LegacyRecordKinds.ENTRIES, EntityType.NOTE, { registered(EntityType.NOTE) }) {
            journalNotesRepository.allNotesObserved.first().map { it.uid.toString() }
        },
        outboxCheck(LegacyRecordKinds.JOURNALS, EntityType.JOURNAL, { registered(EntityType.JOURNAL) }) {
            journalRepository.allJournalsObserved.first().map { it.id.toString() }
        },
        outboxCheck(LegacyRecordKinds.ASSOCIATIONS, EntityType.ASSOCIATION, { registered(EntityType.ASSOCIATION) }, ::legacyMembershipId) {
            membershipIds().map(::legacyMembershipId)
        },
        outboxCheck(LegacyRecordKinds.DRAFTS, EntityType.DRAFT, { profile.syncsDrafts && registered(EntityType.DRAFT) }) {
            journalRepository.getAllDraftsForSync().map { it.id.toString() }
        },
        outboxCheck(
            LegacyRecordKinds.MOMENTS,
            EntityType.MOMENT,
            { momentSyncConfig?.enabled?.invoke() == true && registered(EntityType.MOMENT) },
        ) { deps.liveMomentIds().toList() },
        LegacyKindCheck(
            legacyKind = LegacyRecordKinds.LOCATION_HISTORY,
            dataKind = EntityType.PLACE.dataKind(),
            localIds = {
                historyEngine
                    ?.recordsForMove()
                    ?.records
                    ?.filter { !it.deleted }
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            },
            waitingCreates = {
                historyEngine
                    ?.recordsForMove()
                    ?.records
                    ?.filter { it.dirty && !it.deleted }
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            },
            waitingDeletes = {
                historyEngine
                    ?.recordsForMove()
                    ?.records
                    ?.filter { it.dirty && it.deleted }
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            },
            syncedHere = { historyEngine?.isEnabled() == true && registered(EntityType.LOCATION_SAMPLES) },
        ),
    )
}

private fun DefaultSyncManager.outboxCheck(
    legacyKind: String,
    type: EntityType,
    syncedHere: suspend () -> Boolean,
    toLegacy: (String) -> String = { it.lowercase() },
    localIds: suspend () -> List<String>,
): LegacyKindCheck {
    suspend fun waiting(deletes: Boolean): Set<String> {
        val types = if (type == EntityType.JOURNAL) listOf(type, EntityType.JOURNAL_MERGE) else listOf(type)
        return types
            .flatMap { pendingType ->
                syncMetadataService
                    .getPendingUploads(pendingType)
                    .filter { (it.operation == PendingOperation.DELETE || pendingType == EntityType.JOURNAL_MERGE) == deletes }
            }.map { toLegacy(it.entityId) }
            .toSet()
    }
    return LegacyKindCheck(
        legacyKind = legacyKind,
        dataKind = type.dataKind(),
        localIds = { localIds().map(toLegacy).toSet() },
        waitingCreates = { waiting(deletes = false) },
        waitingDeletes = { waiting(deletes = true) },
        syncedHere = syncedHere,
    )
}

/** Every media file this device's notes, drafts and journal covers refer to. */
private suspend fun DefaultSyncManager.mediaOwners(): List<MediaOwner> {
    val notes =
        journalNotesRepository.allNotesObserved
            .first()
            .filter { it.mediaRefOrNull() != null }
            .map { MediaOwner.Note(it.uid) }
    val drafts =
        if (profile.syncsDrafts) {
            journalRepository.getAllDraftsForSync().flatMap { draft ->
                draft.blocks.flatMap { block ->
                    when (block) {
                        is SerializableImageBlock -> listOf("image")
                        is SerializableVideoBlock -> listOf("video", "thumbnail")
                        is SerializableAudioBlock -> listOf("audio")
                        is SerializableCameraBlock -> listOf("camera")
                        else -> emptyList()
                    }.map { kind -> MediaOwner.Asset(EntityType.DRAFT, draft.id, block.id, kind) }
                }
            }
        } else {
            emptyList()
        }
    val covers =
        journalRepository.allJournalsObserved
            .first()
            .filter { it.coverImageUri != null }
            .map { MediaOwner.Asset(EntityType.JOURNAL, it.id, it.id, JOURNAL_COVER_KIND) }
    return notes + drafts + covers
}

/**
 * Queues this device's whole journal for legacy storage again after switching back, and a deletion
 * for each id legacy storage still has that this device deleted while the journal was in its space.
 */
private suspend fun DefaultSyncManager.requeueForLegacy(
    deps: SpaceMigrationDependencies,
    deleted: Map<String, Set<String>>,
) {
    val local =
        mapOf(
            EntityType.JOURNAL to journalRepository.allJournalsObserved.first().map { it.id.toString() },
            EntityType.NOTE to journalNotesRepository.allNotesObserved.first().map { it.uid.toString() },
            EntityType.ASSOCIATION to membershipIds(),
            EntityType.DRAFT to if (profile.syncsDrafts) journalRepository.getAllDraftsForSync().map { it.id.toString() } else emptyList(),
            EntityType.MOMENT to if (momentSyncConfig?.enabled?.invoke() == true) deps.liveMomentIds().toList() else emptyList(),
        )
    for ((type, ids) in local) ids.forEach { syncMetadataService.enqueueRepairIfAbsent(it, type) }
    for ((type, legacyKind) in OUTBOX_KINDS) {
        deleted[legacyKind].orEmpty().forEach { id ->
            val entityId = if (type == EntityType.ASSOCIATION) id.replace(":", "::") else id
            syncMetadataService.enqueuePending(entityId, type, PendingOperation.DELETE)
        }
    }
    historyEngine?.requeueForLegacy()
}
