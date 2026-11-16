package app.logdate.client.sync

import app.logdate.client.domain.watch.WindowBudget
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.datalayer.NoteDataMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PhoneWearSyncBridgeTest {
    @Test
    fun `an older watch receives the newest notes up to its library budget`() =
        runTest {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val history = (0 until WindowBudget.DEFAULT_MAX_ITEMS + 50).map { minute -> text(start + minute.minutes) }
            val sentPaths = mutableListOf<String>()
            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = NewestNotes(history).repository,
                    noteDataMapper = NoteDataMapper(),
                    transport = RecordingTransport(sentPaths),
                    audioStreamOpener =
                        object : PhoneAudioStreamOpener {
                            override fun open(mediaRef: String): InputStream? = null
                        },
                )

            bridge.publishNotesToWatch("watch")

            val newest = history.sortedByDescending(JournalNote::creationTimestamp).take(WindowBudget.DEFAULT_MAX_ITEMS)
            assertEquals(newest.map { NoteDataMapper.notePath(it.uid) }, sentPaths)
        }

    private fun text(at: Instant) = JournalNote.Text(content = "Note at $at", creationTimestamp = at, lastUpdated = at)

    private class NewestNotes(
        private val notes: List<JournalNote>,
    ) {
        val repository: JournalNotesRepository =
            object : JournalNotesRepository by failOnUnexpectedCalls() {
                override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> =
                    flowOf(notes.sortedByDescending(JournalNote::creationTimestamp).take(pageSize))
            }
    }

    private class RecordingTransport(
        private val sentPaths: MutableList<String>,
    ) : PhoneWearTransport {
        override suspend fun putDataItem(
            path: String,
            data: Map<String, String>,
            urgent: Boolean,
        ): Boolean {
            sentPaths += path
            return true
        }

        override suspend fun streamToNode(
            nodeId: String,
            channelPath: String,
            inputStream: InputStream,
        ): Boolean = true
    }
}

/** A repository that fails any read the bridge was not expected to make, such as every note at once. */
private inline fun <reified T : Any> failOnUnexpectedCalls(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw AssertionError("Unexpected call to ${method.name}")
    } as T
