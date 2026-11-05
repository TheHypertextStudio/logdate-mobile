package app.logdate.feature.speech.recognition

import android.content.Context
import app.logdate.client.media.audio.download.checkedModelPackageReady
import app.logdate.client.media.audio.download.ModelDownloadRequest
import app.logdate.client.media.audio.download.FileModelDownloadRequest
import app.logdate.client.media.audio.download.ModelPackageInstallation
import app.logdate.client.media.audio.download.downloadModelPackage
import app.logdate.client.media.audio.download.ModelDownloadStatus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Manages the Sherpa-ONNX speech recognition and punctuation models on disk.
 *
 * On first use, extracts models from the app's assets into internal storage.
 * Subsequent calls return the cached model paths.
 *
 * The models are bundled as zip files in assets (downloaded and repackaged at
 * build time by the `app.logdate.speech-model` Gradle plugin). Each zip contains
 * model files with the top-level directory stripped during extraction.
 */
class SherpaOnnxModelManager(
    private val context: Context,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    private val sttModelDir = File(context.filesDir, STT_MODEL_DIR_NAME)
    private val punctModelDir = File(context.filesDir, PUNCT_MODEL_DIR_NAME)
    private val vadModelDir = File(context.filesDir, VAD_MODEL_DIR_NAME)
    private val whisperModelDir = File(context.filesDir, WHISPER_MODEL_DIR_NAME)
    private val taggingModelDir = File(context.filesDir, TAGGING_MODEL_DIR_NAME)

    fun whisperDownloadRequest(): ModelDownloadRequest = FileModelDownloadRequest(File(context.filesDir, ".whisper-download-requested").path)
    fun taggingDownloadRequest(): ModelDownloadRequest = FileModelDownloadRequest(File(context.filesDir, ".tagging-download-requested").path)

    suspend fun getModelPath(): String? {
        if (isSttModelReady()) {
            return sttModelDir.absolutePath
        }
        return try {
            extractModelFromAssets(STT_ASSET_NAME, sttModelDir)
            if (isSttModelReady()) sttModelDir.absolutePath else null
        } catch (e: Exception) {
            Napier.e("Failed to extract Sherpa-ONNX STT model from assets", e)
            null
        }
    }

    suspend fun getPunctuationModelPath(): String? {
        if (isPunctModelReady()) {
            return punctModelDir.absolutePath
        }
        return try {
            extractModelFromAssets(PUNCT_ASSET_NAME, punctModelDir)
            if (isPunctModelReady()) punctModelDir.absolutePath else null
        } catch (e: Exception) {
            Napier.e("Failed to extract Sherpa-ONNX punctuation model from assets", e)
            null
        }
    }

    /**
     * Returns the absolute path to the Silero VAD model file, extracting it from
     * assets on first use. Returns null if extraction fails.
     */
    suspend fun getVadModelPath(): String? {
        val modelFile = vadModelDir.resolve(VAD_MODEL_FILE_NAME)
        if (modelFile.exists()) {
            return modelFile.absolutePath
        }
        return try {
            extractModelFromAssets(VAD_ASSET_NAME, vadModelDir)
            if (modelFile.exists()) modelFile.absolutePath else null
        } catch (e: Exception) {
            Napier.e("Failed to extract Silero VAD model from assets", e)
            null
        }
    }

    /**
     * Returns the absolute path to the Whisper small.en model directory if a
     * complete model is present in internal storage, or null otherwise.
     *
     * The model is not bundled with the app — it is downloaded on demand and
     * placed at this path by [downloadWhisperModel]. Until that happens,
     * callers should treat refinement as unavailable and fall back to
     * streaming-only transcription.
     */
    fun getWhisperModelPath(): String? = if (isWhisperModelReady()) whisperModelDir.absolutePath else null

    fun checkWhisperModelReady(): Boolean = checkedModelPackageReady(whisperModelDir.path, listOf(WHISPER_ENCODER_NAME, WHISPER_DECODER_NAME, WHISPER_TOKENS_NAME))

    fun isWhisperModelReady(): Boolean = modelReady { checkWhisperModelReady() }

    /**
     * Returns the absolute path to the CED-small audio tagging model
     * directory if a complete model is present in internal storage, or null
     * otherwise.
     *
     * The model is not bundled with the app — it is downloaded on demand and
     * placed at this path. Until that happens, callers should treat ambient
     * sound detection as unavailable.
     */
    fun getAudioTaggingModelPath(): String? = if (isAudioTaggingModelReady()) taggingModelDir.absolutePath else null

    fun checkAudioTaggingModelReady(): Boolean = checkedModelPackageReady(taggingModelDir.path, listOf(TAGGING_MODEL_FILE_NAME, TAGGING_LABELS_FILE_NAME))

    fun isAudioTaggingModelReady(): Boolean = modelReady { checkAudioTaggingModelReady() }

    /**
     * Streams the Whisper small.en archive from the Sherpa-ONNX release page,
     * unpacks it into [whisperModelDir], and emits [ModelDownloadStatus]
     * updates as the bytes flow. Cancellable mid-stream.
     */
    fun downloadWhisperModel(): Flow<ModelDownloadStatus> =
        downloadAndExtractTarBz2(
            url = WHISPER_DOWNLOAD_URL,
            targetDir = whisperModelDir,
            isReady = ::checkWhisperModelReady,
            requiredNames = listOf(WHISPER_ENCODER_NAME, WHISPER_DECODER_NAME, WHISPER_TOKENS_NAME),
        )

    /**
     * Streams the CED-small audio tagging archive from the Sherpa-ONNX
     * release page, unpacks it into [taggingModelDir], and emits
     * [ModelDownloadStatus] updates as the bytes flow. Cancellable mid-stream.
     */
    fun downloadAudioTaggingModel(): Flow<ModelDownloadStatus> =
        downloadAndExtractTarBz2(
            url = TAGGING_DOWNLOAD_URL,
            targetDir = taggingModelDir,
            isReady = ::checkAudioTaggingModelReady,
            requiredNames = listOf(TAGGING_MODEL_FILE_NAME, TAGGING_LABELS_FILE_NAME),
        )

    /**
     * Downloads a tar.bz2 archive at [url], streams it through bzip2 + tar
     * decoders, and writes the (top-level-stripped) entries into [targetDir].
     * Emits a [ModelDownloadStatus.Downloading] update on a small interval as
     * bytes accumulate, then a single [ModelDownloadStatus.Extracting] before
     * the disk write phase, then [ModelDownloadStatus.Completed].
     *
     * On any failure the partial [targetDir] is wiped so a retry starts clean.
     * The triggering exception is logged via Napier and never reaches the
     * caller as a string — the caller only sees a [ModelDownloadStatus.Reason].
     */
    private fun downloadAndExtractTarBz2(
        url: String,
        targetDir: File,
        isReady: () -> Boolean,
        requiredNames: List<String>,
    ): Flow<ModelDownloadStatus> = downloadModelPackage(
        url,
        ModelPackageInstallation(targetDir, requiredNames, isReady, ::modelArchiveUnpackedBytes, ::extractModelArchive),
        availableBytes,
    )

    private fun isSttModelReady(): Boolean =
        sttModelDir.exists() &&
            sttModelDir.resolve("tokens.txt").exists() &&
            sttModelDir.listFiles()?.any { it.extension == "onnx" } == true

    private fun isPunctModelReady(): Boolean =
        punctModelDir.exists() &&
            punctModelDir.listFiles()?.any { it.extension == "onnx" } == true

    private fun extractModelFromAssets(
        assetName: String,
        targetDir: File,
    ) {
        Napier.d("Extracting model from assets/$assetName")

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        context.assets.open(assetName).use { inputStream ->
            extractZip(inputStream, targetDir)
        }

        Napier.d("Model extracted to ${targetDir.absolutePath}")
    }

    private fun extractZip(
        inputStream: InputStream,
        targetDir: File,
    ) {
        ZipInputStream(inputStream).use { zip ->
            var topLevelPrefix: String? = null

            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name

                if (topLevelPrefix == null) {
                    val slashIndex = entryName.indexOf('/')
                    if (slashIndex > 0) {
                        topLevelPrefix = entryName.substring(0, slashIndex + 1)
                    }
                }

                val relativePath =
                    if (topLevelPrefix != null && entryName.startsWith(topLevelPrefix)) {
                        entryName.removePrefix(topLevelPrefix)
                    } else {
                        entryName
                    }

                if (relativePath.isEmpty()) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                val file = File(targetDir, relativePath)

                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: $entryName")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zip.copyTo(output, BUFFER_SIZE)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun modelReady(check: () -> Boolean): Boolean = try {
        check()
    } catch (e: Exception) {
        Napier.e("Could not inspect installed model files", e)
        false
    }

    companion object {
        private const val STT_MODEL_DIR_NAME = "sherpa-onnx-stt"
        private const val STT_ASSET_NAME = "sherpa-onnx-stt-en.zip"
        private const val PUNCT_MODEL_DIR_NAME = "sherpa-onnx-punct"
        private const val PUNCT_ASSET_NAME = "sherpa-onnx-punct-en.zip"
        private const val VAD_MODEL_DIR_NAME = "sherpa-onnx-vad"
        private const val VAD_ASSET_NAME = "silero-vad.zip"
        private const val VAD_MODEL_FILE_NAME = "silero_vad.onnx"

        const val WHISPER_MODEL_DIR_NAME = "sherpa-onnx-whisper-small-en"
        const val WHISPER_ENCODER_NAME = "small.en-encoder.int8.onnx"
        const val WHISPER_DECODER_NAME = "small.en-decoder.int8.onnx"
        const val WHISPER_TOKENS_NAME = "small.en-tokens.txt"

        const val TAGGING_MODEL_DIR_NAME = "sherpa-onnx-ced-small"
        const val TAGGING_MODEL_FILE_NAME = "model.onnx"
        const val TAGGING_LABELS_FILE_NAME = "class_labels_indices.csv"

        // Sherpa-ONNX release page URLs for the on-demand downloads.
        private const val WHISPER_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2"
        private const val TAGGING_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/" +
                "sherpa-onnx-ced-small-audio-tagging-2024-04-19.tar.bz2"

        private const val BUFFER_SIZE = 8192

    }
}
