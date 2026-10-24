package app.logdate.feature.core.export

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.androidMediaFileResolver
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Source
import okio.source
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens the media references Android stores: content URIs, absolute paths and `file://` URLs.
 *
 * A reference that no longer points at a file is looked up again in the places older builds stored
 * media (see [MediaReferenceRecovery]); if it is nowhere, the file is reported as missing.
 */
class AndroidMediaSourceOpener(
    private val context: Context,
    private val mediaFiles: MediaFileResolver = androidMediaFileResolver(context),
) : MediaSourceOpener {
    override suspend fun open(reference: String): Source? = withContext(Dispatchers.IO) { openStream(reference)?.source() }

    private fun openStream(reference: String): InputStream? {
        val path = mediaFiles.filePath(reference) ?: return openContent(reference.toUri())

        val original = File(path)
        if (original.exists()) return original.inputStream()
        return recover(original)?.also { Napier.w("Recovered a stale media reference for export") }
    }

    private fun recover(original: File): InputStream? {
        val renamed = File(original.parentFile, MediaReferenceRecovery.withoutDoubledExtension(original.name))
        if (renamed != original && renamed.exists()) return renamed.inputStream()

        MediaReferenceRecovery.recordingFileName(original.name)?.let { recording ->
            val audio = File(context.filesDir, "audio_notes/$recording")
            if (audio.exists()) return audio.inputStream()
        }

        appPrivateCopy(original)?.let { return it.inputStream() }
        return mediaStoreCandidates(original.name).firstNotNullOfOrNull { openContent(it) }
    }

    private fun appPrivateCopy(original: File): File? {
        val candidates = File(context.filesDir, "user_media").listFiles().orEmpty()
        val keys = MediaReferenceRecovery.matchKeys(original.name)
        return candidates.firstOrNull { MediaReferenceRecovery.matchesAny(it.name, keys) }
    }

    private fun mediaStoreCandidates(fileName: String): List<Uri> {
        val id = MediaReferenceRecovery.legacyMediaStoreId(fileName) ?: return emptyList()
        val collections =
            when (MediaReferenceRecovery.collectionFor(fileName.substringAfterLast('.', ""))) {
                MediaReferenceRecovery.Collection.IMAGES -> {
                    listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }

                MediaReferenceRecovery.Collection.VIDEO -> {
                    listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                }

                MediaReferenceRecovery.Collection.AUDIO -> {
                    listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                }

                null -> {
                    listOf(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    )
                }
            }
        return collections.map { Uri.withAppendedPath(it, id) }
    }

    private fun openContent(uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Napier.w("A media file could not be opened for export")
            null
        }
}
