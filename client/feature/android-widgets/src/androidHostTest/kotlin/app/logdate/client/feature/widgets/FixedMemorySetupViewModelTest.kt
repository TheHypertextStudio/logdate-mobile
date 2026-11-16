package app.logdate.client.feature.widgets

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class FixedMemorySetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val history =
        (0 until 120).map { hour ->
            val at = start + hour.hours
            if (hour % 10 == 0) {
                JournalNote.Audio(mediaRef = "file://recording-$hour.m4a", creationTimestamp = at, lastUpdated = at)
            } else {
                JournalNote.Text(content = "Entry $hour", creationTimestamp = at, lastUpdated = at)
            }
        }
    private val notes = PagedNotes(history)
    private val transcripts = CountingTranscripts()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `offers one page at a time and reads each transcript once`() =
        runTest(dispatcher) {
            val setup = FixedMemorySetupViewModel(notes.repository, transcripts.repository, null, dispatcher)
            backgroundScope.launch { setup.state.collect {} }
            advanceUntilIdle()

            assertEquals(50, setup.state.value.choices.size)
            assertEquals(
                history.last().uid,
                setup.state.value.choices
                    .first()
                    .note.uid,
            )
            assertTrue(setup.state.value.canLoadMore)
            val firstPageRecordings = transcripts.reads.toMap()

            setup.loadMore()
            setup.loadMore()
            advanceUntilIdle()
            assertEquals(100, setup.state.value.choices.size)

            setup.loadMore()
            advanceUntilIdle()
            assertEquals(120, setup.state.value.choices.size)
            assertFalse(setup.state.value.canLoadMore)
            assertTrue(firstPageRecordings.isNotEmpty())
            assertTrue(transcripts.reads.values.all { it == 1 })
            val recording = history[110].uid
            assertEquals(
                "Recording $recording",
                setup.state.value.choices
                    .first { it.note.uid == recording }
                    .transcript,
            )
        }

    @Test
    fun `keeps the saved choice even when it is older than the loaded page`() =
        runTest(dispatcher) {
            val oldest = history.first()
            val setup = FixedMemorySetupViewModel(notes.repository, transcripts.repository, oldest.uid.toString(), dispatcher)
            backgroundScope.launch { setup.state.collect {} }
            advanceUntilIdle()

            assertEquals(
                oldest.uid,
                setup.state.value.selected
                    ?.note
                    ?.uid,
            )
            assertTrue(
                setup.state.value.choices
                    .none { it.note.uid == oldest.uid },
            )
        }

    @Test
    fun `search filters the loaded entries`() =
        runTest(dispatcher) {
            val setup = FixedMemorySetupViewModel(notes.repository, transcripts.repository, null, dispatcher)
            backgroundScope.launch { setup.state.collect {} }
            advanceUntilIdle()

            setup.search("Entry 119")
            advanceUntilIdle()

            assertEquals(
                listOf(history[119].uid),
                setup.state.value.choices
                    .map { it.note.uid },
            )
        }

    private class PagedNotes(
        notes: List<JournalNote>,
    ) {
        private val all = MutableStateFlow(notes)

        val repository: JournalNotesRepository =
            object : JournalNotesRepository by unimplemented() {
                override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> =
                    all.map { notes -> notes.sortedByDescending(JournalNote::creationTimestamp).take(pageSize) }

                override fun observeNoteById(noteId: Uuid): Flow<JournalNote?> = all.map { notes -> notes.firstOrNull { it.uid == noteId } }
            }
    }

    private class CountingTranscripts {
        val reads = mutableMapOf<Uuid, Int>()

        val repository: TranscriptionRepository =
            object : TranscriptionRepository by unimplemented() {
                override suspend fun getTranscription(noteId: Uuid): TranscriptionData {
                    reads[noteId] = (reads[noteId] ?: 0) + 1
                    return TranscriptionData(
                        noteId = noteId,
                        text = "Recording $noteId",
                        status = TranscriptionStatus.COMPLETED,
                        created = Instant.DISTANT_PAST,
                        lastUpdated = Instant.DISTANT_PAST,
                        id = noteId,
                    )
                }
            }
    }
}

/** Fails any call a test did not expect, so a whole-library read cannot pass unnoticed. */
private inline fun <reified T : Any> unimplemented(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw AssertionError("Unexpected call to ${method.name}")
    } as T
