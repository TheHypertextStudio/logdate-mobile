package app.logdate.client.media.audio.sherpa

import app.logdate.client.media.audio.download.ModelDownloadStatus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Desktop counterpart of `SherpaOnnxModelManager`. Resolves on-disk paths
 * for the on-demand Whisper and CED models, downloads them from the
 * Sherpa-ONNX release page on first use, and unpacks them under the user's
 * application data directory.
 *
 * Mirrors the Android model manager's contract but skips anything that
 * touches `Context` / `AssetManager` — desktop ships none of the bundled
 * assets the Android dynamic feature module does (streaming Zipformer,
 * VAD, punctuation), so for now everything desktop transcription needs is
 * downloaded on demand.
 */
internal class DesktopSherpaModelManager(
    rootDirectory: File = defaultRootDirectory(),
) {
    private val whisperModelDir = File(rootDirectory, WHISPER_MODEL_DIR_NAME)
    private val taggingModelDir = File(rootDirectory, TAGGING_MODEL_DIR_NAME)

    init {
        rootDirectory.mkdirs()
    }

    fun getWhisperModelPath(): String? = if (isWhisperModelReady()) whisperModelDir.absolutePath else null

    fun isWhisperModelReady(): Boolean =
        whisperModelDir.exists() &&
            whisperModelDir.resolve(WHISPER_TOKENS_NAME).exists() &&
            whisperModelDir.resolve(WHISPER_ENCODER_NAME).exists() &&
            whisperModelDir.resolve(WHISPER_DECODER_NAME).exists()

    fun getAudioTaggingModelPath(): String? = if (isAudioTaggingModelReady()) taggingModelDir.absolutePath else null

    fun isAudioTaggingModelReady(): Boolean =
        taggingModelDir.exists() &&
            taggingModelDir.resolve(TAGGING_MODEL_FILE_NAME).exists() &&
            taggingModelDir.resolve(TAGGING_LABELS_FILE_NAME).exists()

    fun downloadWhisperModel(): Flow<ModelDownloadStatus> =
        downloadAndExtractTarBz2(
            url = WHISPER_DOWNLOAD_URL,
            targetDir = whisperModelDir,
            isReady = ::isWhisperModelReady,
        )

    fun downloadAudioTaggingModel(): Flow<ModelDownloadStatus> =
        downloadAndExtractTarBz2(
            url = TAGGING_DOWNLOAD_URL,
            targetDir = taggingModelDir,
            isReady = ::isAudioTaggingModelReady,
        )

    /**
     * Streams a tar.bz2 archive from [url] into [targetDir], emitting
     * progress as bytes accumulate. Mirrors the Android model manager's
     * download path so the on-demand UX behaves identically across
     * platforms.
     */
    private fun downloadAndExtractTarBz2(
        url: String,
        targetDir: File,
        isReady: () -> Boolean,
    ): Flow<ModelDownloadStatus> =
        flow {
            if (isReady()) {
                emit(ModelDownloadStatus.Completed)
                return@flow
            }

            var connection: HttpURLConnection? = null
            try {
                connection =
                    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                        readTimeout = HTTP_READ_TIMEOUT_MS
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                    }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Napier.w("Desktop model download HTTP $responseCode for $url")
                    emit(ModelDownloadStatus.ServerUnavailable)
                    return@flow
                }

                val totalBytes =
                    connection.contentLengthLong.takeIf { it > 0 }
                        ?: connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }

                emit(ModelDownloadStatus.Downloading(bytesDownloaded = 0L, totalBytes = totalBytes))

                val tempArchive = File(targetDir.parentFile, "${targetDir.name}.tar.bz2.tmp")
                tempArchive.parentFile?.mkdirs()
                tempArchive.delete()

                connection.inputStream.use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var totalRead = 0L
                        var bytesSinceLastEmit = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            totalRead += read
                            bytesSinceLastEmit += read
                            if (bytesSinceLastEmit >= PROGRESS_EMIT_INTERVAL_BYTES) {
                                emit(
                                    ModelDownloadStatus.Downloading(
                                        bytesDownloaded = totalRead,
                                        totalBytes = totalBytes,
                                    ),
                                )
                                bytesSinceLastEmit = 0L
                            }
                        }
                        emit(
                            ModelDownloadStatus.Downloading(
                                bytesDownloaded = totalRead,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }

                emit(ModelDownloadStatus.Extracting)
                try {
                    extractTarBz2IntoDir(tempArchive, targetDir)
                } finally {
                    tempArchive.delete()
                }

                if (!isReady()) {
                    Napier.w("Extracted desktop archive at $url is missing expected files")
                    targetDir.deleteRecursively()
                    emit(ModelDownloadStatus.ArchiveCorrupt)
                    return@flow
                }

                emit(ModelDownloadStatus.Completed)
            } catch (e: java.net.UnknownHostException) {
                Napier.e("Desktop model download failed: no network for $url", e)
                targetDir.deleteRecursively()
                emit(ModelDownloadStatus.NoNetwork)
            } catch (e: java.net.SocketTimeoutException) {
                Napier.e("Desktop model download timed out for $url", e)
                targetDir.deleteRecursively()
                emit(ModelDownloadStatus.NoNetwork)
            } catch (e: java.io.IOException) {
                val outOfSpace = e.message?.contains("space", ignoreCase = true) == true
                Napier.e("Desktop model download IO error for $url (outOfSpace=$outOfSpace)", e)
                targetDir.deleteRecursively()
                emit(if (outOfSpace) ModelDownloadStatus.OutOfStorage else ModelDownloadStatus.UnknownError)
            } catch (e: Exception) {
                Napier.e("Desktop model download failed for $url", e)
                targetDir.deleteRecursively()
                emit(ModelDownloadStatus.UnknownError)
            } finally {
                connection?.disconnect()
            }
        }.flowOn(Dispatchers.IO)

    private fun extractTarBz2IntoDir(
        archive: File,
        targetDir: File,
    ) {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        var topLevelPrefix: String? = null
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tarIn ->
            var entry = tarIn.nextEntry
            while (entry != null) {
                val entryName = entry.name

                if (topLevelPrefix == null) {
                    val slashIndex = entryName.indexOf('/')
                    if (slashIndex > 0) {
                        topLevelPrefix = entryName.substring(0, slashIndex + 1)
                    }
                }

                val relativePath =
                    topLevelPrefix?.let {
                        if (entryName.startsWith(it)) entryName.removePrefix(it) else entryName
                    } ?: entryName

                if (relativePath.isEmpty()) {
                    entry = tarIn.nextEntry
                    continue
                }

                val outFile = File(targetDir, relativePath)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Tar entry escaped target dir: $entryName")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { tarIn.copyTo(it, BUFFER_SIZE) }
                }
                entry = tarIn.nextEntry
            }
        }
    }

    companion object {
        const val WHISPER_MODEL_DIR_NAME = "sherpa-onnx-whisper-small-en"
        const val WHISPER_ENCODER_NAME = "small.en-encoder.int8.onnx"
        const val WHISPER_DECODER_NAME = "small.en-decoder.int8.onnx"
        const val WHISPER_TOKENS_NAME = "small.en-tokens.txt"

        const val TAGGING_MODEL_DIR_NAME = "sherpa-onnx-ced-small"
        const val TAGGING_MODEL_FILE_NAME = "model.onnx"
        const val TAGGING_LABELS_FILE_NAME = "class_labels_indices.csv"

        private const val WHISPER_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2"
        private const val TAGGING_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/" +
                "sherpa-onnx-ced-small-audio-tagging-2024-04-19.tar.bz2"

        private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
        private const val HTTP_READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EMIT_INTERVAL_BYTES = 512L * 1024
        private const val BUFFER_SIZE = 8192

        /**
         * The default root directory used when no override is supplied. Picks
         * the platform-conventional app data location so models persist
         * across runs without leaking into the project's working directory.
         */
        private fun defaultRootDirectory(): File {
            val home = System.getProperty("user.home") ?: "."
            val osName = System.getProperty("os.name")?.lowercase().orEmpty()
            val base =
                when {
                    "mac" in osName || "darwin" in osName -> {
                        File(home, "Library/Application Support/Logdate")
                    }

                    "win" in osName -> {
                        val appData = System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
                        File(appData, "Logdate")
                    }

                    else -> {
                        // Linux + everything else: XDG data home, falling back to ~/.local/share.
                        val xdg = System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
                        File(xdg, "logdate")
                    }
                }
            return File(base, "sherpa-onnx")
        }
    }
}
