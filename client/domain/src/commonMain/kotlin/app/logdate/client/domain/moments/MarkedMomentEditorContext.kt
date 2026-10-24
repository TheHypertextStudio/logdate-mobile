package app.logdate.client.domain.moments

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.moments.MarkedMoment

internal fun List<JournalNote>.withMarkedCapture(mark: MarkedMoment): List<JournalNote> =
    map { note ->
        val location =
            note.location ?: mark.location?.let {
                app.logdate.client.repository.journals.NoteLocation(
                    coordinates =
                        app.logdate.client.repository.journals.NoteCoordinates(
                            it.latitude,
                            it.longitude,
                            accuracy = it.accuracyMeters,
                        ),
                )
            }
        val activity = mark.capturedDuring ?: note.capturedDuring
        when (note) {
            is JournalNote.Handwriting -> {
                note.copy(
                    creationTimestamp = mark.capturedAt,
                    timeZoneId = mark.timeZoneId,
                    location = location,
                    capturedDuring = activity,
                )
            }

            is JournalNote.Text -> {
                note.copy(
                    creationTimestamp = mark.capturedAt,
                    timeZoneId = mark.timeZoneId,
                    location = location,
                    capturedDuring = activity,
                )
            }

            is JournalNote.Image -> {
                note.copy(
                    creationTimestamp = mark.capturedAt,
                    timeZoneId = mark.timeZoneId,
                    location = location,
                    capturedDuring = activity,
                )
            }

            is JournalNote.Audio -> {
                note.copy(
                    creationTimestamp = mark.capturedAt,
                    timeZoneId = mark.timeZoneId,
                    location = location,
                    capturedDuring = activity,
                )
            }

            is JournalNote.Video -> {
                note.copy(
                    creationTimestamp = mark.capturedAt,
                    timeZoneId = mark.timeZoneId,
                    location = location,
                    capturedDuring = activity,
                )
            }
        }
    }
