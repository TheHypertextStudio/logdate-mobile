package app.logdate.client.repository.location

import kotlinx.coroutines.flow.Flow

/**
 * Local outbox and downloaded records. Sensitive payloads are encrypted before transport.
 *
 * @property observedAtMillis when a sample or activity change happened, kept after its payload is
 *   removed by a deletion so the record can still be found by the hour it belongs to. Null for
 *   other records, and for a deleted record this device never knew the time of.
 */
data class HistoryRecord(
    val id: String,
    val recordType: String,
    val payload: String?,
    val deviceId: String,
    val deviceVersion: Long = 1,
    val serverVersion: Long = 0,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
    val observedAtMillis: Long? = null,
)

interface HistoryRecordStore {
    fun observe(
        ownerId: String,
        origin: String,
    ): Flow<List<HistoryRecord>>

    fun observeRange(
        ownerId: String,
        origin: String,
        startMillis: Long,
        endMillis: Long,
    ): Flow<List<HistoryRecord>> = observe(ownerId, origin)

    suspend fun records(
        ownerId: String,
        origin: String,
    ): List<HistoryRecord>

    suspend fun record(
        ownerId: String,
        origin: String,
        id: String,
    ): HistoryRecord? = records(ownerId, origin).firstOrNull { it.id == id }

    /** Every record of [recordType], deleted ones included. */
    suspend fun recordsOfType(
        ownerId: String,
        origin: String,
        recordType: String,
    ): List<HistoryRecord> = records(ownerId, origin).filter { it.recordType == recordType }

    /**
     * Every [recordType] record [deviceId] observed from [startMillis] up to but not including
     * [endMillis], deleted ones included. A deleted record is found by the time it kept.
     */
    suspend fun recordsBetween(
        ownerId: String,
        origin: String,
        recordType: String,
        deviceId: String,
        startMillis: Long,
        endMillis: Long,
    ): List<HistoryRecord> =
        records(ownerId, origin).filter {
            it.recordType == recordType &&
                it.deviceId == deviceId &&
                it.observedAtMillis != null &&
                it.observedAtMillis in startMillis until endMillis
        }

    suspend fun put(
        ownerId: String,
        origin: String,
        record: HistoryRecord,
    )

    /** Persists a local edit batch; durable stores must commit the whole batch atomically. */
    suspend fun putBatch(
        ownerId: String,
        origin: String,
        records: List<HistoryRecord>,
    ) {
        error("Atomic local batches are not supported by this history store")
    }

    suspend fun observationsBefore(
        ownerId: String,
        origin: String,
        deviceId: String,
        beforeMillis: Long,
        beforeSampleId: String,
        limit: Int = 256,
    ): List<HistoryRecord> = emptyList()

    suspend fun activitiesBetween(
        ownerId: String,
        origin: String,
        deviceId: String,
        startMillis: Long,
        endMillis: Long,
    ): List<HistoryRecord> = emptyList()

    suspend fun latestActivityBefore(
        ownerId: String,
        origin: String,
        deviceId: String,
        beforeMillis: Long,
    ): HistoryRecord? = null

    suspend fun recordedObservationTimestamps(
        ownerId: String,
        origin: String,
        startMillis: Long,
        endMillis: Long,
    ): List<Long> = emptyList()

    suspend fun pending(
        ownerId: String,
        origin: String,
        limit: Int = 100,
    ): List<HistoryRecord>

    suspend fun cursor(
        ownerId: String,
        origin: String,
    ): Long

    /** Apply page and cursor atomically. Never replace a dirty local edit or revive a tombstone. */
    suspend fun applyPage(
        ownerId: String,
        origin: String,
        records: List<HistoryRecord>,
        cursor: Long,
    )

    /** Acknowledge only the version actually uploaded; preserve edits made during upload. */
    suspend fun acknowledge(
        ownerId: String,
        origin: String,
        id: String,
        deviceVersion: Long,
        serverVersion: Long,
    )
}
