package app.logdate.feature.location.timeline.ui

import app.logdate.client.domain.location.LocationStop
import app.logdate.client.domain.location.LocationStopEvidenceKind
import app.logdate.client.location.places.GeocodedAddress
import app.logdate.client.repository.journals.NoteType
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.model.LocationMemoryKind
import app.logdate.util.localTime
import app.logdate.util.toReadableDateShort
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.places_near_street
import logdate.client.feature.location.timeline.generated.resources.places_observed_here
import logdate.client.feature.location.timeline.generated.resources.places_somewhere_in
import logdate.client.feature.location.timeline.generated.resources.places_stayed_days_hours
import logdate.client.feature.location.timeline.generated.resources.places_stayed_hours_minutes
import logdate.client.feature.location.timeline.generated.resources.places_stayed_minutes
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal fun formatCoarseLocation(address: GeocodedAddress): UiText =
    when {
        address.thoroughfare != null && address.locality != null -> {
            Res.string.places_near_street.toUiText(address.thoroughfare.orEmpty(), address.locality.orEmpty())
        }

        address.subLocality != null && address.locality != null -> {
            "${address.subLocality}, ${address.locality}".toUiText()
        }

        address.locality != null -> {
            Res.string.places_somewhere_in.toUiText(address.locality.orEmpty())
        }

        address.adminArea != null -> {
            Res.string.places_somewhere_in.toUiText(address.adminArea.orEmpty())
        }

        else -> {
            "".toUiText()
        }
    }

internal fun NoteType.toUiKind(): LocationMemoryKind =
    when (this) {
        NoteType.HANDWRITING -> LocationMemoryKind.TEXT
        NoteType.TEXT -> LocationMemoryKind.TEXT
        NoteType.IMAGE -> LocationMemoryKind.PHOTO
        NoteType.AUDIO -> LocationMemoryKind.AUDIO
        NoteType.VIDEO -> LocationMemoryKind.VIDEO
        NoteType.LOCATION -> LocationMemoryKind.TEXT
    }

internal fun formatTimeRange(
    startTime: Instant,
    endTime: Instant,
): String {
    val sameDay = startTime.toReadableDateShort() == endTime.toReadableDateShort()
    return if (sameDay) {
        "${startTime.localTime} - ${endTime.localTime}"
    } else {
        "${startTime.toReadableDateShort()}, ${startTime.localTime} - ${endTime.toReadableDateShort()}, ${endTime.localTime}"
    }
}

internal fun formatEvidence(stop: LocationStop): UiText =
    when (stop.evidenceKind) {
        LocationStopEvidenceKind.STAY -> formatDuration(stop.duration)
        LocationStopEvidenceKind.OBSERVATION -> Res.string.places_observed_here.toUiText()
    }

private fun formatDuration(duration: Duration): UiText =
    when {
        duration >= 1.days -> Res.string.places_stayed_days_hours.toUiText(duration.inWholeDays, duration.inWholeHours % 24)
        duration >= 1.hours -> Res.string.places_stayed_hours_minutes.toUiText(duration.inWholeHours, duration.inWholeMinutes % 60)
        duration >= 1.minutes -> Res.string.places_stayed_minutes.toUiText(duration.inWholeMinutes)
        else -> Res.string.places_observed_here.toUiText()
    }

internal fun calculateDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val earthRadius = 6371000.0
    val dLat = (lat2 - lat1) * PI / 180
    val dLon = (lon2 - lon1) * PI / 180
    val a =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadius * c
}

internal fun formatCoordinates(
    latitude: Double,
    longitude: Double,
): String = "${formatCoordinateValue(latitude)}, ${formatCoordinateValue(longitude)}"
