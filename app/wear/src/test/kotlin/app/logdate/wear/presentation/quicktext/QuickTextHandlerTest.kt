package app.logdate.wear.presentation.quicktext

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.wear.location.WearLocationCaptureCoordinator
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class QuickTextHandlerTest {
    private val notesRepository = mockk<JournalNotesRepository>(relaxed = true)
    private val locationCoordinator = mockk<WearLocationCaptureCoordinator>(relaxed = true)

    @Test
    fun `save persists the spoken text and then completes`() =
        runTest {
            coEvery { notesRepository.create(any()) } returns Uuid.random()
            coEvery { locationCoordinator.captureForJournalEntry() } returns null

            var completed = false
            saveQuickTextAndComplete(
                notesRepository = notesRepository,
                locationCaptureCoordinator = locationCoordinator,
                spokenText = "remember to buy milk",
                onDone = { completed = true },
            )

            coVerify(exactly = 1) {
                notesRepository.create(
                    match<JournalNote.Text> { it.content == "remember to buy milk" },
                )
            }
            assertTrue(completed, "onDone should run after a successful save")
        }

    @Test
    fun `onDone still fires when the repository throws so the user is not stranded`() =
        runTest {
            coEvery { locationCoordinator.captureForJournalEntry() } returns null
            coEvery { notesRepository.create(any()) } throws RuntimeException("disk full")

            var completed = false
            saveQuickTextAndComplete(
                notesRepository = notesRepository,
                locationCaptureCoordinator = locationCoordinator,
                spokenText = "anything",
                onDone = { completed = true },
            )

            assertTrue(completed, "try/finally must release the quick-text screen even on failure")
        }

    @Test
    fun `onDone still fires when location capture throws`() =
        runTest {
            coEvery { locationCoordinator.captureForJournalEntry() } throws
                RuntimeException("location service unavailable")

            var completed = false
            saveQuickTextAndComplete(
                notesRepository = notesRepository,
                locationCaptureCoordinator = locationCoordinator,
                spokenText = "doesn't matter",
                onDone = { completed = true },
            )

            assertTrue(completed)
            // Repository should never be called if we never got past location capture.
            coVerify(exactly = 0) { notesRepository.create(any()) }
        }

    @Test
    fun `save propagates a captured location into the note payload`() =
        runTest {
            val captured =
                NoteLocation(coordinates = NoteCoordinates(latitude = 37.7749, longitude = -122.4194))
            coEvery { locationCoordinator.captureForJournalEntry() } returns captured
            coEvery { notesRepository.create(any()) } returns Uuid.random()

            saveQuickTextAndComplete(
                notesRepository = notesRepository,
                locationCaptureCoordinator = locationCoordinator,
                spokenText = "with location",
                onDone = {},
            )

            coVerify(exactly = 1) {
                notesRepository.create(match<JournalNote.Text> { it.location == captured })
            }
        }

    @Test
    fun `onDone is the last thing to run on the success path`() =
        runTest {
            val callOrder = mutableListOf<String>()
            coEvery { locationCoordinator.captureForJournalEntry() } answers {
                callOrder += "location"
                null
            }
            coEvery { notesRepository.create(any()) } answers {
                callOrder += "create"
                Uuid.random()
            }

            saveQuickTextAndComplete(
                notesRepository = notesRepository,
                locationCaptureCoordinator = locationCoordinator,
                spokenText = "ordering",
                onDone = { callOrder += "onDone" },
            )

            assertEquals(listOf("location", "create", "onDone"), callOrder)
        }

    @Test
    fun `save never writes the spoken words to the log`() =
        runTest {
            coEvery { notesRepository.create(any()) } returns Uuid.random()
            coEvery { locationCoordinator.captureForJournalEntry() } returns null
            val log = CapturingAntilog()
            Napier.base(log)
            try {
                saveQuickTextAndComplete(
                    notesRepository = notesRepository,
                    locationCaptureCoordinator = locationCoordinator,
                    spokenText = "call the pharmacy about refills",
                    onDone = {},
                )
            } finally {
                Napier.takeLogarithm(log)
            }

            assertTrue(log.messages.isNotEmpty())
            assertFalse(log.messages.any { "pharmacy" in it })
        }

    private class CapturingAntilog : Antilog() {
        val messages = mutableListOf<String>()

        override fun performLog(
            priority: LogLevel,
            tag: String?,
            throwable: Throwable?,
            message: String?,
        ) {
            if (message != null) messages += message
        }
    }
}
