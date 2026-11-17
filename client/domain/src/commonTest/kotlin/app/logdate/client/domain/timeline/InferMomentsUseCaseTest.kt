@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.domain.timeline

import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.entity.moments.ExtractedMoment
import app.logdate.client.intelligence.entity.moments.ExtractedTextFragment
import app.logdate.client.intelligence.entity.moments.MomentExtractor
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponse
import app.logdate.client.networking.DataRestriction
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies the logic for grouping journal entries into cohesive "moments" via [InferMomentsUseCase].
 *
 * These tests cover both AI-driven inference (using generative models to identify semantic
 * boundaries and labels) and a robust heuristic fallback that groups entries by time-of-day
 * and location when AI is unavailable or fails.
 */
class InferMomentsUseCaseTest {
    @Test
    fun `unchanged source notes keep their moment identity across refresh`() {
        val notes = listOf(textNote("Retained entry", morningTime))
        val first = inferMomentsHeuristically(notes, emptyList())
        val refreshed = inferMomentsHeuristically(notes, emptyList())
        assertEquals(first.map { it.id }, refreshed.map { it.id })
    }

    @Test
    fun `oversized retained notes use bounded moment previews and keep their source text`() =
        runTest {
            val content = "q".repeat(MAX_AI_INPUT_CHARACTERS + 1)
            val note = textNote(content, morningTime)
            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            assertEquals(content, (moments.single().sourceNotes.single() as JournalNote.Text).content)
            assertTrue(
                moments
                    .single()
                    .textFragments
                    .single()
                    .text.length <= 2_000,
            )
        }

    @Test
    fun `handwriting remains a moment source without turning stroke data into text`() =
        runTest {
            val note =
                JournalNote.Handwriting(
                    creationTimestamp = morningTime,
                    lastUpdated = morningTime,
                    document =
                        app.logdate.shared.model
                            .HandwritingDocument(),
                    unreadableContent = "original stroke payload",
                )
            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())

            val moment = useCase(date, listOf(note), emptyList()).single()

            assertEquals(listOf(note), moment.sourceNotes)
            assertTrue(moment.textFragments.isEmpty())
            assertTrue(moment.media.isEmpty())
        }

    private val date = LocalDate(2025, 3, 15)

    // Use local-time-aware timestamps to avoid timezone issues in tests
    private val timezone = kotlinx.datetime.TimeZone.currentSystemDefault()
    private val morningTime = kotlinx.datetime.LocalDateTime(2025, 3, 15, 9, 0).toInstant(timezone)
    private val afternoonTime = kotlinx.datetime.LocalDateTime(2025, 3, 15, 14, 0).toInstant(timezone)
    private val eveningTime = kotlinx.datetime.LocalDateTime(2025, 3, 15, 20, 0).toInstant(timezone)
    private val baseTimestamp = morningTime

    // region Heuristic fallback tests

    @Test
    fun `heuristic groups notes by time-of-day bucket`() =
        runTest {
            val morningNote = textNote("Morning jog", morningTime)
            val afternoonNote = textNote("Lunch meeting", afternoonTime)
            val eveningNote = textNote("Dinner", eveningTime)

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(morningNote, afternoonNote, eveningNote), emptyList())

            assertEquals(3, moments.size)
            // Bare time-of-day labels are suppressed — only place-based labels are shown
            assertEquals("", moments[0].label)
            assertEquals("", moments[1].label)
            assertEquals("", moments[2].label)
        }

    @Test
    fun `heuristic merges notes in the same time bucket`() =
        runTest {
            val note1 = textNote("First thought", morningTime)
            val note2 = textNote("Second thought", morningTime + 1.hours)

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note1, note2), emptyList())

            assertEquals(1, moments.size)
            assertEquals("", moments[0].label)
            assertEquals(2, moments[0].sourceNotes.size)
            assertEquals(2, moments[0].textFragments.size)
        }

    @Test
    fun `heuristic uses place name as label when place is available`() =
        runTest {
            val placeId = Uuid.random()
            val noteWithPlace =
                JournalNote.Text(
                    uid = Uuid.random(),
                    content = "Great coffee",
                    creationTimestamp = baseTimestamp,
                    lastUpdated = baseTimestamp,
                    location =
                        NoteLocation(
                            coordinates = NoteCoordinates(latitude = 30.2672, longitude = -97.7431),
                            place = NotePlace(id = placeId, name = "Blue Bottle Coffee", latitude = 30.2672, longitude = -97.7431),
                        ),
                )

            val places =
                listOf(
                    TimelinePlaceVisit(id = placeId.toString(), name = "Blue Bottle Coffee", latitude = 30.2672, longitude = -97.7431),
                )

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(noteWithPlace), places)

            assertEquals(1, moments.size)
            assertEquals("", moments[0].label)
            assertEquals(places.single(), moments[0].labelPlace)
        }

    @Test
    fun `heuristic extracts media from image and video notes`() =
        runTest {
            val imageNote =
                JournalNote.Image(
                    uid = Uuid.random(),
                    mediaRef = "file://photo.jpg",
                    creationTimestamp = baseTimestamp,
                    lastUpdated = baseTimestamp,
                )
            val videoNote =
                JournalNote.Video(
                    uid = Uuid.random(),
                    mediaRef = "file://video.mp4",
                    creationTimestamp = baseTimestamp + 1.hours,
                    lastUpdated = baseTimestamp + 1.hours,
                )

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(imageNote, videoNote), emptyList())

            assertEquals(1, moments.size)
            assertEquals(2, moments[0].media.size)
            assertTrue(moments[0].media.any { !it.isVideo && it.uri == "file://photo.jpg" })
            assertTrue(moments[0].media.any { it.isVideo && it.uri == "file://video.mp4" })
        }

    @Test
    fun `heuristic extracts audio from audio notes`() =
        runTest {
            val audioNote =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    mediaRef = "file://voice.m4a",
                    durationMs = 45_000,
                    creationTimestamp = baseTimestamp,
                    lastUpdated = baseTimestamp,
                )

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(audioNote), emptyList())

            assertEquals(1, moments.size)
            assertEquals(1, moments[0].audio.size)
            assertEquals("file://voice.m4a", moments[0].audio[0].uri)
            assertEquals(45_000, moments[0].audio[0].durationMs)
        }

    @Test
    fun `heuristic returns empty list for empty entries`() =
        runTest {
            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, emptyList(), emptyList())

            assertTrue(moments.isEmpty())
        }

    @Test
    fun `heuristic sets inference source to TIME_OF_DAY_FALLBACK`() =
        runTest {
            val note = textNote("Hello", baseTimestamp)

            val useCase = InferMomentsUseCase(FailingMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            assertEquals(MomentInferenceSource.TIME_OF_DAY_FALLBACK, moments[0].inferenceSource)
        }

    // endregion

    // region AI inference tests

    @Test
    fun `AI-inferred moments are used when extractor succeeds`() =
        runTest {
            val note1 = textNote("Morning stuff and evening stuff", baseTimestamp)
            val note1Id = note1.uid.toString()

            val extracted =
                listOf(
                    ExtractedMoment(
                        label = "Morning routine",
                        estimatedStartHour = 8,
                        estimatedEndHour = 9,
                        sourceNoteIds = listOf(note1Id),
                        textFragments = listOf(ExtractedTextFragment("Morning stuff", note1Id)),
                        people = listOf("Jamie"),
                    ),
                    ExtractedMoment(
                        label = "That evening",
                        estimatedStartHour = 19,
                        estimatedEndHour = 20,
                        sourceNoteIds = listOf(note1Id),
                        textFragments = listOf(ExtractedTextFragment("evening stuff", note1Id)),
                        people = emptyList(),
                    ),
                )

            val useCase = InferMomentsUseCase(SucceedingMomentExtractor(extracted), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note1), emptyList())

            assertEquals(2, moments.size)
            assertEquals("Morning routine", moments[0].label)
            assertEquals("That evening", moments[1].label)
            assertEquals(MomentInferenceSource.AI_INFERRED, moments[0].inferenceSource)
            assertEquals(listOf("Jamie"), moments[0].people)
        }

    @Test
    fun `single note can appear in multiple AI-inferred moments`() =
        runTest {
            val note = textNote("Had coffee at 9am then went to the park at 2pm", baseTimestamp)
            val noteId = note.uid.toString()

            val extracted =
                listOf(
                    ExtractedMoment(
                        label = "Coffee",
                        estimatedStartHour = 9,
                        estimatedEndHour = 10,
                        sourceNoteIds = listOf(noteId),
                        textFragments = listOf(ExtractedTextFragment("Had coffee at 9am", noteId)),
                    ),
                    ExtractedMoment(
                        label = "At the park",
                        estimatedStartHour = 14,
                        estimatedEndHour = 15,
                        sourceNoteIds = listOf(noteId),
                        textFragments = listOf(ExtractedTextFragment("went to the park at 2pm", noteId)),
                    ),
                )

            val useCase = InferMomentsUseCase(SucceedingMomentExtractor(extracted), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            assertEquals(2, moments.size)
            // The same note is a source for both moments
            assertEquals(note.uid, moments[0].sourceNotes[0].uid)
            assertEquals(note.uid, moments[1].sourceNotes[0].uid)
        }

    @Test
    fun `falls back to heuristic when AI returns empty moments`() =
        runTest {
            val note = textNote("Something happened", baseTimestamp)

            val useCase = InferMomentsUseCase(SucceedingMomentExtractor(emptyList()), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            assertEquals(1, moments.size)
            assertEquals(MomentInferenceSource.TIME_OF_DAY_FALLBACK, moments[0].inferenceSource)
        }

    @Test
    fun `falls back to heuristic when AI is unavailable`() =
        runTest {
            val note = textNote("Something happened", baseTimestamp)

            val useCase = InferMomentsUseCase(UnavailableMomentExtractor(), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            assertEquals(1, moments.size)
            assertEquals(MomentInferenceSource.TIME_OF_DAY_FALLBACK, moments[0].inferenceSource)
        }

    @Test
    fun `AI-inferred moments skip unknown source note IDs`() =
        runTest {
            val note = textNote("Real note", baseTimestamp)
            val noteId = note.uid.toString()

            val extracted =
                listOf(
                    ExtractedMoment(
                        label = "Valid moment",
                        estimatedStartHour = 8,
                        estimatedEndHour = 9,
                        sourceNoteIds = listOf(noteId),
                        textFragments = listOf(ExtractedTextFragment("Real note", noteId)),
                    ),
                    ExtractedMoment(
                        label = "Ghost moment",
                        estimatedStartHour = 10,
                        estimatedEndHour = 11,
                        sourceNoteIds = listOf("nonexistent-uuid"),
                        textFragments = emptyList(),
                    ),
                )

            val useCase = InferMomentsUseCase(SucceedingMomentExtractor(extracted), FakeAudioTagRepository())
            val moments = useCase(date, listOf(note), emptyList())

            // Ghost moment is dropped because its source notes don't exist
            assertEquals(1, moments.size)
            assertEquals("Valid moment", moments[0].label)
        }

    // endregion

    // region Helpers

    private fun textNote(
        content: String,
        timestamp: Instant,
    ) = JournalNote.Text(
        uid = Uuid.random(),
        content = content,
        creationTimestamp = timestamp,
        lastUpdated = timestamp,
    )

    /** Creates a MomentExtractor that always returns an error (offline), forcing heuristic fallback. */
    private fun FailingMomentExtractor() =
        MomentExtractor(
            generativeAICache = NoOpCache(),
            generativeAIChatClient = NoOpChatClient(),
            networkAvailabilityMonitor = OfflineNetworkMonitor(),
            dataUsagePolicy = UnrestrictedDataUsagePolicy(),
        )

    /** Creates a MomentExtractor that always returns unavailable. */
    private fun UnavailableMomentExtractor() =
        MomentExtractor(
            generativeAICache = NoOpCache(),
            generativeAIChatClient = NoOpChatClient(),
            networkAvailabilityMonitor = OfflineNetworkMonitor(),
            dataUsagePolicy = UnrestrictedDataUsagePolicy(),
        )

    /** Creates a MomentExtractor that returns pre-configured moments via a fake AI client. */
    private fun SucceedingMomentExtractor(extractedMoments: List<ExtractedMoment>) =
        MomentExtractor(
            generativeAICache = NoOpCache(),
            generativeAIChatClient = MomentsReturningChatClient(extractedMoments),
            networkAvailabilityMonitor = OnlineNetworkMonitor(),
            dataUsagePolicy = UnrestrictedDataUsagePolicy(),
        )

    // endregion
}

// region Test fakes

private class NoOpCache : GenerativeAICache {
    override suspend fun getEntry(request: GenerativeAICacheRequest) = null

    override suspend fun putEntry(
        request: GenerativeAICacheRequest,
        content: String,
    ) {}

    override suspend fun purge() {}
}

private class NoOpChatClient : GenerativeAIChatClient {
    override val providerId: String = "noop"
    override val defaultModel: String? = "noop"

    override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> = AIResult.Error(AIError.InvalidResponse)
}

private class OfflineNetworkMonitor : NetworkAvailabilityMonitor {
    private val flow = MutableSharedFlow<NetworkState>(replay = 1)

    override fun isNetworkAvailable(): Boolean = false

    override fun observeNetwork(): SharedFlow<NetworkState> = flow
}

private class OnlineNetworkMonitor : NetworkAvailabilityMonitor {
    private val flow = MutableSharedFlow<NetworkState>(replay = 1)

    override fun isNetworkAvailable(): Boolean = true

    override fun observeNetwork(): SharedFlow<NetworkState> = flow
}

private class UnrestrictedDataUsagePolicy : DataUsagePolicy {
    override val policy: Flow<DataUsageMode> = MutableStateFlow(DataUsageMode.Unrestricted)

    override suspend fun currentMode() = DataUsageMode.Unrestricted

    override suspend fun currentRestriction(): DataRestriction = DataRestriction.NONE
}

private class MomentsReturningChatClient(
    private val moments: List<ExtractedMoment>,
) : GenerativeAIChatClient {
    override val providerId: String = "fake"
    override val defaultModel: String? = "fake-model"

    override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> {
        val json =
            Json.encodeToString(
                ListSerializer(ExtractedMoment.serializer()),
                moments,
            )
        val responseJson = """{"moments": $json}"""
        return AIResult.Success(GenerativeAIResponse(responseJson))
    }
}

// endregion
