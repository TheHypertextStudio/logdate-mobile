package app.logdate

import com.android.build.api.variant.DynamicFeatureAndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * A speech model the app bundles: the archive at [url], whose SHA-256 must be [sha256], repackaged as
 * the asset [assetName] without the files that end in any of [excludePatterns].
 */
data class SpeechModel(
    val url: String,
    val assetName: String,
    val sha256: String,
    val excludePatterns: List<String> = emptyList(),
) : java.io.Serializable

interface SpeechModelExtension {
    val models: ListProperty<SpeechModel>
}

/**
 * Downloads the speech models and writes the assets the speech recognition feature bundles.
 *
 * The output depends only on the model list, and every archive is checked against its SHA-256, so
 * the result is cached: another checkout or a clean build restores the assets from the build cache
 * instead of downloading several hundred megabytes again.
 */
@CacheableTask
abstract class DownloadSpeechModelTask : DefaultTask() {
    @get:Input
    abstract val models: ListProperty<SpeechModel>

    @get:Internal
    abstract val connectTimeoutMillis: Property<Int>

    @get:Internal
    abstract val readTimeoutMillis: Property<Int>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        connectTimeoutMillis.convention(30_000)
        readTimeoutMillis.convention(120_000)
    }

    @TaskAction
    fun download() {
        val outputDir = outputDirectory.get().asFile
        val assets = models.get().map { it.assetName }.toSet()
        outputDir.listFiles()?.filterNot { it.name in assets }?.forEach { it.deleteRecursively() }
        outputDir.mkdirs()

        val downloader = SpeechModelDownloader(temporaryDir, connectTimeoutMillis.get(), readTimeoutMillis.get())
        for (model in models.get()) {
            logger.lifecycle("Downloading speech model: ${model.assetName}")
            downloader.fetch(model, outputDir.resolve(model.assetName))
        }
    }
}

class SpeechModelPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("speechModels", SpeechModelExtension::class.java)
        extension.models.convention(DEFAULT_MODELS)

        val outputDir = project.layout.buildDirectory.dir("generated/speech-assets")

        val downloadTask = project.tasks.register("downloadSpeechModels", DownloadSpeechModelTask::class.java)
        downloadTask.configure {
            group = "speech"
            description = "Downloads speech recognition models for bundling in APK assets"
            outputDirectory.set(outputDir)
            models.set(extension.models)
        }

        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val androidComponents =
                project.extensions
                    .getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)

            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    downloadTask,
                    DownloadSpeechModelTask::outputDirectory,
                )
            }
        }

        project.pluginManager.withPlugin("com.android.dynamic-feature") {
            val androidComponents =
                project.extensions
                    .getByType(DynamicFeatureAndroidComponentsExtension::class.java)

            androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    downloadTask,
                    DownloadSpeechModelTask::outputDirectory,
                )
            }
        }
    }

    companion object {
        private const val SHERPA_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

        private val DEFAULT_MODELS =
            listOf(
                SpeechModel(
                    url = "$SHERPA_RELEASES/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
                    assetName = "sherpa-onnx-stt-en.zip",
                    sha256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
                    excludePatterns =
                        listOf(
                            // Keep only int8 quantized models; skip fp32 variants to save ~250MB
                            // Note: decoder has no int8 variant, so keep the fp32 decoder (2MB)
                            "encoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                            "joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
                            ".wav",
                            ".sh",
                            "README.md",
                        ),
                ),
                SpeechModel(
                    url = "$SHERPA_RELEASES/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2",
                    assetName = "sherpa-onnx-punct-en.zip",
                    sha256 = "9f5e5a72c7d2829635bd074fce92b6bbd5b78da8a52e7ad8ed1be933f366b99d",
                    excludePatterns =
                        listOf(
                            "model.int8.onnx",
                            "README.md",
                        ),
                ),
                SpeechModel(
                    url = "$SHERPA_RELEASES/asr-models/silero_vad.onnx",
                    assetName = "silero-vad.zip",
                    sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                ),
            )
    }
}
