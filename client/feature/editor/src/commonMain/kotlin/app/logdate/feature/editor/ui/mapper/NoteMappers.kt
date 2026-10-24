package app.logdate.feature.editor.ui.mapper

import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.handwriting.toDocument
import app.logdate.feature.editor.ui.handwriting.toPage
import kotlin.time.Clock

/**
 * Maps a JournalNote domain model to an EntryBlockData UI model.
 * This mapper converts notes from the domain layer to the UI representation
 * needed for the editor.
 */
fun JournalNote.toDomainBlock(): EntryBlockUiState =
    when (this) {
        is JournalNote.Handwriting -> {
            require(unreadableContent == null) { "Handwriting cannot be opened; the original ink is preserved." }
            HandwritingBlockUiState(
                id = uid,
                timestamp = creationTimestamp,
                page = document.toPage(),
                timeZoneId = timeZoneId,
                noteLocation = location,
                capturedOn = capturedOn,
                syncVersion = syncVersion,
            )
        }

        is JournalNote.Text -> {
            TextBlockUiState(
                id = uid,
                timestamp = creationTimestamp,
                location = null, // We don't have location data from notes
                content = content,
                timeZoneId = timeZoneId,
            )
        }

        is JournalNote.Image -> {
            ImageBlockUiState(
                id = uid,
                timestamp = creationTimestamp,
                location = null,
                uri = mediaRef,
                caption = caption,
                presentation = presentation,
                timeZoneId = timeZoneId,
            )
        }

        is JournalNote.Video -> {
            VideoBlockUiState(
                id = uid,
                timestamp = creationTimestamp,
                location = null,
                uri = mediaRef,
                caption = caption,
                timeZoneId = timeZoneId,
            )
        }

        is JournalNote.Audio -> {
            AudioBlockUiState(
                id = uid,
                timestamp = creationTimestamp,
                location = null,
                captureState = AudioCaptureState.Ready(uri = mediaRef, durationMs = durationMs),
                caption = caption,
                transcription = transcription,
                timeZoneId = timeZoneId,
            )
        }
    }

/**
 * Maps an EntryBlockData UI model to a JournalNote domain model.
 * This mapper converts UI blocks to domain notes for saving.
 */
fun EntryBlockUiState.toJournalNote(): JournalNote? {
    val now = Clock.System.now()

    return when (this) {
        is HandwritingBlockUiState -> {
            if (!hasContent()) return null
            JournalNote.Handwriting(
                uid = id,
                creationTimestamp = timestamp,
                lastUpdated = now,
                document = page.toDocument(),
                timeZoneId = timeZoneId,
                location = noteLocation,
                capturedOn = capturedOn,
                syncVersion = syncVersion,
            )
        }

        is TextBlockUiState -> {
            if (!hasContent()) return null
            JournalNote.Text(
                uid = id,
                creationTimestamp = timestamp,
                lastUpdated = now,
                content = content,
                timeZoneId = timeZoneId,
            )
        }

        is ImageBlockUiState -> {
            if (!hasContent()) return null
            JournalNote.Image(
                uid = id,
                creationTimestamp = timestamp,
                lastUpdated = now,
                mediaRef = uri ?: return null,
                caption = caption,
                presentation = presentation,
                timeZoneId = timeZoneId,
            )
        }

        is CameraBlockUiState -> {
            if (!hasContent()) return null
            val mediaRef = uri ?: return null
            when (mediaType) {
                CapturedMediaType.PHOTO -> {
                    JournalNote.Image(
                        uid = id,
                        creationTimestamp = timestamp,
                        lastUpdated = now,
                        mediaRef = mediaRef,
                        caption = caption,
                        timeZoneId = timeZoneId,
                    )
                }

                CapturedMediaType.VIDEO -> {
                    JournalNote.Video(
                        uid = id,
                        creationTimestamp = timestamp,
                        lastUpdated = now,
                        mediaRef = mediaRef,
                        caption = caption,
                        timeZoneId = timeZoneId,
                    )
                }
            }
        }

        is VideoBlockUiState -> {
            if (!hasContent()) return null
            JournalNote.Video(
                uid = id,
                creationTimestamp = timestamp,
                lastUpdated = now,
                mediaRef = uri ?: return null,
                caption = caption,
                timeZoneId = timeZoneId,
            )
        }

        is AudioBlockUiState -> {
            val ready = captureState as? AudioCaptureState.Ready ?: return null
            JournalNote.Audio(
                uid = id,
                creationTimestamp = timestamp,
                lastUpdated = now,
                mediaRef = ready.uri,
                durationMs = ready.durationMs,
                caption = caption,
                transcription = transcription,
                timeZoneId = timeZoneId,
            )
        }
    }
}
