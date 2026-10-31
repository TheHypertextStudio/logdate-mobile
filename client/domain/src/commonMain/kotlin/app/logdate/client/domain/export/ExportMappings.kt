package app.logdate.client.domain.export

import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.location.LocationHistoryItem

internal fun NoteLocation.toExportLocation(): ExportLocation? {
    val lat = effectiveLatitude ?: return null
    val lng = effectiveLongitude ?: return null
    return ExportLocation(
        latitude = lat,
        longitude = lng,
        placeName = displayName,
        altitude = coordinates?.altitude,
        accuracy = coordinates?.accuracy,
    )
}

internal fun LocationHistoryItem.toExportLocationHistoryItem(): ExportLocationHistoryItem =
    ExportLocationHistoryItem(
        sampleId = sampleId,
        userId = userId,
        deviceId = deviceId,
        timestamp = timestamp,
        loggedAt = loggedAt,
        latitude = location.latitude,
        longitude = location.longitude,
        altitudeMeters = location.altitude.value,
        confidence = confidence,
        isGenuine = isGenuine,
        capturePipeline = capturePipeline.name,
        captureSource = captureSource.name,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        isMock = isMock,
    )
