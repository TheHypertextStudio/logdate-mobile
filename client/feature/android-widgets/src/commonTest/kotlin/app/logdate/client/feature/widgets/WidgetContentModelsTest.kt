package app.logdate.client.feature.widgets

import app.logdate.client.media.MediaObject
import app.logdate.client.repository.journals.JournalNote
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class WidgetContentModelsTest {
    @Test
    fun `chosen text entry keeps its identity and excerpt`() {
        val note =
            JournalNote.Text(
                creationTimestamp = Instant.parse("2025-04-12T12:00:00Z"),
                lastUpdated = Instant.parse("2025-04-12T12:00:00Z"),
                content = "The garden finally bloomed",
            )

        val content = note.toChosenEntryContent()

        assertEquals(note.uid.toString(), content.noteId)
        assertEquals("The garden finally bloomed", content.summary)
        assertEquals(WidgetEntryKind.TEXT, content.kind)
    }

    @Test
    fun `chosen image entry uses its photo and caption`() {
        val note =
            JournalNote.Image(
                creationTimestamp = Instant.parse("2025-04-12T12:00:00Z"),
                lastUpdated = Instant.parse("2025-04-12T12:00:00Z"),
                mediaRef = "content://media/external/images/media/42",
                caption = "At the coast",
            )

        val content = note.toChosenEntryContent()

        assertEquals("content://media/external/images/media/42", content.imageUri)
        assertEquals("At the coast", content.summary)
        assertEquals(WidgetEntryKind.IMAGE, content.kind)
    }

    @Test
    fun `chosen video entry shows a frame from its clip`() {
        val note =
            JournalNote.Video(
                creationTimestamp = Instant.parse("2025-04-12T12:00:00Z"),
                lastUpdated = Instant.parse("2025-04-12T12:00:00Z"),
                mediaRef = "logdate-media://videos/walk.mp4",
            )

        val content = note.toChosenEntryContent()

        assertEquals("logdate-media://videos/walk.mp4", content.imageUri)
        assertEquals(WidgetEntryKind.VIDEO, content.kind)
    }

    @Test
    fun `audio entry uses its transcript as the displayed content`() {
        val note =
            JournalNote.Audio(
                creationTimestamp = Instant.parse("2025-04-12T12:00:00Z"),
                lastUpdated = Instant.parse("2025-04-12T12:00:00Z"),
                mediaRef = "file:///recording.m4a",
            )

        val content = note.toChosenEntryContent("We watched the rain roll across the bay")

        assertEquals("We watched the rain roll across the bay", content.summary)
        assertEquals(WidgetEntryKind.AUDIO, content.kind)
    }

    @Test
    fun `photo prompt rotates predictably among accessible recent images`() {
        val images =
            listOf(
                image("new", "2026-09-29T12:00:00Z"),
                image("older", "2026-09-28T12:00:00Z"),
                image("too-old", "2026-07-01T12:00:00Z"),
            )

        val first = choosePhotoPrompt(images, LocalDate(2026, 9, 30))
        val next = choosePhotoPrompt(images, LocalDate(2026, 10, 1))

        assertEquals(first, choosePhotoPrompt(images.reversed(), LocalDate(2026, 9, 30)))
        assertEquals(setOf("new", "older"), setOf(first?.uri, next?.uri))
    }

    @Test
    fun `photo prompt has a text-only fallback when no recent image is accessible`() {
        assertNull(
            choosePhotoPrompt(
                listOf(image("too-old", "2026-07-01T12:00:00Z")),
                LocalDate(2026, 9, 30),
            ),
        )
    }

    private fun image(
        uri: String,
        timestamp: String,
    ) = MediaObject.Image(
        uri = uri,
        size = 100,
        name = "$uri.jpg",
        timestamp = Instant.parse(timestamp),
    )
}
