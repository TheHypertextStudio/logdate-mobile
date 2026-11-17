package app.logdate.ui.notes

import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.HandwritingDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NoteWordsTest {
    private val at = Instant.parse("2026-11-17T12:00:00Z")

    @Test
    fun `a photo without a caption has no words so it is shown rather than named`() {
        assertNull(JournalNote.Image(creationTimestamp = at, lastUpdated = at, mediaRef = "logdate-media://images/a.jpg").noteWords())
        assertNull(JournalNote.Video(creationTimestamp = at, lastUpdated = at, mediaRef = "logdate-media://videos/a.mp4").noteWords())
        assertNull(JournalNote.Handwriting(creationTimestamp = at, lastUpdated = at, document = HandwritingDocument()).noteWords())
    }

    @Test
    fun `captions and text are collapsed to one readable line`() {
        val image = JournalNote.Image(creationTimestamp = at, lastUpdated = at, mediaRef = "x", caption = "  Coffee\n\noutside  ")
        assertEquals("Coffee outside", image.noteWords())
        assertEquals("A slower start", JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "A slower start").noteWords())
        assertNull(JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "   ").noteWords())
    }

    @Test
    fun `Markdown syntax is not shown as part of a note's words`() {
        val text = JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "# Morning\n\nA **slow** start")
        assertEquals("Morning A slow start", text.noteWords())
        val image = JournalNote.Image(creationTimestamp = at, lastUpdated = at, mediaRef = "x", caption = "_Coffee_ outside")
        assertEquals("Coffee outside", image.noteWords())
    }

    @Test
    fun `a one-line headline is the first line with words`() {
        val text = JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "\n\n## Morning\n\nA **slow** start")
        assertEquals("Morning", text.noteFirstLine())
        assertNull(JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = " \n ").noteFirstLine())
    }

    @Test
    fun `a voice note speaks through its transcript before its caption`() {
        val audio =
            JournalNote.Audio(
                mediaRef = "logdate-media://audio/a.m4a",
                creationTimestamp = at,
                lastUpdated = at,
                caption = "Walk home",
                transcription = "The rain finally stopped",
            )
        assertEquals("The rain finally stopped", audio.noteWords())
        assertEquals("Walk home", audio.copy(transcription = "").noteWords())
    }

    @Test
    fun `a waveform thumbnail keeps each run's loudest sample`() {
        assertEquals(listOf(0.9f, 0.4f), listOf(0.1f, 0.9f, 0.4f, 0.2f).peaks(2))
        assertEquals(listOf(0.3f), listOf(0.3f).peaks(12))
        assertEquals(emptyList(), emptyList<Float>().peaks(12))
    }
}
