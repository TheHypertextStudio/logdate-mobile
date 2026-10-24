package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import kotlin.time.Instant

internal fun Instant.wirePrecision(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())

internal fun JournalNote.withVersion(version: Long): JournalNote =
    when (this) {
        is JournalNote.Handwriting -> copy(syncVersion = version)
        is JournalNote.Text -> copy(syncVersion = version)
        is JournalNote.Image -> copy(syncVersion = version)
        is JournalNote.Video -> copy(syncVersion = version)
        is JournalNote.Audio -> copy(syncVersion = version)
    }

/**
 * Compare only the fields the content contract sends, after resolving cached media. The capture time
 * zone, device and activity are left out: a server that predates them returns none, and none of them
 * changes after capture. Audio working text is local-only and is never part of this comparison.
 */
internal fun JournalNote.uploadedFields(
    version: Long,
    ref: String?,
): JournalNote {
    val created = creationTimestamp.wirePrecision()
    val updated = lastUpdated.wirePrecision()
    val uploadedLocation = location?.takeIf { it.hasLocation }
    return when (this) {
        is JournalNote.Handwriting -> {
            uploadedHandwritingFields(version)
        }

        is JournalNote.Text -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                location = uploadedLocation,
                timeZoneId = null,
                capturedOn = null,
                capturedDuring = null,
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
                capturedOn = null,
                capturedDuring = null,
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
                capturedOn = null,
                capturedDuring = null,
            )
        }

        is JournalNote.Audio -> {
            copy(
                syncVersion = version,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = ref.orEmpty(),
                transcription = "",
                location = uploadedLocation,
                timeZoneId = null,
                capturedOn = null,
                capturedDuring = null,
            )
        }
    }
}

private fun JournalNote.Handwriting.uploadedHandwritingFields(version: Long): JournalNote.Handwriting =
    copy(
        syncVersion = version,
        creationTimestamp = creationTimestamp.wirePrecision(),
        lastUpdated = lastUpdated.wirePrecision(),
        location = location?.takeIf { it.hasLocation },
        timeZoneId = null,
        capturedOn = null,
        capturedDuring = null,
    )
