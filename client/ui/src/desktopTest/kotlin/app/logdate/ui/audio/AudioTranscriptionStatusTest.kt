package app.logdate.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.timeline.MomentAudioUiState
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AudioTranscriptionStatusTest {
    @Test
    fun `recordings show queued active failure and complete states`() {
        for ((name, width, height) in listOf(Triple("phone", 400, 820), Triple("foldable-sized", 1000, 800), Triple("tablet", 1280, 800))) {
            verifyStates(name, width, height)
        }
    }

    private fun verifyStates(
        name: String,
        width: Int,
        height: Int,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val id = Uuid.random()
        val status = mutableStateOf(TranscriptionStatus.PENDING)
        var retries = 0
        val uri = "file:///status.wav"
        val recordedAt = Instant.parse("2026-10-30T12:00:00Z")
        val samples = List(300) { index -> .1f + (index % 23) / 30f }
        val processor =
            AudioContextProcessor(
                object : AmplitudeExtractor {
                    override suspend fun extractAmplitudes(
                        uri: String,
                        targetSampleCount: Int,
                    ) = samples
                },
                object : WaveformStorage {
                    override suspend fun load(audioUri: String): List<Float>? = samples

                    override suspend fun save(
                        audioUri: String,
                        amplitudes: List<Float>,
                    ) = Unit

                    override suspend fun delete(audioUri: String) = Unit

                    override suspend fun exists(audioUri: String) = true
                },
            )
        runBlocking { processor.process(uri, 5000, recordedAt, null, null) }

        fun capture(state: String) {
            System.getenv("LOGDATE_AUDIO_SCREENSHOT_DIR")?.let { directory ->
                val image = onRoot().captureToImage()
                val pixels = image.toPixelMap()
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
                val file = File(directory, "$name-$state.png")
                file.parentFile.mkdirs()
                ImageIO.write(output, "png", file)
            }
        }
        setContent {
            val current = status.value
            LogDateTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAudioContextProcessor provides processor) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Audio status · offscreen desktop fixture")
                            Text("$name · $width×$height · synthetic samples")
                            TranscriptionProvider(
                                TranscriptionState(
                                    requestTranscription = { retries++ },
                                    isTranscriptionInProgress = {
                                        current == TranscriptionStatus.PENDING ||
                                            current == TranscriptionStatus.IN_PROGRESS
                                    },
                                    getTranscriptionStatus = { current },
                                ),
                            ) {
                                MomentAudioCard(MomentAudioUiState(uri, 5000, noteId = id, recordedAt = recordedAt), null)
                            }
                        }
                    }
                }
            }
        }
        onNodeWithText("Transcription queued").assertExists()
        onNodeWithText("Transcribing…").assertDoesNotExist()
        capture("01-queued")
        runOnIdle { status.value = TranscriptionStatus.IN_PROGRESS }
        onNodeWithText("Transcribing…").assertExists()
        capture("02-active")
        runOnIdle { status.value = TranscriptionStatus.FAILED }
        onNodeWithText("Transcription failed. Audio kept.").assertExists()
        capture("03-failed")
        onNodeWithText("Retry transcription").performClick()
        runOnIdle { assertEquals(2, retries) }
        runOnIdle { status.value = TranscriptionStatus.COMPLETED }
        onNodeWithText("No speech detected").assertExists()
        capture("04-complete-no-speech")
        onNodeWithText("Transcribing…").assertDoesNotExist()
    }
}
