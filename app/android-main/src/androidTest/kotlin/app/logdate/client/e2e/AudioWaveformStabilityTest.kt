package app.logdate.client.e2e

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.ui.audio.AndroidWaveformStorage
import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.ui.audio.LocalAudioContextProcessor
import app.logdate.ui.audio.MomentAudioCard
import app.logdate.ui.audio.WaveformStorage
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import app.logdate.ui.audio.extraction.AndroidAmplitudeExtractor
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.timeline.MomentAudioUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIXTURE_DURATION_SECONDS = 120
private const val FIXTURE_DURATION_MS = FIXTURE_DURATION_SECONDS * 1000L

/** Real PCM decoding and native frames; larger windows are resized API34, not physical hinges. */
class AudioWaveformStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<WaveformAcceptanceActivity>()

    @Test(timeout = 180_000)
    fun decodedWaveformSurvivesUpdatesAndReopeningAcrossThreeWindowSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName.endsWith(".debug"))
        val device = UiDevice.getInstance(instrumentation)
        val output =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "audio-waveform-stability",
            )
        check(output.mkdirs() || output.isDirectory)
        val audio = File(context.cacheDir, "waveform-stability-${SystemClock.elapsedRealtime()}.wav")
        writeWave(audio)
        val uri = audio.toURI().toString()
        val snapshots = CopyOnWriteArrayList<List<Float>>()
        val actualExtractor = AndroidAmplitudeExtractor(context)
        val extractor =
            object : AmplitudeExtractor by actualExtractor {
                override fun extractAmplitudesProgressively(
                    uri: String,
                    targetSampleCount: Int,
                ) = actualExtractor.extractAmplitudesProgressively(uri, targetSampleCount).onEach { snapshots += it }
            }
        val disk = AndroidWaveformStorage(context)
        val slowDisk =
            object : WaveformStorage by disk {
                override suspend fun load(audioUri: String): List<Float>? {
                    delay(300) // A returning view must retain samples even while cache IO is slow.
                    return disk.load(audioUri)
                }
            }
        val processor = AudioContextProcessor(extractor, slowDisk)
        val heartbeat = mutableStateOf(0)
        val visible = mutableStateOf(true)
        compose.setContent {
            LogDateTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAudioContextProcessor provides processor) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Update ${heartbeat.value}")
                        if (visible.value) MomentAudioCard(MomentAudioUiState(uri, FIXTURE_DURATION_MS), null)
                    }
                }
            }
        }
        compose.waitUntil(30_000) { processor.cachedAmplitudes(uri, FIXTURE_DURATION_MS)?.isNotEmpty() == true }
        val oldSize = overrideValue(device.executeShellCommand("wm size"))
        val oldDensity = overrideValue(device.executeShellCommand("wm density"))
        try {
            for ((label, width) in listOf("phone" to 0, "foldable-window" to 1000, "tablet-window" to 1280)) {
                if (width != 0) {
                    device.executeShellCommand("wm density 160")
                    device.executeShellCommand("wm size ${width}x800")
                }
                compose.waitForIdle()
                SystemClock.sleep(500)
                if (width != 0) {
                    assertEquals(width, device.displayWidth, "Requested width must be the effective managed window")
                    assertEquals(800, device.displayHeight)
                }
                val before = compose.onNodeWithTag("moment-audio-card").captureToImage().asAndroidBitmap()
                ManagedEditorRecording(device, File(output, label).apply { mkdirs() }).use { recording ->
                    repeat(4) {
                        compose.runOnIdle { heartbeat.value++ }
                        compose.waitForIdle()
                        assertPixels(before, compose.onNodeWithTag("moment-audio-card").captureToImage().asAndroidBitmap())
                        compose.runOnIdle { visible.value = false }
                        compose.waitForIdle()
                        compose.runOnIdle { visible.value = true }
                        SystemClock.sleep(80)
                        assertPixels(before, compose.onNodeWithTag("moment-audio-card").captureToImage().asAndroidBitmap())
                        SystemClock.sleep(400)
                    }
                    device.takeScreenshot(File(output, "$label.png"))
                    recording.assertStillRecording()
                }
            }
            val audibleSnapshots = snapshots.filter { levels -> levels.any { it > 0f } }
            val publishedBuckets = audibleSnapshots.map { levels -> levels.count { it > 0f } }
            File(output, "samples.txt").writeText(
                "Emissions: ${snapshots.size}\nPublished buckets: $publishedBuckets\n" +
                    audibleSnapshots.joinToString("\n") { it.joinToString(",") },
            )
            assertTrue(audibleSnapshots.size >= 2, "Expected progressive PCM decoding; published buckets: $publishedBuckets")
            assertTrue(publishedBuckets.first() < publishedBuckets.last(), "The waveform never gained buckets")
            audibleSnapshots.zipWithNext().forEach { (earlier, later) ->
                earlier.forEachIndexed { index, level ->
                    if (level > 0f) assertEquals(level, later[index], "Published bucket $index changed")
                }
            }
        } finally {
            device.executeShellCommand("wm size ${oldSize ?: "reset"}")
            device.executeShellCommand("wm density ${oldDensity ?: "reset"}")
        }
    }

    private fun assertPixels(
        expected: Bitmap,
        actual: Bitmap,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val left = IntArray(expected.width * expected.height)
        val right = IntArray(left.size)
        expected.getPixels(left, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(right, 0, actual.width, 0, 0, actual.width, actual.height)
        assertTrue(left.contentEquals(right), "Waveform/card flashed or changed without a media or playback change")
    }

    private fun overrideValue(text: String): String? =
        text
            .lineSequence()
            .firstOrNull { it.startsWith("Override") }
            ?.substringAfter(":")
            ?.trim()

    private fun writeWave(file: File) {
        val rate = 16_000
        val count = rate * FIXTURE_DURATION_SECONDS
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
        buffer
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(rate)
            .putInt(rate * 2)
            .putShort(2)
            .putShort(16)
        buffer.put("data".toByteArray()).putInt(count * 2)
        repeat(count) { index ->
            val amplitude = if (index < count / 2) 3000 else 24000
            buffer.putShort((amplitude * sin(2 * PI * 220 * index / rate)).toInt().toShort())
        }
        file.writeBytes(buffer.array())
    }
}
