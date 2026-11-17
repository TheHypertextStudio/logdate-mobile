package app.logdate.client.domain.storage

import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Listing one kind of stored thing largest first, and deleting several of them. */
class StoredItemsTest {
    private val day = Instant.parse("2026-10-03T09:00:00Z")
    private val later = Instant.parse("2026-10-04T09:00:00Z")

    private fun photo(
        ref: String,
        at: Instant = day,
    ) = JournalNote.Image(creationTimestamp = at, lastUpdated = at, mediaRef = ref)

    private fun video(
        ref: String,
        durationMs: Long? = null,
    ) = JournalNote.Video(creationTimestamp = day, lastUpdated = day, mediaRef = ref, durationMs = durationMs)

    private fun recording(
        ref: String,
        transcription: String = "",
    ) = JournalNote.Audio(mediaRef = ref, durationMs = 61_000, creationTimestamp = day, lastUpdated = day, transcription = transcription)

    private fun entry(text: String) = JournalNote.Text(creationTimestamp = day, lastUpdated = day, content = text)

    private fun observe(
        notes: List<JournalNote>,
        sizes: Map<String, Long> = emptyMap(),
    ) = ObserveStoredItemsUseCase(FakeNotes(notes), StoredMediaSizes { sizes[it] }, Dispatchers.Unconfined)

    @Test
    fun `photos are listed largest first with their sizes`() =
        runTest {
            val small = photo("small")
            val large = photo("large")
            val middle = photo("middle")
            val items =
                observe(
                    notes = listOf(small, video("clip"), large, entry("Dinner"), middle),
                    sizes = mapOf("small" to 10, "large" to 900, "middle" to 400, "clip" to 5_000),
                )(StoredKind.Photos).first()

            assertEquals(listOf(large.uid, middle.uid, small.uid), items.map { it.id })
            assertEquals(listOf<Long?>(900, 400, 10), items.map { it.sizeBytes })
            assertEquals(listOf("large", "middle", "small"), items.map { it.mediaRef })
        }

    @Test
    fun `a photo that is not on this device has no size and comes last`() =
        runTest {
            val elsewhere = photo("elsewhere", at = later)
            val here = photo("here")
            val items = observe(listOf(elsewhere, here), sizes = mapOf("here" to 1))(StoredKind.Photos).first()

            assertEquals(listOf(here.uid, elsewhere.uid), items.map { it.id })
            assertNull(items.last().sizeBytes)
        }

    @Test
    fun `photos of the same size are listed newest first`() =
        runTest {
            val older = photo("older", at = day)
            val newer = photo("newer", at = later)
            val items = observe(listOf(older, newer), sizes = mapOf("older" to 7, "newer" to 7))(StoredKind.Photos).first()

            assertEquals(listOf(newer.uid, older.uid), items.map { it.id })
        }

    @Test
    fun `a video carries how long it runs`() =
        runTest {
            val items = observe(listOf(video("clip", durationMs = 42_000)), sizes = mapOf("clip" to 5_000))(StoredKind.Videos).first()

            assertEquals(42_000, items.single().durationMs)
            assertEquals(5_000, items.single().sizeBytes)
        }

    @Test
    fun `a recording carries its length and the start of what was said`() =
        runTest {
            val items =
                observe(
                    listOf(recording("memo", transcription = "  Walked to the\nharbour before breakfast  ")),
                    sizes = mapOf("memo" to 2_000),
                )(StoredKind.Recordings).first()

            assertEquals(61_000, items.single().durationMs)
            assertEquals("Walked to the harbour before breakfast", items.single().text)
        }

    @Test
    fun `a written entry is as large as its text`() =
        runTest {
            val short = entry("Tea")
            val long = entry("Café by the harbour")
            val items = observe(listOf(short, long))(StoredKind.WrittenEntries).first()

            assertEquals(listOf(long.uid, short.uid), items.map { it.id })
            // "é" takes two bytes.
            assertEquals(listOf<Long?>(20, 3), items.map { it.sizeBytes })
            assertEquals("Café by the harbour", items.first().text)
        }

    @Test
    fun `the list follows the notes as they change`() =
        runTest {
            val notes = FakeNotes(listOf(photo("a"), photo("b")))
            val observed = ObserveStoredItemsUseCase(notes, StoredMediaSizes { 1 }, Dispatchers.Unconfined)(StoredKind.Photos)
            assertEquals(2, observed.first().size)

            notes.all.value = notes.all.value.drop(1)

            assertEquals(1, observed.first().size)
        }

    @Test
    fun `deleting removes each item and says how much was freed`() =
        runTest {
            val notes = FakeNotes(emptyList())
            val delete = DeleteStoredItemsUseCase(RemoveNoteUseCase(notes))
            val first = item(sizeBytes = 900)
            val second = item(sizeBytes = 100)
            val unsized = item(sizeBytes = null)

            val result = delete(listOf(first, second, unsized))

            assertEquals(listOf(first.id, second.id, unsized.id), notes.removed)
            assertEquals(1_000, result.freedBytes)
            assertEquals(listOf(first, second, unsized), result.deleted)
            assertEquals(emptyList(), result.failed)
        }

    @Test
    fun `an item that cannot be deleted is reported and the rest still go`() =
        runTest {
            val stuck = item(sizeBytes = 900)
            val fine = item(sizeBytes = 100)
            val notes = FakeNotes(emptyList(), failing = setOf(stuck.id))
            val delete = DeleteStoredItemsUseCase(RemoveNoteUseCase(notes))

            val result = delete(listOf(stuck, fine))

            assertEquals(listOf(fine.id), notes.removed)
            assertEquals(100, result.freedBytes)
            assertEquals(listOf(stuck), result.failed)
        }

    private fun item(sizeBytes: Long?) = StoredItem(id = Uuid.random(), kind = StoredKind.Photos, sizeBytes = sizeBytes, createdAt = day)

    private class FakeNotes(
        notes: List<JournalNote>,
        private val failing: Set<Uuid> = emptySet(),
    ) : JournalNotesRepository {
        val all = MutableStateFlow(notes)
        val removed = mutableListOf<Uuid>()

        override val allNotesObserved: Flow<List<JournalNote>> = all

        override suspend fun removeById(noteId: Uuid) {
            check(noteId !in failing) { "Cannot delete $noteId" }
            removed += noteId
        }

        override fun observeNotesInJournal(journalId: Uuid) = flowOf(emptyList<JournalNote>())

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()

        override fun observeNotesInRange(
            start: Instant,
            end: Instant,
        ) = flowOf(emptyList<JournalNote>())

        override fun observeNotesPage(
            pageSize: Int,
            offset: Int,
        ) = flowOf(emptyList<JournalNote>())

        override fun observeNotesStream(pageSize: Int) = flowOf(emptyList<JournalNote>())

        override fun observeRecentNotes(limit: Int) = flowOf(emptyList<JournalNote>())

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = all.value.firstOrNull { it.uid == noteId }

        override suspend fun create(note: JournalNote): Uuid = note.uid

        override suspend fun remove(note: JournalNote) = removeById(note.uid)

        override suspend fun create(
            note: JournalNote,
            journalId: Uuid,
        ) = Unit

        override suspend fun removeFromJournal(
            noteId: Uuid,
            journalId: Uuid,
        ) = Unit
    }
}
