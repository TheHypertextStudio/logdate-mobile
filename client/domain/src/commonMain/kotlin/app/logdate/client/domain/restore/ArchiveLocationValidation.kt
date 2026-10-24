package app.logdate.client.domain.restore

import app.logdate.client.domain.export.archive.ArchiveNoteLocation
import kotlin.uuid.Uuid

/** Validate the entire archive before a malformed later record can leave an earlier record applied. */
internal fun ArchiveNoteLocation.validateForRestore() {
    coordinates?.let {
        validateCoordinates(it.latitude, it.longitude)
        require(it.altitudeMeters == null || it.altitudeMeters.isFinite()) { "Invalid note altitude" }
        require(it.accuracyMeters == null || (it.accuracyMeters >= 0 && it.accuracyMeters.toFloat().isFinite())) {
            "Invalid note accuracy"
        }
    }
    place?.let {
        Uuid.parse(it.id)
        require((it.latitude == null) == (it.longitude == null)) { "Incomplete place center" }
        if (it.latitude != null && it.longitude != null) validateCoordinates(it.latitude, it.longitude)
    }
}

private fun validateCoordinates(
    latitude: Double,
    longitude: Double,
) {
    require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid note latitude" }
    require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid note longitude" }
}
