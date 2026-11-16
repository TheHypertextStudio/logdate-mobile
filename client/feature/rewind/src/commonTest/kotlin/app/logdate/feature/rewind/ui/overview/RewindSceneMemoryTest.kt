package app.logdate.feature.rewind.ui.overview

import app.logdate.shared.model.RewindContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RewindSceneMemoryTest {
    private val time = Instant.fromEpochSeconds(0)

    @Test
    fun sceneSamplesOriginalMediaWithoutTurningWritingIntoAnImageCaption() {
        val memories =
            listOf(
                RewindContent.TextNote(time, Uuid.random(), "What I actually wrote."),
                RewindContent.Image(time, Uuid.random(), "file://journal/photo.jpg", "The photograph's own caption."),
                RewindContent.AudioNote(time, Uuid.random(), "file://journal/voice.m4a", 42000, "My actual transcript."),
            ).toSceneMemories()
        assertEquals(3, memories.size)
        assertEquals("The photograph's own caption.", assertIs<RewindSceneMemory.Visual>(memories[0]).caption)
        assertEquals("What I actually wrote.", assertIs<RewindSceneMemory.Writing>(memories[1]).text)
        val voice = assertIs<RewindSceneMemory.Recording>(memories[2])
        assertEquals(42000L, voice.durationMs)
        assertEquals("My actual transcript.", voice.transcript)
    }

    @Test
    fun aVideoOnlyWeekKeepsItsRealVideoResource() {
        val scene = listOf(RewindContent.Video(time, Uuid.random(), "file://journal/clip.mp4", duration = 10.seconds)).toSceneMemories()
        val video = assertIs<RewindSceneMemory.Visual>(scene.single())
        assertEquals("file://journal/clip.mp4", video.uri)
        assertEquals(true, video.isVideo)
    }

    @Test
    fun samplingIsBoundedAndDoesNotInventARecordingOrTranscript() {
        val scene = List(12) { index -> RewindContent.TextNote(time, Uuid.random(), "Memory $index") }.toSceneMemories()
        assertEquals(3, scene.size)
        assertEquals(listOf("Memory 0", "Memory 1", "Memory 2"), scene.map { assertIs<RewindSceneMemory.Writing>(it).text })
    }
}
