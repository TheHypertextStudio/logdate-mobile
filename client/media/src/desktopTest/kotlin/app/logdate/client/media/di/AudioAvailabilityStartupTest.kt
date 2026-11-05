package app.logdate.client.media.di

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.sherpa.DesktopSherpaModelManager
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AudioAvailabilityStartupTest {
    @Test
    fun discoveringMissingDownloadsDoesNotCreateAppStorage() {
        val parent = Files.createTempDirectory("audio-startup-missing").toFile()
        try {
            val root = File(parent, "models")
            val manager = DesktopSherpaModelManager(root)
            assertFalse(root.exists())
            assertFalse(manager.checkWhisperModelReady())
            assertFalse(manager.checkAudioTaggingModelReady())
            assertFalse(root.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun installedDownloadsAreDiscoveredDuringStartupBeforeSettingsIsRequested() =
        runBlocking {
            val root = Files.createTempDirectory("audio-startup").toFile()
            val manager = DesktopSherpaModelManager(root)
            try {
                for (path in listOf(
                    "sherpa-onnx-whisper-small-en/small.en-encoder.int8.onnx",
                    "sherpa-onnx-whisper-small-en/small.en-decoder.int8.onnx",
                    "sherpa-onnx-whisper-small-en/small.en-tokens.txt",
                    "silero-vad/silero_vad.onnx",
                    "sherpa-onnx-ced-small/model.onnx",
                    "sherpa-onnx-ced-small/class_labels_indices.csv",
                )) {
                    File(root, path).apply {
                        parentFile.mkdirs()
                        writeText("fixture")
                    }
                }
                var serviceCreations = 0
                val app =
                    koinApplication {
                        modules(
                            audioModule,
                            module {
                                factory {
                                    serviceCreations++
                                    manager
                                }
                            },
                        )
                    }
                try {
                    assertEquals(2, serviceCreations, "Both services must discover downloads before settings asks for them")
                    val transcription = app.koin.get<TranscriptionService>()
                    val recognition = app.koin.get<AudioTaggingService>()
                    withTimeout(5_000) {
                        assertEquals(
                            ModelDownloadStatus.Completed,
                            transcription.offlineModelDownloadStatus.first {
                                it !=
                                    ModelDownloadStatus.Checking
                            },
                        )
                        assertEquals(
                            ModelDownloadStatus.Completed,
                            recognition.modelDownloadStatus.first { it != ModelDownloadStatus.Checking },
                        )
                    }
                    assertEquals(
                        ModelDownloadStatus.Completed,
                        app.koin
                            .get<TranscriptionService>()
                            .offlineModelDownloadStatus.value,
                    )
                    assertEquals(
                        ModelDownloadStatus.Completed,
                        app.koin
                            .get<AudioTaggingService>()
                            .modelDownloadStatus.value,
                    )
                } finally {
                    app.close()
                }
            } finally {
                root.deleteRecursively()
            }
        }
}
