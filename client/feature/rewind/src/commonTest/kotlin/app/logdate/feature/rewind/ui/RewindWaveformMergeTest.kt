package app.logdate.feature.rewind.ui

import app.logdate.client.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RewindWaveformMergeTest {
    private val walk =
        AudioNoteRewindPanelUiState(
            sourceId = Uuid.random(),
            timestamp = Instant.parse("2026-10-21T12:00:00Z"),
            uri = "file://walk.m4a",
            durationMs = 1_000,
            transcriptionText = null,
            dateFormatted = UiText.Raw("Wednesday"),
        )
    private val call = walk.copy(sourceId = Uuid.random(), uri = "file://call.m4a")

    @Test
    fun `waveforms that finish loading fill in only their own recordings`() {
        val shown = RewindDetailUiState.Success(listOf(walk, call))

        val merged = shown.withWaveforms(mapOf(walk.uri to listOf(0.2f, 0.8f))) as RewindDetailUiState.Success

        assertEquals(listOf(0.2f, 0.8f), (merged.panels[0] as AudioNoteRewindPanelUiState).cachedAmplitudes)
        assertNull((merged.panels[1] as AudioNoteRewindPanelUiState).cachedAmplitudes)
    }

    @Test
    fun `panels show before any waveform loads`() {
        val shown = RewindDetailUiState.Success(listOf(walk))

        assertSame(shown, shown.withWaveforms(emptyMap()))
    }
}
