package app.logdate.server.migration

import app.logdate.server.crypto.sha256Hex
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.sync.LocationHistoryRepository
import app.logdate.shared.model.sync.LegacyInventory
import app.logdate.shared.model.sync.LegacyKindInventory
import app.logdate.shared.model.sync.LegacyRecordKinds
import app.logdate.shared.model.sync.SpaceMigrationDigests
import java.util.UUID

private const val LOCATION_HISTORY_PAGE = 100

/** Every kind legacy storage keeps, as the inventory names it, and the collection that holds it. */
private val COLLECTION_KINDS =
    mapOf(
        LegacyRecordKinds.ENTRIES to LogDateCollectionKind.ENTRY,
        LegacyRecordKinds.JOURNALS to LogDateCollectionKind.JOURNAL,
        LegacyRecordKinds.ASSOCIATIONS to LogDateCollectionKind.ASSOCIATION,
        LegacyRecordKinds.DRAFTS to LogDateCollectionKind.DRAFT,
        LegacyRecordKinds.MOMENTS to LogDateCollectionKind.MOMENT,
    )

/**
 * Reads what legacy storage holds for an account: the live record ids of each kind and a watermark
 * that changes whenever anything in legacy storage changes. Ids are lowercase, and a membership is
 * `<journal id>:<entry id>`, so they compare directly with a device's own.
 */
internal class LegacyInventoryReader(
    private val metadata: LogDateCollectionsMetadataStore,
    private val locationHistory: LocationHistoryRepository,
    private val media: LogDateMediaBlobRepository,
) {
    /** The account's inventory, with the ids themselves for [idsOf] when it names a kind. */
    suspend fun read(
        accountId: UUID,
        idsOf: String? = null,
    ): LegacyInventory {
        val history = locationHistory(accountId)
        val kinds =
            LegacyRecordKinds.ALL.map { kind ->
                val ids =
                    COLLECTION_KINDS[kind]?.let { collection -> collectionIds(accountId, kind, collection) } ?: history.liveIds
                LegacyKindInventory(kind, ids.size, digestOf(ids), ids.sorted().takeIf { kind == idsOf })
            }
        val collectionVersion = metadata.state(accountId)?.lastVersion ?: 0L
        val mediaRows = media.listMedia(accountId)
        val watermark =
            listOf(
                "c:$collectionVersion",
                "l:${history.lastVersion}:${history.recordCount}",
                "m:${mediaRows.maxOfOrNull { it.version } ?: 0}:${mediaRows.size}",
            ).joinToString("|")
        return LegacyInventory(watermark, kinds)
    }

    /** The live record count of every kind, for checking a device's proof at the switch. */
    suspend fun counts(accountId: UUID): Map<String, Int> = read(accountId).kinds.associate { it.kind to it.count }

    private suspend fun collectionIds(
        accountId: UUID,
        kind: String,
        collection: LogDateCollectionKind,
    ): List<String> =
        metadata.listLive(accountId, collection).map { row ->
            val key = row.recordKey.lowercase()
            if (kind == LegacyRecordKinds.ASSOCIATIONS) key.replace('~', ':') else key
        }

    private class History(
        val liveIds: List<String>,
        val lastVersion: Long,
        val recordCount: Int,
    )

    private fun locationHistory(accountId: UUID): History {
        val live = mutableMapOf<String, Boolean>()
        var cursor = 0L
        var lastVersion = 0L
        do {
            val page = locationHistory.changes(accountId, cursor, LOCATION_HISTORY_PAGE)
            page.records.forEach { record ->
                live[record.id.lowercase()] = !record.deleted
                lastVersion = maxOf(lastVersion, record.serverVersion)
            }
            cursor = page.nextCursor
        } while (page.hasMore)
        return History(live.filterValues { it }.keys.toList(), lastVersion, live.size)
    }
}

private fun digestOf(ids: Collection<String>): String = sha256Hex(SpaceMigrationDigests.idsText(ids))
