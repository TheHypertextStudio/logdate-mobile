package app.logdate.client.e2e

import app.logdate.client.domain.dayboundary.DayBoundarySettings
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.LocalFirstHealthRepository
import app.logdate.client.health.model.DayBounds
import app.logdate.client.health.model.SleepSession
import app.logdate.client.health.model.TimeOfDay
import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.intelligence.generativeai.GenerativeAIRequest
import app.logdate.client.intelligence.generativeai.GenerativeAIResponse
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.streak.StreakSettingsRepository
import app.logdate.feature.onboarding.flow.OnboardingDeviceState
import app.logdate.feature.onboarding.flow.OnboardingDeviceStateRepository
import app.logdate.feature.onboarding.flow.OnboardingEntryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class OnboardingFakeLocationTrackingSettingsRepository : LocationTrackingSettingsRepository {
    private val state = MutableStateFlow(LocationTrackingSettings())

    fun reset() {
        state.value = LocationTrackingSettings()
    }

    override suspend fun getSettings(): LocationTrackingSettings = state.value

    override fun observeSettings(): Flow<LocationTrackingSettings> = state.asStateFlow()

    override suspend fun updateSettings(settings: LocationTrackingSettings) {
        state.value = settings
    }

    override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
        state.value = state.value.copy(backgroundTrackingEnabled = enabled)
    }
}

internal class OnboardingFakeDayBoundarySettingsRepository : DayBoundarySettingsRepository {
    private val state = MutableStateFlow(DayBoundarySettings())

    fun reset() {
        state.value = DayBoundarySettings()
    }

    override suspend fun getSettings(): DayBoundarySettings = state.value

    override fun observeSettings(): Flow<DayBoundarySettings> = state.asStateFlow()

    override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) {
        state.value = DayBoundarySettings(sleepBasedBoundariesEnabled = enabled)
    }
}

internal class OnboardingFakeOnboardingDeviceStateRepository : OnboardingDeviceStateRepository {
    private val state = MutableStateFlow(OnboardingDeviceState())
    override val deviceState: StateFlow<OnboardingDeviceState> = state.asStateFlow()

    fun reset() {
        state.value = OnboardingDeviceState()
    }

    override suspend fun markRecommendationsHandled() {
        state.value = state.value.copy(recommendationsHandledOnThisDevice = true)
    }

    override suspend fun markDayBoundariesHandled() {
        state.value = state.value.copy(dayBoundariesHandledOnThisDevice = true)
    }

    override suspend fun markLocationHandled() {
        state.value = state.value.copy(locationHandledOnThisDevice = true)
    }

    override suspend fun markNotificationsHandled() {
        state.value = state.value.copy(notificationsHandledOnThisDevice = true)
    }

    override suspend fun markWatchHandled() {
        state.value = state.value.copy(watchHandledOnThisDevice = true)
    }

    override suspend fun markAccountHandled() {
        state.value = state.value.copy(accountHandledOnThisDevice = true)
    }

    override suspend fun setActiveEntryMode(entryMode: OnboardingEntryMode) {
        state.value = state.value.copy(activeEntryMode = entryMode)
    }

    override suspend fun clear() {
        reset()
    }
}

internal class OnboardingFakeMediaManager : MediaManager {
    private val sampleMedia =
        listOf(
            MediaObject.Image(
                uri = "content://test/image-1",
                size = 128_000,
                name = "sunrise.jpg",
                timestamp = Clock.System.now(),
            ),
            MediaObject.Image(
                uri = "content://test/image-2",
                size = 256_000,
                name = "friends.jpg",
                timestamp = Clock.System.now(),
            ),
        )
    private val imported = mutableListOf<String>()

    fun reset() {
        imported.clear()
    }

    override suspend fun getMedia(uri: String): MediaObject = sampleMedia.first { it.uri == uri }

    override suspend fun deleteOwnedMedia(uri: String): Boolean = false

    override suspend fun exists(mediaId: String): Boolean = sampleMedia.any { it.uri == mediaId }

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(sampleMedia.take(limit))

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> = flowOf(sampleMedia)

    override suspend fun addToDefaultCollection(uri: String) {
        imported += uri
    }

    override suspend fun readMedia(uri: String): MediaPayload =
        MediaPayload(
            fileName = "test.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1,
            data = byteArrayOf(1),
        )

    override suspend fun saveMedia(payload: MediaPayload): String = "content://saved/${payload.fileName}"

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String = "content://saved/$fileName"
}

internal class OnboardingFakeGenerativeAiChatClient : GenerativeAIChatClient {
    override val providerId: String = "fake"
    override val defaultModel: String? = "fake-model"

    fun reset() = Unit

    override suspend fun submit(request: GenerativeAIRequest): AIResult<GenerativeAIResponse> =
        AIResult.Success(
            GenerativeAIResponse(
                content =
                    if (request.responseFormat != null) {
                        """{"message":"It's good to meet you. You're ready to start logging."}"""
                    } else {
                        "You took a quiet evening walk along the water."
                    },
                model = defaultModel,
            ),
        )
}

internal class OnboardingFakeNetworkAvailabilityMonitor : NetworkAvailabilityMonitor {
    private val state = MutableSharedFlow<NetworkState>(replay = 1)

    init {
        reset()
    }

    fun reset() {
        state.tryEmit(NetworkState.Connected(Clock.System.now()))
    }

    override fun isNetworkAvailable(): Boolean = true

    override fun observeNetwork(): SharedFlow<NetworkState> = state.asSharedFlow()
}

internal class OnboardingFakeHealthRepository : LocalFirstHealthRepository {
    var available: Boolean = false
    var sleepPermissionsGranted: Boolean = false

    fun reset() {
        available = false
        sleepPermissionsGranted = false
    }

    override suspend fun getHealthDataAvailability(): HealthDataAvailability =
        if (available) {
            HealthDataAvailability.AVAILABLE
        } else {
            HealthDataAvailability.NOT_AVAILABLE
        }

    override suspend fun isHealthDataAvailable(): Boolean = available

    override suspend fun getAvailableDataTypes(): List<String> = emptyList()

    override suspend fun hasSleepPermissions(): Boolean = sleepPermissionsGranted

    override suspend fun requestSleepPermissions(): Boolean {
        sleepPermissionsGranted = true
        return true
    }

    override suspend fun getSleepSessions(
        start: Instant,
        end: Instant,
    ): List<SleepSession> = emptyList()

    override suspend fun getAverageWakeUpTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = null

    override suspend fun getAverageSleepTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = null

    override suspend fun getDayBoundsForDate(
        date: LocalDate,
        timeZone: TimeZone,
        sleepBasedBoundariesEnabled: Boolean,
    ): DayBounds = DayBounds(start = Clock.System.now(), end = Clock.System.now())
}

internal class OnboardingFakeJournalNotesRepository : JournalNotesRepository {
    private val notes = MutableStateFlow<List<JournalNote>>(emptyList())
    override val allNotesObserved: Flow<List<JournalNote>> = notes.asStateFlow()

    fun reset() {
        notes.value = emptyList()
    }

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = notes.asStateFlow()

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = notes.asStateFlow()

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = notes.asStateFlow()

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = notes.value.firstOrNull { it.uid == noteId }

    override suspend fun create(note: JournalNote): Uuid {
        notes.value = notes.value + note
        return note.uid
    }

    override suspend fun remove(note: JournalNote) {
        notes.value = notes.value - note
    }

    override suspend fun removeById(noteId: Uuid) {
        notes.value = notes.value.filterNot { it.uid == noteId }
    }

    override suspend fun create(
        note: JournalNote,
        journalId: Uuid,
    ) {
        notes.value = notes.value + note
    }

    override suspend fun removeFromJournal(
        noteId: Uuid,
        journalId: Uuid,
    ) = Unit
}

internal class OnboardingFakeStreakSettingsRepository : StreakSettingsRepository {
    private val enabled = MutableStateFlow(true)
    private val cached = MutableStateFlow(0)

    fun reset() {
        enabled.value = true
        cached.value = 0
    }

    override fun observeStreakEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override suspend fun isStreakEnabled(): Boolean = enabled.value

    override suspend fun setStreakEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override fun observeCachedStreak(): Flow<Int> = cached.asStateFlow()

    override suspend fun getCachedStreak(): Int = cached.value

    override suspend fun setCachedStreak(value: Int) {
        cached.value = value
    }
}
