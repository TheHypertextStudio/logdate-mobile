package app.logdate.feature.core.export

import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.desktopMediaFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Source
import okio.source
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * Opens the media references the desktop app stores: `logdate-media://` references, absolute
 * paths, Windows drive paths and `file:` URLs. Anything else, including a network address, is
 * treated as missing, since an export never fetches from the network.
 */
class DesktopMediaSourceOpener(
    private val mediaFiles: MediaFileResolver = desktopMediaFileResolver(),
) : MediaSourceOpener {
    override suspend fun open(reference: String): Source? =
        withContext(Dispatchers.IO) {
            val resolved = mediaFiles.filePath(reference)?.let(::File)
            (listOfNotNull(resolved) + localFileCandidates(reference)).firstOrNull { it.isFile }?.source()
        }
}

private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

/**
 * The files a stored [reference] can mean, most likely first.
 *
 * The app writes `file://` followed by the plain path, without escaping spaces or backslashes, so a
 * URL is read both as a proper URL and as that plain path.
 */
internal fun localFileCandidates(reference: String): List<File> =
    when {
        reference.startsWith("file:") -> {
            listOfNotNull(fileFromUrl(reference), File(reference.removePrefix("file://").removePrefix("file:")))
        }

        reference.startsWith("/") || reference.startsWith("\\\\") || WINDOWS_DRIVE_PATH.matches(reference) -> {
            listOf(File(reference))
        }

        else -> {
            emptyList()
        }
    }

private fun fileFromUrl(reference: String): File? =
    try {
        File(URI(reference))
    } catch (malformed: URISyntaxException) {
        null
    } catch (unsuitable: IllegalArgumentException) {
        null
    }
