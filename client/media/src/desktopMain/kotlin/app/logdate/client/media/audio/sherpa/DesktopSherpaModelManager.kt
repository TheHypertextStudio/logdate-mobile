package app.logdate.client.media.audio.sherpa

import app.logdate.client.media.audio.download.FileModelDownloadRequest
import app.logdate.client.media.audio.download.ModelDownloadRequest
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.download.ModelPackageInstallation
import app.logdate.client.media.audio.download.checkedModelFilesReady
import app.logdate.client.media.audio.download.checkedModelPackageReady
import app.logdate.client.media.audio.download.deleteModelDirectory
import app.logdate.client.media.audio.download.downloadModelPackage
import app.logdate.client.media.audio.download.reconcileCompletedModelPackage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

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
    private val rootDirectory: File = defaultRootDirectory(),
    private val whisperDownloadUrl: String = WHISPER_DOWNLOAD_URL,
    private val vadDownloadUrl: String = VAD_DOWNLOAD_URL,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    private val whisperModelDir = File(rootDirectory, WHISPER_MODEL_DIR_NAME)
    private val taggingModelDir = File(rootDirectory, TAGGING_MODEL_DIR_NAME)
    private val vadModelDir = File(rootDirectory, "silero-vad")

    fun whisperDownloadRequest(): ModelDownloadRequest = FileModelDownloadRequest(File(rootDirectory, ".whisper-download-requested").path)

    fun taggingDownloadRequest(): ModelDownloadRequest = FileModelDownloadRequest(File(rootDirectory, ".tagging-download-requested").path)

    fun getWhisperModelPath(): String? = if (isWhisperModelReady()) whisperModelDir.absolutePath else null

    fun checkWhisperModelReady(): Boolean = checkWhisperFilesReady() && getVadModelPath() != null

    fun isWhisperModelReady(): Boolean = modelReady { checkWhisperModelReady() }

    fun getAudioTaggingModelPath(): String? = if (isAudioTaggingModelReady()) taggingModelDir.absolutePath else null

    fun checkAudioTaggingModelReady(): Boolean =
        checkedModelPackageReady(taggingModelDir.path, listOf(TAGGING_MODEL_FILE_NAME, TAGGING_LABELS_FILE_NAME))

    fun isAudioTaggingModelReady(): Boolean = modelReady { checkAudioTaggingModelReady() }

    private fun checkWhisperFilesReady(): Boolean =
        checkedModelPackageReady(
            whisperModelDir.path,
            listOf(WHISPER_ENCODER_NAME, WHISPER_DECODER_NAME, WHISPER_TOKENS_NAME),
        )

    fun getVadModelPath(): String? =
        when {
            checkedModelFilesReady(rootDirectory.path, listOf(VAD_MODEL_FILE_NAME)) -> rootDirectory.resolve(VAD_MODEL_FILE_NAME).path
            checkedModelFilesReady(vadModelDir.path, listOf(VAD_MODEL_FILE_NAME)) -> vadModelDir.resolve(VAD_MODEL_FILE_NAME).path
            else -> null
        }.also { if (it != null) reconcileCompletedModelPackage(vadModelDir) }

    fun downloadWhisperModel(): Flow<ModelDownloadStatus> =
        flow {
            val needsVad = getVadModelPath() == null
            var completedBytes = 0L
            if (!checkWhisperFilesReady()) {
                var installed = false
                downloadAndExtractTarBz2(
                    whisperDownloadUrl,
                    whisperModelDir,
                    ::checkWhisperFilesReady,
                    listOf(WHISPER_ENCODER_NAME, WHISPER_DECODER_NAME, WHISPER_TOKENS_NAME),
                ).collect { status ->
                    when (status) {
                        is ModelDownloadStatus.Downloading -> {
                            completedBytes = status.bytesDownloaded
                            emit(if (needsVad) status.copy(totalBytes = null) else status)
                        }
                        ModelDownloadStatus.Completed -> installed = true
                        else -> emit(status)
                    }
                }
                if (!installed) return@flow
            }
            if (needsVad) {
                downloadAndExtractTarBz2(
                    vadDownloadUrl,
                    vadModelDir,
                    { getVadModelPath() != null },
                    listOf(VAD_MODEL_FILE_NAME),
                    VAD_MODEL_FILE_NAME,
                ).collect { status ->
                    emit(
                        if (status is ModelDownloadStatus.Downloading) {
                            status.copy(
                                bytesDownloaded = completedBytes + status.bytesDownloaded,
                                totalBytes = status.totalBytes?.plus(completedBytes),
                            )
                        } else {
                            status
                        },
                    )
                }
            } else {
                emit(ModelDownloadStatus.Completed)
            }
        }.flowOn(Dispatchers.IO)

    fun downloadAudioTaggingModel(): Flow<ModelDownloadStatus> =
        downloadAndExtractTarBz2(
            url = TAGGING_DOWNLOAD_URL,
            targetDir = taggingModelDir,
            isReady = ::checkAudioTaggingModelReady,
            requiredNames = listOf(TAGGING_MODEL_FILE_NAME, TAGGING_LABELS_FILE_NAME),
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
        requiredNames: List<String>,
        rawFileName: String? = null,
    ): Flow<ModelDownloadStatus> =
        downloadModelPackage(
            url,
            ModelPackageInstallation(
                targetDir,
                requiredNames,
                isReady,
                unpackedBytes = { if (rawFileName == null) modelArchiveUnpackedBytes(it) else it.length() },
                extract = { archive, staging ->
                    if (rawFileName == null) {
                        extractModelArchive(archive, staging)
                    } else {
                        deleteModelDirectory(staging.path)
                        staging.mkdirs()
                        archive.copyTo(staging.resolve(rawFileName))
                    }
                },
            ),
            availableBytes,
        )

    private fun modelReady(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (e: Exception) {
            Napier.e("Could not inspect installed model files", e)
            false
        }

    companion object {
        const val VAD_MODEL_FILE_NAME = "silero_vad.onnx"
        private const val VAD_DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

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
                    "mac" in osName || "darwin" in osName ->
                        File(home, "Library/Application Support/Logdate")
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
