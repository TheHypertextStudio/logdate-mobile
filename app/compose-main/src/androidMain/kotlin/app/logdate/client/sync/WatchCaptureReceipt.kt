package app.logdate.client.sync

import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.audioDigest
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A timestamp is insufficient proof when another edit can have the same version. */
object WatchCaptureReceipt {
    fun sameContent(
        stored: JournalNote,
        incoming: JournalNote,
    ): Boolean =
        when {
            stored is JournalNote.Text && incoming is JournalNote.Text -> {
                stored.copy(syncVersion = 0, capturedOn = stored.capturedOn ?: CaptureDevice.WATCH) ==
                    incoming.copy(syncVersion = 0, capturedOn = incoming.capturedOn ?: CaptureDevice.WATCH)
            }

            stored is JournalNote.Audio && incoming is JournalNote.Audio -> {
                stored.copy(mediaRef = "", syncVersion = 0, capturedOn = stored.capturedOn ?: CaptureDevice.WATCH) ==
                    incoming.copy(mediaRef = "", syncVersion = 0, capturedOn = incoming.capturedOn ?: CaptureDevice.WATCH)
            }

            else -> {
                false
            }
        }

    suspend fun existingAudioMatches(
        stored: JournalNote,
        data: Map<String, String>?,
        filePath: (String) -> String? = { it },
    ): Boolean {
        if (stored !is JournalNote.Audio || data == null) return false
        return try {
            val incoming = NoteDataMapper().fromDataMap(data)
            val expected = NoteDataMapper.audioDigest(data) ?: return false
            if (!sameContent(stored, incoming)) return false
            val path = filePath(stored.mediaRef) ?: return false
            withContext(Dispatchers.IO) { File(path).audioDigest() == expected }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Cannot prove that the stored recording matches this watch capture", e)
            false
        }
    }
}
