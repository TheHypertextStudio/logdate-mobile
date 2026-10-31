package app.logdate.client.data.location

import app.logdate.client.data.journals.ArchiveRecoveryReceipts
import app.logdate.client.data.journals.DirectArchiveRecoveryTransactions
import app.logdate.client.database.dao.LocationActivityDao
import app.logdate.client.database.dao.LocationHistoryDao
import app.logdate.client.database.entities.Coordinates
import app.logdate.client.database.entities.LocationLogEntity
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.util.runCatchingUnlessCancelled
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * Offline-first implementation of location history repository.
 */
class OfflineFirstLocationHistoryRepository(
    private val locationHistoryDao: LocationHistoryDao,
    private val activityDao: LocationActivityDao? = null,
    private val transactionManager: SyncTransactionManager = DirectArchiveRecoveryTransactions,
    private val recoveryReceipts: ArchiveRecoveryReceipts = ArchiveRecoveryReceipts.None,
    private val currentRecoveryScope: suspend () -> JournalMergeScope? = { null },
) : LocationHistoryRepository {
    override suspend fun getAllLocationHistory(): List<LocationHistoryItem> =
        locationHistoryDao.getAllLocationHistory().map {
            it.toDomainModel()
        }

    override fun observeLocationHistory(): Flow<List<LocationHistoryItem>> =
        locationHistoryDao.observeAllLocationHistory().map { entities ->
            entities.map { it.toDomainModel() }
        }

    override fun observeLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<LocationHistoryItem>> =
        locationHistoryDao.observeLocationHistoryBetween(startTime, endTime).map { entities -> entities.map { it.toDomainModel() } }

    override suspend fun getLocationHistoryPage(
        userId: String,
        deviceId: String,
        afterLoggedAt: Instant,
        afterSampleId: String,
        limit: Int,
    ): List<LocationHistoryItem> =
        locationHistoryDao
            .getPage(userId, deviceId, afterLoggedAt, afterSampleId, limit.coerceIn(1, 1000))
            .map { it.toDomainModel() }

    override suspend fun getLocationHistoryBefore(
        userId: String,
        deviceId: String,
        before: Instant,
        beforeSampleId: String,
        limit: Int,
    ): List<LocationHistoryItem> =
        locationHistoryDao.before(userId, deviceId, before, beforeSampleId, limit.coerceIn(1, 256)).map { it.toDomainModel() }

    override suspend fun getRecentLocationHistory(limit: Int): List<LocationHistoryItem> =
        locationHistoryDao.getRecentLocationHistory(limit).map {
            it.toDomainModel()
        }

    override suspend fun getLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): List<LocationHistoryItem> =
        locationHistoryDao
            .getLocationHistoryBetween(startTime, endTime)
            .map { it.toDomainModel() }

    override suspend fun getLastLocation(): LocationHistoryItem? = locationHistoryDao.getLastLocation()?.toDomainModel()

    override fun observeLastLocation(): Flow<LocationHistoryItem?> = locationHistoryDao.observeLastLocation().map { it?.toDomainModel() }

    override suspend fun logLocation(
        location: Location,
        userId: String,
        deviceId: String,
        confidence: Float,
        isGenuine: Boolean,
    ): Result<Unit> =
        logLocation(
            LocationLogRecord(
                userId = userId,
                deviceId = deviceId,
                timestamp =
                    Instant.fromEpochMilliseconds(
                        kotlin.time.Clock.System
                            .now()
                            .toEpochMilliseconds(),
                    ),
                loggedAt =
                    kotlin.time.Clock.System
                        .now(),
                location = location,
                confidence = confidence,
                isGenuine = isGenuine,
            ),
        )

    override suspend fun logLocation(record: LocationLogRecord): Result<Unit> =
        runCatchingUnlessCancelled { locationHistoryDao.addLocationLog(record.toEntity()) }

    override suspend fun logLocations(records: List<LocationLogRecord>): Result<Unit> =
        runCatchingUnlessCancelled { locationHistoryDao.addLocationLogs(records.map { it.toEntity() }) }

    private fun LocationLogRecord.toEntity() =
        LocationLogEntity(
            sampleId = sampleId,
            userId = userId,
            deviceId = deviceId,
            timestamp = timestamp,
            loggedAt = loggedAt,
            location =
                Coordinates(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude.value,
                ),
            confidence = confidence,
            isGenuine = isGenuine,
            capturePipeline = capturePipeline.name,
            captureSource = captureSource.name,
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            bearingDegrees = bearingDegrees,
            isMock = isMock,
            activityType = activityType,
            timeZoneId = timeZoneId,
        )

    override suspend fun restoreLocationForArchive(
        record: LocationLogRecord,
        scope: JournalMergeScope?,
        recoveryId: String,
    ): Result<Unit> =
        runCatchingUnlessCancelled {
            transactionManager.withTransaction {
                check(currentRecoveryScope() == scope) { "Location recovery account changed" }
                if (scope != null && recoveryReceipts.contains(scope, "LOCATION", recoveryId, record.sampleId)) return@withTransaction
                if (locationHistoryDao.getBySampleId(record.sampleId) == null) logLocation(record).getOrThrow()
                if (scope != null) recoveryReceipts.record(scope, "LOCATION", recoveryId, record.sampleId)
                check(currentRecoveryScope() == scope) { "Location recovery account changed" }
            }
        }

    override suspend fun deleteLocationEntry(
        userId: String,
        deviceId: String,
        timestamp: Instant,
    ): Result<Unit> =
        runCatchingUnlessCancelled {
            locationHistoryDao.deleteLocationById(userId, deviceId, timestamp)
        }

    override suspend fun deleteLocationsBetween(
        startTime: Instant,
        endTime: Instant,
    ): Result<Unit> =
        runCatchingUnlessCancelled {
            locationHistoryDao.deleteLogsWithinRange(startTime, endTime)
            activityDao?.deleteBetween(startTime, endTime)
            Unit
        }

    override suspend fun getLocationCount(): Int = locationHistoryDao.getLocationCount()

    private fun LocationLogEntity.toDomainModel(): LocationHistoryItem =
        LocationHistoryItem(
            sampleId = sampleId,
            userId = userId,
            deviceId = deviceId,
            timestamp = timestamp,
            loggedAt = loggedAt,
            location =
                Location(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude =
                        LocationAltitude(
                            value = location.altitude,
                            units = AltitudeUnit.METERS,
                        ),
                ),
            confidence = confidence,
            isGenuine = isGenuine,
            capturePipeline = capturePipeline.toCapturePipeline(),
            captureSource = captureSource.toCaptureSource(),
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            bearingDegrees = bearingDegrees,
            isMock = isMock,
            activityType = activityType,
            timeZoneId = timeZoneId,
        )

    private fun String.toCapturePipeline(): LocationCapturePipeline =
        runCatching {
            LocationCapturePipeline.valueOf(this)
        }.getOrElse {
            Napier.w("Unknown location capture pipeline '$this'; falling back to LEGACY")
            LocationCapturePipeline.LEGACY
        }

    private fun String.toCaptureSource(): LocationCaptureSource =
        runCatching {
            LocationCaptureSource.valueOf(this)
        }.getOrElse {
            Napier.w("Unknown location capture source '$this'; falling back to BACKGROUND_PERIODIC")
            LocationCaptureSource.BACKGROUND_PERIODIC
        }
}
