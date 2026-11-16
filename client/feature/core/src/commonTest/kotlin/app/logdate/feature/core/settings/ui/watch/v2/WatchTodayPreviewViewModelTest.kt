package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.client.domain.recommendation.GetMemoryRecallUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTodayPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `today's summary reads only today's entries`() =
        runTest(dispatcher) {
            val now = Clock.System.now()
            val notes = RangeOnlyNotes(listOf(text(now), text(now - 30.days)))
            val preview = WatchTodayPreviewViewModel(notes, GetMemoryRecallUseCase(notes))
            backgroundScope.launch { preview.summary.collect {} }
            runCurrent()

            assertEquals(1, preview.summary.value?.momentCount)

            notes.add(text(now))
            runCurrent()

            assertEquals(2, preview.summary.value?.momentCount)
        }

    private fun text(at: Instant) = JournalNote.Text(content = "Walked to the market", creationTimestamp = at, lastUpdated = at)

    /** Fails any whole-library read, so the preview has to ask for today's range. */
    private class RangeOnlyNotes(
        initial: List<JournalNote>,
    ) : JournalNotesRepository {
        private val notes = MutableStateFlow(initial)

        fun add(note: JournalNote) {
            notes.value += note
        }

        override val allNotesObserved: Flow<List<JournalNote>> = flow { throw AssertionError("read every note") }

        override fun observeNotesInRange(
            start: Instant,
            end: Instant,
        ): Flow<List<JournalNote>> = notes.map { all -> all.filter { it.creationTimestamp >= start && it.creationTimestamp < end } }

        override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = error("unused")

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = error("unused")

        override fun observeNotesPage(
            pageSize: Int,
            offset: Int,
        ): Flow<List<JournalNote>> = error("unused")

        override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = error("unused")

        override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = error("unused")

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = error("unused")

        override suspend fun create(note: JournalNote): Uuid = error("unused")

        override suspend fun remove(note: JournalNote) = error("unused")

        override suspend fun removeById(noteId: Uuid) = error("unused")

        override suspend fun create(
            note: JournalNote,
            journalId: Uuid,
        ) = error("unused")

        override suspend fun removeFromJournal(
            noteId: Uuid,
            journalId: Uuid,
        ) = error("unused")
    }
}
