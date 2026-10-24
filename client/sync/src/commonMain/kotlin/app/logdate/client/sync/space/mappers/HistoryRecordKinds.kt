package app.logdate.client.sync.space.mappers

import app.logdate.client.database.dao.UserPlaceDao
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.sync.RemoteOutcome
import app.logdate.client.sync.conflict.ConflictResolution
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceLocalRecords
import app.logdate.client.sync.space.SpacePart
import app.logdate.client.sync.space.SpacePartChange
import app.logdate.client.sync.space.SpaceRecordMapper
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryJourney
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.ManualVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryLink
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.time.Instant

/**
 * A kind of location history that this device stores as history records, so that the personal space
 * can apply what other devices changed. It is [SpaceLocalRecords] plus what a download needs.
 */
internal interface HistoryKindRecords<T : Any> : SpaceLocalRecords<T> {
    /** The id of the item [item] is, as the space and the upload queue name it. */
    fun entityIdOf(item: T): String

    /** Records that the deletion of the item named [entityId] reached the space, so it isn't uploaded again. */
    suspend fun acknowledgeDeleted(entityId: String)

    /**
     * Stores [item], as read from the space, unless this device has a change to it that hasn't
     * uploaded yet: then nothing is stored and the upload settles which one wins.
     */
    suspend fun applyDownloaded(item: T): RemoteOutcome

    /** Stores that the item named [entityId] was deleted in the space. */
    suspend fun deleteDownloaded(entityId: String)
}

private val epoch = Instant.fromEpochMilliseconds(0)

/**
 * Records that this device stores as one history record each, named by [idOf].
 *
 * @param rowIdOf the id of the history record a new item is stored under.
 * @param prepare any change an item undergoes when read, applied to every item of the kind at once.
 */
private class SingleHistoryRecords<T : Any>(
    private val access: HistoryStoreAccess,
    private val recordType: String,
    private val idOf: (T) -> String,
    private val rowIdOf: (T) -> String,
    private val toPayload: (T) -> HistoryPayload,
    private val fromPayload: (HistoryPayload) -> T?,
    private val lastUpdated: (T) -> Instant,
    private val prepare: suspend (List<T>) -> List<T> = { it },
    override val conflictResolver: ConflictResolver<T>,
) : HistoryKindRecords<T> {
    private class Found<T>(
        val record: HistoryRecord,
        val value: T,
    )

    /** The value [row] holds, or null when it isn't one of this kind or can't be read as one. */
    private fun valueOf(row: HistoryRecord): T? = access.payloadOf(row)?.let(fromPayload)

    /** The entity id of the value [row] holds; null for a row whose value has none, such as a link with an id no key can name. */
    private fun entityOf(value: T): String? =
        try {
            idOf(value)
        } catch (_: SpaceRecordMappingException) {
            null
        }

    private suspend fun findAll(entityId: String): List<Found<T>> {
        val values = access.ofType(recordType).filter { !it.deleted }.mapNotNull { row -> valueOf(row)?.let { row to it } }
        val prepared = prepare(values.map { it.second })
        return values.indices.filter { entityOf(prepared[it]) == entityId }.map { Found(values[it].first, prepared[it]) }
    }

    private suspend fun find(entityId: String): Found<T>? = findAll(entityId).firstOrNull()

    private suspend fun rowOf(entityId: String): HistoryRecord? =
        access.ofType(recordType).firstOrNull { row -> valueOf(row)?.let(::entityOf) == entityId }

    override fun entityIdOf(item: T): String = idOf(item)

    override suspend fun acknowledgeDeleted(entityId: String) {
        val row = access.record(entityId)?.takeIf { it.recordType == recordType && it.deleted && it.dirty } ?: return
        access.acknowledge(row, 0)
    }

    override suspend fun load(entityId: String): T? = find(entityId)?.value

    override fun lastUpdatedOf(item: T): Instant = lastUpdated(item)

    override suspend fun acknowledgeUpload(
        item: T,
        seq: Long,
        syncedAt: Instant,
    ) {
        // Every record that holds this item, such as two links to the same visit, is covered by its one upload.
        findAll(idOf(item)).filter { it.record.dirty && it.value == item }.forEach { access.acknowledge(it.record, seq) }
    }

    override suspend fun replaceLocal(item: T) = store(item, dirty = true)

    override suspend fun applyDownloaded(item: T): RemoteOutcome {
        val existing = rowOf(idOf(item)) ?: access.record(rowIdOf(item))?.takeIf { it.recordType == recordType }
        if (existing?.dirty == true) return RemoteOutcome.DECLINED
        if (existing?.deleted == true) return RemoteOutcome.DECLINED
        store(item, dirty = false, existing = existing)
        return RemoteOutcome.STORED
    }

    override suspend fun deleteDownloaded(entityId: String) {
        val row =
            rowOf(entityId)?.takeIf { !it.deleted }
                ?: access.record(entityId)?.takeIf { it.recordType == recordType && !it.deleted }
                ?: return
        access.put(
            HistoryRecord(
                id = row.id,
                recordType = recordType,
                payload = null,
                deviceId = row.deviceId,
                deviceVersion = row.deviceVersion + 1,
                serverVersion = row.serverVersion,
                deleted = true,
                dirty = false,
            ),
        )
    }

    private suspend fun store(
        item: T,
        dirty: Boolean,
        existing: HistoryRecord? = null,
    ) {
        val row = existing ?: rowOf(idOf(item))
        access.put(
            HistoryRecord(
                id = row?.id ?: rowIdOf(item),
                recordType = recordType,
                payload = access.encode(toPayload(item)),
                deviceId = row?.deviceId ?: access.device(),
                deviceVersion = (row?.deviceVersion ?: 0) + 1,
                serverVersion = row?.serverVersion ?: 0,
                dirty = dirty,
            ),
        )
    }
}

private fun <T : Any> resolverOf(choose: (local: T, remote: T) -> ConflictResolution<T>): ConflictResolver<T> =
    object : ConflictResolver<T> {
        override fun resolve(
            local: T,
            remote: T,
            localTimestamp: Instant,
            remoteTimestamp: Instant,
        ): ConflictResolution<T> = if (local == remote) ConflictResolution.KeepRemote(remote) else choose(local, remote)
    }

private fun <T : Any> kindOf(
    entityType: EntityType,
    mapper: SpaceRecordMapper<T>,
    records: HistoryKindRecords<T>,
): SpaceKind<T> = historyKindOf(entityType, mapper, records)

/** A kind of location history stored as one record for each item. */
internal fun <T : Any> historyKindOf(
    entityType: EntityType,
    mapper: SpaceRecordMapper<T>,
    records: HistoryKindRecords<T>,
    withSyncVersion: (T, Long) -> T = { item, _ -> item },
    // History ids are any lowercase record key, such as a link's `<entry>:<digest>`.
    isItemId: (String) -> Boolean = { id -> id == id.lowercase() && RecordKey.isValid(id) },
): SpaceKind<T> {
    val main = SpacePart.main(mapper)
    return SpaceKind(
        entityType = entityType,
        main = main,
        local = records,
        withSyncVersion = withSyncVersion,
        isItemId = isItemId,
    ) { changes, _ -> (changes.of(main).lastOrNull() as? SpacePartChange.Written)?.value }
}

/**
 * Corrections as a kind of the personal space: an `app.logdate.place.correction` for each one.
 * A correction is never edited, so two copies under one id are the same correction.
 */
fun placeCorrectionSpaceKind(
    history: HistoryScopeProvider,
    store: HistoryRecordStore,
    deviceId: () -> String?,
): SpaceKind<HistoryCorrection> =
    kindOf(
        EntityType.PLACE_CORRECTION,
        PlaceCorrectionRecordMapper,
        SingleHistoryRecords(
            access = HistoryStoreAccess(store, history, deviceId),
            recordType = "correction",
            idOf = { it.id },
            rowIdOf = { it.id },
            toPayload = { HistoryPayload.Correction(it) },
            fromPayload = { (it as? HistoryPayload.Correction)?.value },
            lastUpdated = { it.createdAt ?: epoch },
            prepare = PlaceCorrectionRecordMapper::withoutRedundantSupersedes,
            conflictResolver = resolverOf { _, remote -> ConflictResolution.KeepRemote(remote) },
        ),
    )

/**
 * Visits the person added as a kind of the personal space: an `app.logdate.place.visit` for each.
 * A visit changes only when its place is merged into another, so when two devices disagree the one
 * uploading now wins.
 */
fun placeVisitSpaceKind(
    history: HistoryScopeProvider,
    store: HistoryRecordStore,
    deviceId: () -> String?,
): SpaceKind<ManualVisit> =
    kindOf(
        EntityType.PLACE_VISIT,
        PlaceVisitRecordMapper,
        SingleHistoryRecords(
            access = HistoryStoreAccess(store, history, deviceId),
            recordType = "manual",
            idOf = { it.id },
            rowIdOf = { it.id },
            toPayload = { HistoryPayload.Manual(it) },
            fromPayload = { (it as? HistoryPayload.Manual)?.value },
            lastUpdated = { it.createdAt ?: it.end },
            conflictResolver = resolverOf { local, _ -> ConflictResolution.KeepLocal(local) },
        ),
    )

/**
 * Entries linked to visits as a kind of the personal space: an `app.logdate.place.entryLink` for
 * each entry and visit pair, keyed so that linking the same pair twice writes one record.
 */
fun placeEntryLinkSpaceKind(
    history: HistoryScopeProvider,
    store: HistoryRecordStore,
    deviceId: () -> String?,
): SpaceKind<VisitMemoryLink> =
    kindOf(
        EntityType.PLACE_ENTRY_LINK,
        PlaceEntryLinkRecordMapper,
        SingleHistoryRecords(
            access = HistoryStoreAccess(store, history, deviceId),
            recordType = "memory-link",
            idOf = { canonicalKey(PlaceEntryLinkRecordMapper.rkeyOf(it)) },
            rowIdOf = { PlaceEntryLinkRecordMapper.rkeyOf(it) },
            toPayload = { HistoryPayload.MemoryLink(it) },
            fromPayload = { (it as? HistoryPayload.MemoryLink)?.value },
            lastUpdated = { it.createdAt ?: epoch },
            conflictResolver = resolverOf { _, remote -> ConflictResolution.KeepRemote(remote) },
        ),
    )

/**
 * Journeys as a kind of the personal space: an `app.logdate.place.journey` for each. Nothing writes
 * one on this device yet, so this device only applies and returns the ones other apps write.
 */
fun placeJourneySpaceKind(
    history: HistoryScopeProvider,
    store: HistoryRecordStore,
    deviceId: () -> String?,
): SpaceKind<HistoryJourney> =
    kindOf(
        EntityType.PLACE_JOURNEY,
        PlaceJourneyRecordMapper,
        SingleHistoryRecords(
            access = HistoryStoreAccess(store, history, deviceId),
            recordType = "journey",
            idOf = { it.id },
            rowIdOf = { it.id },
            toPayload = { HistoryPayload.Journey(it) },
            fromPayload = { (it as? HistoryPayload.Journey)?.value },
            lastUpdated = { it.createdAt },
            conflictResolver = resolverOf { _, remote -> ConflictResolution.KeepRemote(remote) },
        ),
    )

private fun canonicalKey(key: String) = key.lowercase()

/**
 * Places as a kind of the personal space: an `app.logdate.place.place` for each id, holding the
 * place the person saved and the place in their location history that share it.
 *
 * Two devices that changed a place meet in [PlaceConflictResolver]. A place deleted anywhere is
 * deleted from both of its tables.
 *
 * @param savedPlaces the places the person saved.
 */
fun placeSpaceKind(
    history: HistoryScopeProvider,
    store: HistoryRecordStore,
    savedPlaces: UserPlaceDao,
    deviceId: () -> String?,
): SpaceKind<MergedPlace> {
    val records = StoredPlaces(HistoryStoreAccess(store, history, deviceId), savedPlaces)
    return kindOf(EntityType.PLACE, PlaceRecordMapper, records)
}

/** Decides between two versions of a place. */
internal object PlaceConflictResolver : ConflictResolver<MergedPlace> {
    override fun resolve(
        local: MergedPlace,
        remote: MergedPlace,
        localTimestamp: Instant,
        remoteTimestamp: Instant,
    ): ConflictResolution<MergedPlace> {
        // Confirming a place is never undone.
        val newer = if (remoteTimestamp > localTimestamp) remote else local
        val older = if (newer === local) remote else local
        val merged =
            newer.copy(
                userConfirmed = local.userConfirmed || remote.userConfirmed,
                locality = newer.locality ?: older.locality,
                externalId = newer.externalId ?: older.externalId,
            )
        return when {
            merged == remote -> ConflictResolution.KeepRemote(remote)
            merged == local -> ConflictResolution.KeepLocal(local)
            else -> ConflictResolution.Merge(merged)
        }
    }
}

private class StoredPlaces(
    private val access: HistoryStoreAccess,
    private val savedPlaces: UserPlaceDao,
) : HistoryKindRecords<MergedPlace> {
    override val conflictResolver: ConflictResolver<MergedPlace> = PlaceConflictResolver

    override fun entityIdOf(item: MergedPlace): String = item.id

    override suspend fun acknowledgeDeleted(entityId: String) {
        val row = access.record(entityId)?.takeIf { it.recordType == PLACE && it.deleted && it.dirty } ?: return
        access.acknowledge(row, 0)
    }

    override suspend fun load(entityId: String): MergedPlace? {
        val history = access.record(entityId)?.takeIf { it.recordType == PLACE && !it.deleted }?.let(::historyPlaceOf)
        val saved = savedPlaces.getPlaceById(entityId)
        if (history == null && saved == null) return null
        return MergedPlace.of(saved, history)
    }

    override fun lastUpdatedOf(item: MergedPlace): Instant = item.updatedAt ?: item.savedAt ?: epoch

    override suspend fun acknowledgeUpload(
        item: MergedPlace,
        seq: Long,
        syncedAt: Instant,
    ) {
        // A place changed since it was read is a newer edit, and stays to upload.
        if (load(item.id) != item) return
        val row = access.record(item.id)?.takeIf { it.recordType == PLACE }
        when {
            // A saved place that location history never saw gets its history view now, as every place has one.
            row == null -> store(item, dirty = false, seq = seq)

            row.dirty -> access.acknowledge(row, seq)
        }
    }

    override suspend fun replaceLocal(item: MergedPlace) = store(item, dirty = true)

    override suspend fun applyDownloaded(item: MergedPlace): RemoteOutcome {
        val existing = access.record(item.id)?.takeIf { it.recordType == PLACE }
        if (existing?.dirty == true || existing?.deleted == true) return RemoteOutcome.DECLINED
        store(item, dirty = false)
        return RemoteOutcome.STORED
    }

    override suspend fun deleteDownloaded(entityId: String) {
        val row = access.record(entityId)?.takeIf { it.recordType == PLACE && !it.deleted }
        if (row != null) {
            access.put(
                HistoryRecord(entityId, PLACE, null, row.deviceId, row.deviceVersion + 1, row.serverVersion, deleted = true, dirty = false),
            )
        }
        savedPlaces.deletePlaceById(entityId)
    }

    private suspend fun store(
        item: MergedPlace,
        dirty: Boolean,
        seq: Long = 0,
    ) {
        val row = access.record(item.id)?.takeIf { it.recordType == PLACE }
        access.put(
            HistoryRecord(
                id = item.id,
                recordType = PLACE,
                payload = access.encode(HistoryPayload.Place(item.historyPlace())),
                deviceId = row?.deviceId ?: access.device(),
                deviceVersion = (row?.deviceVersion ?: 0) + 1,
                serverVersion = maxOf(seq, row?.serverVersion ?: 0),
                dirty = dirty,
            ),
        )
        item.savedPlace()?.let { savedPlaces.insertPlace(it) }
    }

    private fun historyPlaceOf(record: HistoryRecord): SemanticPlace? = (access.payloadOf(record) as? HistoryPayload.Place)?.value

    private companion object {
        const val PLACE = "place"
    }
}
