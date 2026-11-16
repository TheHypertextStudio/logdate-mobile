package app.logdate.client.data.restore

import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.shared.model.SerializableTextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NativeArchive3EntryDraftsTest {
    private val stamp = Instant.parse("2026-10-23T00:00:00Z")
    private val draftId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val noteId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val journalId = Uuid.parse("33333333-3333-3333-3333-333333333333")

    @Test
    fun `simple unpublished text remains an editable Apple draft with stable identities`() {
        val source =
            EntryDraft(
                id = draftId,
                notes = listOf(JournalNote.Text(noteId, stamp, stamp, "Still writing")),
                createdAt = stamp,
                updatedAt = stamp,
                selectedJournalIds = listOf(journalId),
            )

        val converted = NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = false).single()

        assertEquals(source.id, converted.id)
        assertEquals(source.selectedJournalIds, converted.selectedJournalIds)
        assertEquals(source.createdAt, converted.createdAt)
        assertEquals(source.updatedAt, converted.lastModifiedAt)
        assertEquals(SerializableTextBlock(noteId, stamp, content = "Still writing"), converted.blocks.single())
    }

    @Test
    fun `metadata without an Apple draft field stays in Android custody`() {
        val changed = JournalNote.Text(noteId, stamp, stamp.plus(kotlin.time.Duration.parse("1m")), "Still writing")
        val source = EntryDraft(draftId, listOf(changed), stamp, stamp)
        assertFailsWith<IllegalStateException> { NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = false) }
        assertFailsWith<IllegalStateException> {
            NativeArchive3EntryDrafts.convert(
                listOf(source.copy(notes = listOf(changed.copy(lastUpdated = stamp)), markId = "mark")),
                ownerBound = false,
            )
        }
    }

    @Test
    fun `duplicate source blocks cannot change editor identity`() {
        val note = JournalNote.Text(noteId, stamp, stamp, "First")
        val source = EntryDraft(draftId, listOf(note, note.copy(content = "Second")), stamp, stamp)
        assertFailsWith<IllegalStateException> { NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = false) }
    }

    @Test
    fun `GPS coordinates remain attached to a simple text draft block`() {
        val coordinates = NoteCoordinates(36.1699, -115.1398, altitude = 625.0)
        val note = JournalNote.Text(noteId, stamp, stamp, "At the park", location = NoteLocation(coordinates))
        val source = EntryDraft(draftId, listOf(note), stamp, stamp)

        val converted = NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = false)
        val block = converted.single().blocks.single()

        assertEquals(SerializableTextBlock(noteId, stamp, 36.1699, -115.1398, 625.0, "At the park"), block)
    }

    @Test
    fun `place identity and GPS accuracy stay in Android custody until the Apple draft can retain them`() {
        val coordinates = NoteCoordinates(36.1699, -115.1398, accuracy = 4f)
        val note = JournalNote.Text(noteId, stamp, stamp, "At the park", location = NoteLocation(coordinates))
        val source = EntryDraft(draftId, listOf(note), stamp, stamp)
        assertFailsWith<IllegalStateException> { NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = false) }

        val place = NotePlace(Uuid.parse("44444444-4444-4444-4444-444444444444"), name = "Park")
        assertFailsWith<IllegalStateException> {
            NativeArchive3EntryDrafts.convert(
                listOf(source.copy(notes = listOf(note.copy(location = NoteLocation(place = place))))),
                ownerBound = false,
            )
        }
    }

    @Test
    fun `unscoped entry drafts cannot cross an account boundary`() {
        val source = EntryDraft(draftId, listOf(JournalNote.Text(noteId, stamp, stamp, "Private")), stamp, stamp)
        assertFailsWith<IllegalStateException> { NativeArchive3EntryDrafts.convert(listOf(source), ownerBound = true) }
        assertEquals(emptyList(), NativeArchive3EntryDrafts.convert(emptyList(), ownerBound = true))
    }
}
