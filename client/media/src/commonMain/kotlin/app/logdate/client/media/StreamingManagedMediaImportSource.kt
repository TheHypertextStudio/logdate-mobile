package app.logdate.client.media

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/**
 * Stages media on platforms without Android's content-URI import source by streaming
 * [MediaManager.openMedia] into a private temporary file, so a large video never sits in memory.
 */
class StreamingManagedMediaImportSource(
    private val media: MediaManager,
    private val temporaryDirectory: String,
) : ManagedMediaImportSource {
    override suspend fun stage(sourceUri: String): StagedMediaFile {
        val file = media.openMedia(sourceUri)
        val path = Path(temporaryDirectory, "logdate-mark-import-${Uuid.random()}")
        try {
            SystemFileSystem.sink(path).buffered().use { sink ->
                file.open().use { source -> sink.transferFrom(source) }
            }
        } catch (failure: Throwable) {
            SystemFileSystem.delete(path, mustExist = false)
            throw failure
        }
        return StagedMediaFile(path.toString(), file.fileName, file.mimeType)
    }

    override suspend fun discard(stagedMedia: StagedMediaFile) {
        SystemFileSystem.delete(Path(stagedMedia.sourceFilePath), mustExist = false)
    }
}
