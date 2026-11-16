package app.logdate.client.media.audio.download

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

class FileModelDownloadRequest(
    path: String,
) : ModelDownloadRequest {
    private val marker = Paths.get(path).toAbsolutePath()

    override fun isRequested(): Boolean =
        try {
            Files.readAttributes(marker, BasicFileAttributes::class.java)
            true
        } catch (_: NoSuchFileException) {
            false
        }

    override fun setRequested(requested: Boolean) {
        if (requested) {
            Files.createDirectories(marker.parent)
            try {
                Files.createFile(marker)
            } catch (_: FileAlreadyExistsException) {
                // An existing marker already records the request.
            }
        } else {
            Files.deleteIfExists(marker)
        }
    }
}
