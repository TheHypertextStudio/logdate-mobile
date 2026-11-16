package app.logdate.client.data.location

import app.logdate.client.data.LenientJson
import app.logdate.client.database.dao.HistoryRecordDao
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.shared.model.location.HistoryPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomHistoryRecordStore(
    private val dao: HistoryRecordDao,
) : HistoryRecordStore {
    override fun observe(
        ownerId: String,
        origin: String,
    ): Flow<List<HistoryRecord>> = dao.observe(ownerId, origin).map { rows -> rows.map { it.toRecord() } }

    override fun observeRange(
        ownerId: String,
        origin: String,
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<HistoryRecord>> = dao.observeRange(ownerId, origin, startMillis, endMillis).map { rows -> rows.map { it.toRecord() } }

    override suspend fun records(
        ownerId: String,
        origin: String,
    ): List<HistoryRecord> = dao.records(ownerId, origin).map { it.toRecord() }

    override suspend fun record(
        ownerId: String,
        origin: String,
        id: String,
    ): HistoryRecord? = dao.record(ownerId, origin, id)?.toRecord()

    override suspend fun recordsOfType(
        ownerId: String,
        origin: String,
        recordType: String,
    ): List<HistoryRecord> = dao.recordsOfType(ownerId, origin, recordType).map { it.toRecord() }

    override suspend fun recordsBetween(
        ownerId: String,
        origin: String,
        recordType: String,
        deviceId: String,
        startMillis: Long,
        endMillis: Long,
    ): List<HistoryRecord> = dao.recordsBetween(ownerId, origin, recordType, deviceId, startMillis, endMillis).map { it.toRecord() }

    override suspend fun put(
        ownerId: String,
        origin: String,
        record: HistoryRecord,
    ) {
        dao.putPreservingTombstone(record.toEntity(ownerId, origin))
    }

    override suspend fun putBatch(
        ownerId: String,
        origin: String,
        records: List<HistoryRecord>,
    ) {
        dao.putBatchPreservingTombstones(records.map { it.toEntity(ownerId, origin) })
    }

    override suspend fun observationsBefore(
        ownerId: String,
        origin: String,
        deviceId: String,
        beforeMillis: Long,
        beforeSampleId: String,
        limit: Int,
    ): List<HistoryRecord> =
        dao
            .observationsBefore(
                ownerId,
                origin,
                deviceId,
                beforeMillis,
                "observation:$beforeSampleId",
                limit.coerceIn(1, 256),
            ).map { it.toRecord() }

    override suspend fun activitiesBetween(
        ownerId: String,
        origin: String,
        deviceId: String,
        startMillis: Long,
        endMillis: Long,
    ): List<HistoryRecord> = dao.activitiesBetween(ownerId, origin, deviceId, startMillis, endMillis).map { it.toRecord() }

    override suspend fun pending(
        ownerId: String,
        origin: String,
        limit: Int,
    ): List<HistoryRecord> = dao.pending(ownerId, origin, limit).map { it.toRecord() }

    override suspend fun cursor(
        ownerId: String,
        origin: String,
    ): Long = dao.cursor(ownerId, origin) ?: 0

    override suspend fun applyPage(
        ownerId: String,
        origin: String,
        records: List<HistoryRecord>,
        cursor: Long,
    ) {
        dao.applyPage(ownerId, origin, records.map { it.copy(dirty = false).toEntity(ownerId, origin) }, cursor)
    }

    override suspend fun acknowledge(
        ownerId: String,
        origin: String,
        id: String,
        deviceVersion: Long,
        serverVersion: Long,
    ) {
        dao.acknowledge(ownerId, origin, id, deviceVersion, serverVersion)
    }
}

private fun HistoryRecordEntity.toRecord() =
    HistoryRecord(id, recordType, payload, deviceId, deviceVersion, serverVersion, deleted, dirty, observedAt)

private fun HistoryRecord.toEntity(
    owner: String,
    origin: String,
) = HistoryRecordEntity(
    owner,
    origin,
    id,
    recordType,
    payload,
    deviceId,
    deviceVersion,
    serverVersion,
    deleted,
    dirty,
    observationTime() ?: observedAtMillis,
)

private fun HistoryRecord.observationTime(): Long? =
    payload?.let {
        when (val value = runCatching { LenientJson.decodeFromString(HistoryPayload.serializer(), it) }.getOrNull()) {
            is HistoryPayload.Observation -> value.value.timestamp.toEpochMilliseconds()
            is HistoryPayload.Activity -> value.value.timestamp.toEpochMilliseconds()
            else -> null
        }
    }
