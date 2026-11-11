package app.logdate.client.domain.rewind

import app.logdate.client.domain.fakes.FakeNetworkAvailabilityMonitor
import app.logdate.client.domain.fakes.resourceKeyText
import app.logdate.client.domain.media.IndexMediaForPeriodUseCase
import app.logdate.client.domain.notes.FetchNotesForDayUseCase
import app.logdate.client.intelligence.AIError
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.availability.RewindAIAvailability
import app.logdate.client.intelligence.availability.RewindAITier
import app.logdate.client.intelligence.cache.GenerativeAICache
import app.logdate.client.intelligence.cache.GenerativeAICacheEntry
import app.logdate.client.intelligence.cache.GenerativeAICacheRequest
import app.logdate.client.intelligence.curation.BeatBucketer
import app.logdate.client.intelligence.curation.DiversitySelector
import app.logdate.client.intelligence.curation.NoSignalsExtractor
import app.logdate.client.intelligence.curation.PhotoHardFilter
import app.logdate.client.intelligence.curation.RewindMediaCurator
import app.logdate.client.intelligence.curation.SignificanceScorer
import app.logdate.client.intelligence.entity.people.PeopleExtractor
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponse
import app.logdate.client.intelligence.narrative.RewindSequencer
import app.logdate.client.intelligence.narrative.WeekNarrativeSynthesizer
import app.logdate.client.intelligence.rewind.strategy.FullLLMRewindStrategy
import app.logdate.client.intelligence.rewind.strategy.LocalRewindStrategy
import app.logdate.client.intelligence.rewind.strategy.QuotesOnlyLLMRewindStrategy
import app.logdate.client.intelligence.rewind.strategy.RewindStrategySelector
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import app.logdate.client.networking.DataRestriction
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.repository.rewind.RewindGenerationManager
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.shared.model.Location
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindGenerationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for [GetWeekRewindUseCase].
 *
 * Validates the calculation of weekly boundaries based on user preferences
 * for the first day of the week and ensures correct delegation to the
 * underlying rewind retrieval logic.
 */
class GetWeekRewindUseCaseTest {
    private lateinit var rewindRepository: FakeRewindRepository
    private lateinit var getRewindUseCase: GetRewindUseCase
    private lateinit var useCase: GetWeekRewindUseCase

    @BeforeTest
    fun setUp() {
        rewindRepository = FakeRewindRepository()
        val generationManager = FakeRewindGenerationManager()
        val journalNotesRepository = FakeJournalNotesRepository()
        val fetchNotesForDay = FetchNotesForDayUseCase(journalNotesRepository)
        val indexedMediaRepository = FakeIndexedMediaRepository()
        val indexMediaForPeriod =
            IndexMediaForPeriodUseCase(
                mediaManager = FakeMediaManager(),
                indexedMediaRepository = indexedMediaRepository,
            )
        val networkMonitor = FakeNetworkAvailabilityMonitor()
        val dataUsagePolicy =
            object : DataUsagePolicy {
                override val policy = MutableStateFlow(DataUsageMode.Unrestricted)

                override suspend fun currentMode() = DataUsageMode.Unrestricted

                override suspend fun currentRestriction(): DataRestriction = DataRestriction.NONE
            }
        val narrativeSynthesizer =
            WeekNarrativeSynthesizer(
                generativeAICache = FakeGenerativeAICache(),
                genAIClient = FakeGenerativeAIChatClient(NARRATIVE_RESPONSE),
                networkAvailabilityMonitor = networkMonitor,
                dataUsagePolicy = dataUsagePolicy,
            )
        val rewindSequencer = RewindSequencer(resolveText = resourceKeyText)
        val peopleExtractor =
            PeopleExtractor(
                generativeAICache = FakeGenerativeAICache(),
                generativeAIChatClient = FakeGenerativeAIChatClient(null),
                networkAvailabilityMonitor = networkMonitor,
                dataUsagePolicy = dataUsagePolicy,
            )
        val curator =
            RewindMediaCurator(
                signalExtractor = NoSignalsExtractor(),
                hardFilter = PhotoHardFilter(),
                scorer = SignificanceScorer(),
                bucketer = BeatBucketer(),
                selector = DiversitySelector(),
            )
        val localStrategy = LocalRewindStrategy(curator = curator, sequencer = rewindSequencer)
        val fullStrategy =
            FullLLMRewindStrategy(
                narrativeSynthesizer = narrativeSynthesizer,
                curator = curator,
                sequencer = rewindSequencer,
                localFallback = localStrategy,
            )
        val quotesOnlyStrategy = QuotesOnlyLLMRewindStrategy(localFallback = localStrategy)
        val strategySelector =
            RewindStrategySelector(
                availability =
                    object : RewindAIAvailability {
                        override suspend fun current(): RewindAITier = RewindAITier.FULL
                    },
                fullStrategy = fullStrategy,
                quotesOnlyStrategy = quotesOnlyStrategy,
                localStrategy = localStrategy,
            )
        val generateBasicRewindUseCase =
            GenerateBasicRewindUseCase(
                rewindRepository = rewindRepository,
                generationManager = generationManager,
                fetchNotesForDay = fetchNotesForDay,
                indexedMediaRepository = indexedMediaRepository,
                indexMediaForPeriod = indexMediaForPeriod,
                generateRewindTitle = GenerateRewindTitleUseCase(resolveText = resourceKeyText),
                strategySelector = strategySelector,
                peopleExtractor = peopleExtractor,
                locationHistoryRepository = FakeLocationHistoryRepository(),
                transcriptionRepository = FakeTranscriptionRepository(),
                mediaManager = FakeMediaManager(),
            )
        getRewindUseCase =
            GetRewindUseCase(
                rewindRepository = rewindRepository,
                generationManager = generationManager,
                generateBasicRewindUseCase = generateBasicRewindUseCase,
            )
        useCase = createUseCaseWithWeekStart(DayOfWeek.SUNDAY)
    }

    /**
     * Creates a [GetWeekRewindUseCase] configured with a fixed first day of week preference.
     */
    private fun createUseCaseWithWeekStart(dayOfWeek: DayOfWeek): GetWeekRewindUseCase =
        GetWeekRewindUseCase(
            getRewindUseCase = getRewindUseCase,
            firstDayOfWeekPreference = flowOf(dayOfWeek),
        )

    @Test
    fun `invoke should request rewind for current week starting Sunday by default`() =
        runTest {
            // Given
            rewindRepository.rewindForPeriod = buildRewind()

            // When
            useCase().first()

            // Then
            assertEquals(1, rewindRepository.invocations.size)
            val params = rewindRepository.invocations.first()

            assertTrue(params.start <= params.end)

            val timeDifference = params.end.toEpochMilliseconds() - params.start.toEpochMilliseconds()
            // 7 days in milliseconds, with an extra hour tolerance for daylight saving "fall back" transitions
            val maxWeekMs = (7 * 24 + 1) * 60 * 60 * 1000L
            assertTrue(timeDifference <= maxWeekMs)
        }

    @Test
    fun `invoke should handle custom week start day`() =
        runTest {
            // Given
            rewindRepository.rewindForPeriod = buildRewind()

            // When
            createUseCaseWithWeekStart(DayOfWeek.MONDAY)().first()

            // Then
            assertEquals(1, rewindRepository.invocations.size)
            val params = rewindRepository.invocations.first()

            assertTrue(params.start <= params.end)
        }

    @Test
    fun `invoke should handle different week start days`() =
        runTest {
            // Given
            rewindRepository.rewindForPeriod = buildRewind()

            // When
            val result1 = createUseCaseWithWeekStart(DayOfWeek.SUNDAY)().first()
            val result2 = createUseCaseWithWeekStart(DayOfWeek.MONDAY)().first()
            val result3 = createUseCaseWithWeekStart(DayOfWeek.FRIDAY)().first()

            // Then
            assertEquals(3, rewindRepository.invocations.size)
            assertTrue(result1 is RewindQueryResult.Success)
            assertTrue(result2 is RewindQueryResult.Success)
            assertTrue(result3 is RewindQueryResult.Success)

            rewindRepository.invocations.forEach { params ->
                assertTrue(params.start <= params.end)
            }
        }

    @Test
    fun `invoke should return Success when rewind is available`() =
        runTest {
            // Given
            val expectedRewind = buildRewind()
            rewindRepository.rewindForPeriod = expectedRewind

            // When
            val result = useCase().first()

            // Then
            assertEquals(RewindQueryResult.Success(expectedRewind), result)
            assertEquals(1, rewindRepository.invocations.size)
        }

    @Test
    fun `invoke should delegate to GetRewindUseCase correctly`() =
        runTest {
            // Given
            rewindRepository.rewindForPeriod = buildRewind()

            // When
            createUseCaseWithWeekStart(DayOfWeek.WEDNESDAY)().first()

            // Then
            assertEquals(1, rewindRepository.invocations.size)
            val params = rewindRepository.invocations.first()
            val timezone = TimeZone.currentSystemDefault()

            val startLocalDate = params.start.toLocalDateTime(timezone).date
            val startOfStartDay = startLocalDate.atStartOfDayIn(timezone)
            assertEquals(startOfStartDay, params.start)

            val endLocalDate = params.end.toLocalDateTime(timezone).date
            val startOfEndDay = endLocalDate.atStartOfDayIn(timezone)
            assertEquals(startOfEndDay, params.end)
        }

    @Test
    fun `invoke should handle all days of week as start day`() =
        runTest {
            // Given
            rewindRepository.rewindForPeriod = buildRewind()

            // When
            val daysOfWeek = DayOfWeek.entries
            daysOfWeek.forEach { dayOfWeek ->
                createUseCaseWithWeekStart(dayOfWeek)().first()
            }

            // Then
            assertEquals(daysOfWeek.size, rewindRepository.invocations.size)

            rewindRepository.invocations.forEach { params ->
                assertTrue(params.start <= params.end)
            }
        }

    private fun buildRewind(
        start: Instant = Clock.System.now(),
        end: Instant = Clock.System.now(),
    ): Rewind =
        Rewind(
            uid = Uuid.random(),
            startDate = start,
            endDate = end,
            generationDate = Clock.System.now(),
            label = "2024#01",
            title = "Test rewind",
            content = emptyList(),
        )

    private class FakeGenerativeAICache : GenerativeAICache {
        override suspend fun getEntry(request: GenerativeAICacheRequest): GenerativeAICacheEntry? = null

        override suspend fun putEntry(
            request: GenerativeAICacheRequest,
            content: String,
        ) {}

        override suspend fun purge() {}
    }

    private class FakeGenerativeAIChatClient(
        private val response: String?,
    ) : GenerativeAIChatClient {
        override val providerId: String = "fake"
        override val defaultModel: String? = "fake-model"

        override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> =
            if (response == null) {
                AIResult.Error(AIError.InvalidResponse)
            } else {
                AIResult.Success(GenerativeAIResponse(response))
            }
    }

    private class FakeRewindRepository : RewindRepository {
        val invocations = mutableListOf<RewindParams>()
        var rewindForPeriod: Rewind? = null

        override fun getAllRewinds(): Flow<List<Rewind>> = flowOf(emptyList())

        override fun getRewind(uid: Uuid): Flow<Rewind> =
            flowOf(
                rewindForPeriod ?: buildFallbackRewind(uid),
            )

        override fun getRewindBetween(
            start: Instant,
            end: Instant,
        ): Flow<Rewind?> {
            invocations.add(RewindParams(start, end))
            return flowOf(rewindForPeriod)
        }

        override suspend fun isRewindAvailable(
            start: Instant,
            end: Instant,
        ): Boolean = rewindForPeriod != null

        @Deprecated("Use GenerateBasicRewindUseCase instead")
        override suspend fun createRewind(
            start: Instant,
            end: Instant,
        ): Rewind = buildFallbackRewind(Uuid.random()).copy(startDate = start, endDate = end)

        override suspend fun saveRewind(rewind: Rewind) {
            rewindForPeriod = rewind
        }

        override fun getRewindsInRange(
            start: Instant,
            end: Instant,
        ): Flow<List<Rewind>> = flowOf(listOfNotNull(rewindForPeriod))

        override suspend fun deleteRewind(uid: Uuid) {
            if (rewindForPeriod?.uid == uid) {
                rewindForPeriod = null
            }
        }

        override suspend fun markAsViewed(uid: Uuid) {
            // No-op stub for tests that don't exercise view tracking.
        }

        override suspend fun tagAsMilestone(
            uid: Uuid,
            signal: String,
        ) {
            // No-op stub for tests that don't exercise the milestone path.
        }

        private fun buildFallbackRewind(uid: Uuid): Rewind {
            val now = Clock.System.now()
            return Rewind(
                uid = uid,
                startDate = now,
                endDate = now,
                generationDate = now,
                label = "2024#01",
                title = "Fallback rewind",
                content = emptyList(),
            )
        }
    }

    private class FakeRewindGenerationManager : RewindGenerationManager {
        override suspend fun requestGeneration(
            startTime: Instant,
            endTime: Instant,
        ): RewindGenerationRequest =
            RewindGenerationRequest(
                id = Uuid.random(),
                startTime = startTime,
                endTime = endTime,
                requestTime = Clock.System.now(),
                status = RewindGenerationRequest.Status.PENDING,
                details = null,
                rewindId = null,
            )

        override suspend fun getGenerationRequest(requestId: Uuid): RewindGenerationRequest? = null

        override fun observeGenerationStatus(requestId: Uuid): Flow<RewindGenerationRequest?> = flowOf(null)

        override suspend fun isGenerationInProgress(
            startTime: Instant,
            endTime: Instant,
        ): Boolean = false

        override suspend fun updateRequestStatus(
            id: Uuid,
            status: RewindGenerationRequest.Status,
            details: String?,
        ): Boolean = true

        override suspend fun cancelGeneration(requestId: Uuid): Boolean = false
    }

    private class FakeJournalNotesRepository : JournalNotesRepository {
        override val allNotesObserved: Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesInRange(
            start: Instant,
            end: Instant,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesPage(
            pageSize: Int,
            offset: Int,
        ): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(emptyList())

        override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = flowOf(emptyList())

        override suspend fun create(note: JournalNote): Uuid = note.uid

        override suspend fun remove(note: JournalNote) {}

        override suspend fun removeById(noteId: Uuid) {}

        override suspend fun create(
            note: JournalNote,
            journalId: Uuid,
        ) {}

        override suspend fun removeFromJournal(
            noteId: Uuid,
            journalId: Uuid,
        ) {}

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = null

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()
    }

    private class FakeIndexedMediaRepository : IndexedMediaRepository {
        override suspend fun indexImage(
            uri: String,
            timestamp: Instant,
        ): IndexedMedia.Image = IndexedMedia.Image(Uuid.random(), uri, timestamp)

        override suspend fun indexVideo(
            uri: String,
            timestamp: Instant,
            duration: Duration,
        ): IndexedMedia.Video = IndexedMedia.Video(Uuid.random(), uri, timestamp, duration = duration)

        override suspend fun getByUid(uid: Uuid): IndexedMedia? = null

        override fun getForPeriod(
            startTime: Instant,
            endTime: Instant,
        ): Flow<List<IndexedMedia>> = flowOf(emptyList())

        override suspend fun isIndexed(uri: String): Boolean = false

        override suspend fun remove(uid: Uuid): Boolean = false

        override suspend fun updateCaption(
            uid: Uuid,
            caption: String?,
        ): IndexedMedia? = null

        override fun observeAllMedia(): Flow<List<IndexedMedia>> = flowOf(emptyList())

        override fun getMediaCount(): Flow<Int> = flowOf(0)

        override suspend fun getExifMetadata(uid: Uuid): app.logdate.client.repository.media.ExifMetadata? = null
    }

    private class FakeMediaManager : MediaManager {
        override suspend fun getMedia(uri: String): MediaObject =
            MediaObject.Image(
                uri = uri,
                size = 0,
                name = "mock",
                timestamp = Clock.System.now(),
            )

        override suspend fun deleteOwnedMedia(uri: String): Boolean = false

        override suspend fun exists(mediaId: String): Boolean = true

        override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(emptyList())

        override suspend fun queryMediaByDate(
            start: Instant,
            end: Instant,
        ): Flow<List<MediaObject>> = flowOf(emptyList())

        override suspend fun addToDefaultCollection(uri: String) {}

        override suspend fun readMedia(uri: String): MediaPayload =
            MediaPayload(
                fileName = uri.substringAfterLast('/'),
                mimeType = "application/octet-stream",
                sizeBytes = 0,
                data = ByteArray(0),
            )

        override suspend fun saveMedia(payload: MediaPayload): String = "file://stub/${payload.fileName}"

        override suspend fun saveMediaFromFile(
            sourceFilePath: String,
            fileName: String,
            mimeType: String,
        ): String = "file:///tmp/$fileName"
    }

    private class FakeLocationHistoryRepository : LocationHistoryRepository {
        override suspend fun getAllLocationHistory(): List<LocationHistoryItem> = emptyList()

        override fun observeLocationHistory(): Flow<List<LocationHistoryItem>> = flowOf(emptyList())

        override suspend fun getRecentLocationHistory(limit: Int): List<LocationHistoryItem> = emptyList()

        override suspend fun getLocationHistoryBetween(
            startTime: Instant,
            endTime: Instant,
        ): List<LocationHistoryItem> = emptyList()

        override suspend fun getLastLocation(): LocationHistoryItem? = null

        override fun observeLastLocation(): Flow<LocationHistoryItem?> = flowOf(null)

        override suspend fun logLocation(
            location: Location,
            userId: String,
            deviceId: String,
            confidence: Float,
            isGenuine: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun logLocation(record: LocationLogRecord): Result<Unit> = Result.success(Unit)

        override suspend fun deleteLocationEntry(
            userId: String,
            deviceId: String,
            timestamp: Instant,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun deleteLocationsBetween(
            startTime: Instant,
            endTime: Instant,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getLocationCount(): Int = 0
    }

    private class FakeTranscriptionRepository : TranscriptionRepository {
        override suspend fun requestTranscription(noteId: Uuid): Boolean = true

        override suspend fun getTranscription(noteId: Uuid): TranscriptionData? = null

        override fun observeTranscription(noteId: Uuid): Flow<TranscriptionData?> = flowOf(null)

        override suspend fun getPendingTranscriptions(): List<TranscriptionData> = emptyList()

        override suspend fun updateTranscription(
            noteId: Uuid,
            text: String?,
            status: TranscriptionStatus,
            errorMessage: String?,
        ): Boolean = true

        override suspend fun deleteTranscription(noteId: Uuid): Boolean = true
    }

    private companion object {
        private val NARRATIVE_RESPONSE =
            """
            {
              "themes": ["test"],
              "emotionalTone": "calm",
              "storyBeats": [
                {
                  "moment": "Test moment",
                  "context": "Test context",
                  "emotionalWeight": "neutral",
                  "evidenceIds": []
                }
              ],
              "overallNarrative": "Test narrative."
            }
            """.trimIndent()
    }
}
