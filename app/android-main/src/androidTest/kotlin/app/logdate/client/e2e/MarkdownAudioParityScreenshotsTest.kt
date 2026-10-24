package app.logdate.client.e2e

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.media.audio.transcription.TimedTranscript
import app.logdate.client.media.audio.transcription.TimedUtterance
import app.logdate.feature.editor.ui.audio.AudioBlockContent
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.ui.common.MarkdownPreviewText
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Populated production components, captured only on an explicitly selected Android emulator. */
class MarkdownAudioParityScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun requireEmulator() {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"), "These captures require an Android emulator")
    }

    @Test(timeout = 180_000)
    fun markdownSourceReadingAndPreview() {
        val variant = mutableStateOf(variants.first())
        val mode = mutableStateOf("source")
        var scrollToBottom: () -> Unit = {}
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, variant.value.fontScale),
                    LocalLayoutDirection provides variant.value.direction,
                    LocalReduceMotionOverride provides variant.value.reducedMotion,
                ) {
                    LogDateTheme(dynamicColor = variant.value.dynamicColor, darkTheme = variant.value.dark) {
                        key(mode.value, variant.value) {
                            val scroll = rememberScrollState()
                            scrollToBottom = { scroll.dispatchRawDelta(scroll.maxValue.toFloat()) }
                            Surface(Modifier.fillMaxSize()) {
                                Column(
                                    Modifier.safeDrawingPadding().verticalScroll(scroll).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text("Quiet streets · ${mode.value}", style = MaterialTheme.typography.titleMedium)
                                    when (mode.value) {
                                        "source" -> {
                                            TextBlockContent(
                                                block = remember { TextBlockUiState(content = markdown) },
                                                isExpanded = false,
                                                requestEditingFocus = false,
                                                onTextChanged = {},
                                                onFocused = {},
                                            )
                                        }

                                        "reading" -> {
                                            MarkdownText(markdown)
                                        }

                                        else -> {
                                            MarkdownPreviewText(markdown, maxLines = 5)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (currentVariant in variants) {
            for (currentMode in listOf("source", "reading", "preview")) {
                compose.runOnUiThread {
                    variant.value = currentVariant
                    mode.value = currentMode
                }
                compose.waitForIdle()
                if (currentMode == "source") {
                    val source = compose.onNodeWithTag("editor_text_input").assertTextContains(markdown)
                    val layout = source.textLayout()
                    assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
                    assertTrue(layout.getBoundingBox(0).left < layout.getBoundingBox(2).left, "The heading marker precedes English source")
                } else if (currentMode == "reading") {
                    compose.onNodeWithText("  keep indentation", substring = true).assertExists()
                    compose.onNodeWithText("<div>literal HTML</div>").assertExists()
                    assertEquals(ResolvedTextDirection.Ltr, compose.onNodeWithText("Quiet streets").textLayout().getParagraphDirection(0))
                    val body = compose.onNodeWithText("The long way home — rain.\nSoft line\ncontinues.").textLayout()
                    if (currentVariant.fontScale == 1f) {
                        assertEquals(3, body.lineCount, "Inline styles must preserve sentence and soft-break layout")
                    } else {
                        assertTrue(body.lineCount <= 6, "Large text may wrap naturally without creating inline paragraphs")
                    }
                }
                capture("markdown-${currentVariant.name}-$currentMode-top")
                if (currentMode != "preview") {
                    compose.runOnUiThread { scrollToBottom() }
                    compose.waitForIdle()
                    capture("markdown-${currentVariant.name}-$currentMode-bottom")
                }
            }
        }
    }

    @Test(timeout = 180_000)
    fun completedAudioTranscriptPlaybackAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audioFile = File(instrumentation.targetContext.cacheDir, "parity-completed-recording.wav")
        instrumentation.context.assets.open("parity/completed-recording.wav").use { source ->
            audioFile.outputStream().use { source.copyTo(it) }
        }
        val durationMs =
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(audioFile.absolutePath)
                requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            }
        assertTrue(durationMs > 3_000, "The visual fixture must contain playable spoken audio")
        val midpoint = durationMs / 2
        val transcript =
            TimedTranscript(
                listOf(
                    TimedUtterance("The streets were quiet after the rain.", 0, midpoint - 1),
                    TimedUtterance("We took the long way home.", midpoint, durationMs),
                ),
            )
        val waveform = waveform(audioFile)
        assertTrue(waveform.any { it > .1f }, "The fixture must contain a populated waveform")
        val block =
            AudioBlockUiState(
                captureState = AudioCaptureState.Ready(audioFile.toURI().toString(), durationMs),
                transcription = transcript.plainText,
                caption = "**Quiet** streets",
            )
        val variant = mutableStateOf(variants.first())
        val expanded = mutableStateOf(true)
        val playing = mutableStateOf(false)
        val progress = mutableStateOf(.35f)
        val player =
            MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
            }
        val completedSeeks = AtomicInteger()
        player.setOnSeekCompleteListener { completedSeeks.incrementAndGet() }

        fun awaitSeek(
            previous: Int,
            expectedMs: Long,
        ) {
            compose.waitUntil(timeoutMillis = 5_000) { completedSeeks.get() > previous }
            compose.runOnIdle {
                assertTrue(abs(player.currentPosition - expectedMs) <= 100, "Playback must seek to $expectedMs ms")
            }
        }
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, variant.value.fontScale),
                        LocalLayoutDirection provides variant.value.direction,
                        LocalReduceMotionOverride provides variant.value.reducedMotion,
                    ) {
                        LogDateTheme(dynamicColor = variant.value.dynamicColor, darkTheme = variant.value.dark) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(
                                    Modifier.safeDrawingPadding().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text("Quiet streets · recording", style = MaterialTheme.typography.titleMedium)
                                    AudioBlockContent(
                                        block = block,
                                        isExpanded = expanded.value,
                                        isPlaying = playing.value,
                                        timedTranscript = transcript,
                                        waveformAmplitudes = waveform,
                                        playbackProgress = progress.value,
                                        onPlayPauseClicked = {
                                            if (player.isPlaying) player.pause() else player.start()
                                            playing.value = player.isPlaying
                                        },
                                        onDeleteClicked = {},
                                        onSeekPositionChanged = {
                                            progress.value = it
                                            player.seekTo((durationMs * it).toInt())
                                        },
                                        onSeekTimestampClicked = {
                                            progress.value = it.toFloat() / durationMs
                                            player.seekTo(it.toInt())
                                        },
                                        showDeleteAction = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Quiet streets").assertExists()
            compose.onNodeWithContentDescription("Play").performClick()
            compose.runOnIdle { assertTrue(player.isPlaying, "Production play action must start the fixture") }
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.runOnIdle { assertTrue(!player.isPlaying) }
            val initialSeek = completedSeeks.get()
            compose
                .onNodeWithTag("completed_audio_waveform", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.6f) }
            compose.runOnIdle { assertEquals(.6f, progress.value) }
            awaitSeek(initialSeek, (durationMs * .6f).toLong())
            for (currentVariant in variants) {
                for (isExpanded in listOf(true, false)) {
                    compose.runOnUiThread {
                        variant.value = currentVariant
                        expanded.value = isExpanded
                    }
                    compose.waitForIdle()
                    compose.onNodeWithContentDescription("Play").assertIsDisplayed()
                    compose.onNodeWithTag("audio_block_duration", useUnmergedTree = true).assertIsDisplayed()
                    if (isExpanded) {
                        if (currentVariant.name in setOf("light", "rtl")) {
                            val beforeGesture = completedSeeks.get()
                            compose.onNodeWithTag("completed_audio_waveform", useUnmergedTree = true).performTouchInput {
                                val fraction = if (currentVariant.direction == LayoutDirection.Rtl) .25f else .75f
                                click(Offset(width * fraction, height / 2f))
                            }
                            compose.runOnIdle {
                                assertTrue(
                                    progress.value in 0.65f..0.85f,
                                    "Touch seeking must follow the timeline direction",
                                )
                            }
                            awaitSeek(beforeGesture, (durationMs * progress.value).toLong())
                            val beforeTranscript = completedSeeks.get()
                            compose.onNodeWithText(transcript.utterances[1].text, useUnmergedTree = true).performClick()
                            awaitSeek(beforeTranscript, midpoint)
                            val beforeReset = completedSeeks.get()
                            compose
                                .onNodeWithTag("completed_audio_waveform", useUnmergedTree = true)
                                .performSemanticsAction(SemanticsActions.SetProgress) { it(.6f) }
                            awaitSeek(beforeReset, (durationMs * .6f).toLong())
                        }
                        assertEquals(
                            1,
                            compose.onNodeWithText("Quiet streets", useUnmergedTree = true).textLayout().lineCount,
                            "Formatted caption must remain one visual line",
                        )
                        val utterance = transcript.utterances.first().text
                        val words = compose.onNodeWithText(utterance, useUnmergedTree = true).textLayout()
                        assertEquals(ResolvedTextDirection.Ltr, words.getParagraphDirection(0))
                        assertTrue(words.getBoundingBox(utterance.lastIndex - 1).left < words.getBoundingBox(utterance.lastIndex).left)
                        val elapsed =
                            compose
                                .onNodeWithText(" /", substring = true, useUnmergedTree = true)
                                .getUnclippedBoundsInRoot()
                        val total =
                            compose
                                .onNodeWithTag("audio_block_duration", useUnmergedTree = true)
                                .getUnclippedBoundsInRoot()
                        assertTrue(elapsed.right <= total.left, "Elapsed must visually precede total in every direction")
                    } else {
                        val words = compose.onNodeWithText(transcript.plainText, useUnmergedTree = true).textLayout()
                        assertEquals(ResolvedTextDirection.Ltr, words.getParagraphDirection(0))
                    }
                    capture("audio-${currentVariant.name}-${if (isExpanded) "expanded" else "compact"}")
                }
            }
        } finally {
            player.release()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val outputDir = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val output = File(outputDir, "$name.png")
        output.parentFile?.mkdirs()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(output))
    }

    private fun waveform(file: File): List<Float> {
        val bytes = file.readBytes()
        val dataOffset =
            bytes.indices.first { offset ->
                offset + 8 < bytes.size && bytes.copyOfRange(offset, offset + 4).contentEquals("data".toByteArray())
            } + 8
        val samples = (bytes.size - dataOffset) / 2
        return List(120) { bucket ->
            val start = bucket * samples / 120
            val end = (bucket + 1) * samples / 120
            (start until end).maxOf { sample ->
                val offset = dataOffset + sample * 2
                abs(((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()) / 32768f
            }
        }
    }
}

private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
}

private data class ParityVariant(
    val name: String,
    val dark: Boolean = false,
    val fontScale: Float = 1f,
    val direction: LayoutDirection = LayoutDirection.Ltr,
    val dynamicColor: Boolean = false,
    val reducedMotion: Boolean = false,
)

private val variants =
    listOf(
        ParityVariant("light"),
        ParityVariant("dark", dark = true),
        ParityVariant("large-text", fontScale = 2f),
        ParityVariant("rtl", direction = LayoutDirection.Rtl),
        ParityVariant("dynamic-light", dynamicColor = true),
        ParityVariant("dynamic-dark", dark = true, dynamicColor = true),
        ParityVariant("reduced-motion", reducedMotion = true),
    )

private val markdown =
    """# Quiet streets
    |
    |The **long *way*** home — ~~rain~~.
    |Soft line
    |continues.
    |
    |- [x] Walk slowly
    |  - Listen for birds
    |> Back before dusk.
    |
    || Place | Mood |
    || --- | --- |
    || Home | Calm |
    |
    |```text
    |  keep indentation
    |```
    |
    |<div>literal HTML</div>
    """.trimMargin()
