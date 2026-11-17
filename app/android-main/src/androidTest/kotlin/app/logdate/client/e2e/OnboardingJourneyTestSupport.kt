package app.logdate.client.e2e

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.recommendation.AmbientPromptTime
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import app.logdate.client.health.LocalFirstHealthRepository
import app.logdate.client.intelligence.generativeai.GenerativeAIChatClient
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.media.MediaManager
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.repository.account.AccountRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.streak.StreakSettingsRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.feature.onboarding.flow.OnboardingDeviceStateRepository
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.profile.LogDateProfile
import app.logdate.shared.model.user.AppSecurityLevel
import app.logdate.shared.model.user.UserData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

internal class OnboardingJourneyEnvironment {
    val userStateRepository = OnboardingFakeUserStateRepository()
    val profileRepository = OnboardingFakeProfileRepository()
    val accountRepository = OnboardingFakeAccountRepository()
    val sessionStorage = OnboardingFakeSessionStorage()
    val memoriesSettingsRepository = OnboardingFakeMemoriesSettingsRepository()
    val locationSettingsRepository = OnboardingFakeLocationTrackingSettingsRepository()
    val dayBoundarySettingsRepository = OnboardingFakeDayBoundarySettingsRepository()
    val onboardingDeviceStateRepository = OnboardingFakeOnboardingDeviceStateRepository()
    val mediaManager = OnboardingFakeMediaManager()
    val aiClient = OnboardingFakeGenerativeAiChatClient()
    val networkMonitor = OnboardingFakeNetworkAvailabilityMonitor()
    val healthRepository = OnboardingFakeHealthRepository()
    val journalNotesRepository = OnboardingFakeJournalNotesRepository()
    val streakSettingsRepository = OnboardingFakeStreakSettingsRepository()

    val module: Module =
        module {
            single<UserStateRepository> { userStateRepository }
            single<ProfileRepository> { profileRepository }
            single<AccountRepository> { accountRepository }
            single<SessionStorage> { sessionStorage }
            single<MemoriesSettingsRepository> { memoriesSettingsRepository }
            single<LocationTrackingSettingsRepository> { locationSettingsRepository }
            single<DayBoundarySettingsRepository> { dayBoundarySettingsRepository }
            single<OnboardingDeviceStateRepository> { onboardingDeviceStateRepository }
            single<MediaManager> { mediaManager }
            single<GenerativeAIChatClient> { aiClient }
            single<NetworkAvailabilityMonitor> { networkMonitor }
            single<LocalFirstHealthRepository> { healthRepository }
            single<JournalNotesRepository> { journalNotesRepository }
            single<StreakSettingsRepository> { streakSettingsRepository }
        }

    fun reset() {
        userStateRepository.reset()
        profileRepository.reset()
        accountRepository.reset()
        sessionStorage.reset()
        memoriesSettingsRepository.reset()
        locationSettingsRepository.reset()
        dayBoundarySettingsRepository.reset()
        onboardingDeviceStateRepository.reset()
        mediaManager.reset()
        aiClient.reset()
        networkMonitor.reset()
        healthRepository.reset()
        journalNotesRepository.reset()
        streakSettingsRepository.reset()
    }

    fun seedFreshFlow(
        hasIntro: Boolean = false,
        hasBirthday: Boolean = false,
        hasCloudAccount: Boolean = false,
        notificationsHandled: Boolean = false,
        healthAvailable: Boolean = false,
        healthPermissionsGranted: Boolean = false,
    ) {
        reset()
        if (hasIntro) {
            runBlocking {
                profileRepository.updateDisplayName("Test User")
                profileRepository.updateBio("I like journaling", "I like journaling")
            }
        }
        if (hasBirthday) {
            runBlocking {
                userStateRepository.setBirthday(Clock.System.now())
            }
        }
        if (hasCloudAccount) {
            accountRepository.current.value =
                LogDateAccount(
                    username = "tester",
                    displayName = "Test User",
                )
            runBlocking {
                sessionStorage.saveSession(
                    UserSession(
                        accessToken = "token",
                        refreshToken = "refresh",
                        accountId = "account",
                    ),
                )
            }
        }
        if (notificationsHandled) {
            runBlocking {
                onboardingDeviceStateRepository.markNotificationsHandled()
            }
        }
        healthRepository.available = healthAvailable
        healthRepository.sleepPermissionsGranted = healthPermissionsGranted
    }
}

internal class OnboardingKoinModuleOverrideRule(
    private val module: Module,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                startKoinIfAppHasNot(context)
                loadKoinModules(module)
                base.evaluate()
            }
        }
}

internal fun <A : ComponentActivity> SemanticsNodeInteraction.captureStepScreenshot(
    composeRule: AndroidComposeTestRule<ActivityScenarioRule<A>, A>,
    testName: String,
    stepName: String,
) {
    val context = composeRule.activity
    val outputDir = File(context.getExternalFilesDir("test-artifacts"), "onboarding/$testName").apply { mkdirs() }
    val outputFile = File(outputDir, stepName)
    outputFile.outputStream().use { stream ->
        captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    }
}

internal class OnboardingFakeUserStateRepository : UserStateRepository {
    private val _userData = MutableStateFlow(UserData())
    val current: MutableStateFlow<UserData> = _userData
    override val userData: Flow<UserData> = _userData.asStateFlow()

    fun reset() {
        _userData.value = UserData()
    }

    override suspend fun setBirthday(birthday: Instant) {
        _userData.value = _userData.value.copy(birthday = birthday)
    }

    override suspend fun setIsOnboardingComplete(isComplete: Boolean) {
        _userData.value =
            _userData.value.copy(
                isOnboarded = isComplete,
                onboardedDate = if (isComplete) Clock.System.now() else Instant.DISTANT_PAST,
            )
    }

    override suspend fun setBiometricEnabled(isEnabled: Boolean) {
        _userData.value =
            _userData.value.copy(
                securityLevel = if (isEnabled) AppSecurityLevel.BIOMETRIC else AppSecurityLevel.NONE,
            )
    }

    override suspend fun addFavoriteNote(vararg noteId: String) {
        _userData.value = _userData.value.copy(favoriteNotes = (_userData.value.favoriteNotes + noteId).distinct())
    }
}

internal class OnboardingFakeProfileRepository : ProfileRepository {
    private val profileState = MutableStateFlow(LogDateProfile())
    override val currentProfile: Flow<LogDateProfile> = profileState.asStateFlow()

    fun reset() {
        profileState.value = LogDateProfile()
    }

    override suspend fun updateDisplayName(displayName: String): Result<LogDateProfile> {
        profileState.value = profileState.value.copy(displayName = displayName)
        return Result.success(profileState.value)
    }

    override suspend fun updateBirthday(birthday: Instant?): Result<LogDateProfile> {
        profileState.value = profileState.value.copy(birthday = birthday)
        return Result.success(profileState.value)
    }

    override suspend fun updateProfilePhoto(profilePhotoUri: String?): Result<LogDateProfile> {
        profileState.value = profileState.value.copy(profilePhotoUri = profilePhotoUri)
        return Result.success(profileState.value)
    }

    override suspend fun updateBio(
        bio: String?,
        originalBio: String?,
    ): Result<LogDateProfile> {
        profileState.value = profileState.value.copy(bio = bio, originalBio = originalBio)
        return Result.success(profileState.value)
    }

    override suspend fun getCurrentProfile(): LogDateProfile = profileState.value

    override suspend fun clearProfile(): Result<Unit> {
        profileState.value = LogDateProfile()
        return Result.success(Unit)
    }
}

internal class OnboardingFakeAccountRepository : AccountRepository {
    val current = MutableStateFlow<LogDateAccount?>(null)
    override val currentAccount: Flow<LogDateAccount?> = current.asStateFlow()

    fun reset() {
        current.value = null
    }

    override suspend fun updateProfile(
        displayName: String?,
        username: String?,
    ): Result<LogDateAccount> {
        val updated =
            (current.value ?: LogDateAccount(username = username ?: "user", displayName = displayName ?: "User")).copy(
                username = username ?: current.value?.username ?: "user",
                displayName = displayName ?: current.value?.displayName ?: "User",
            )
        current.value = updated
        return Result.success(updated)
    }

    override suspend fun refreshAccount(): Result<LogDateAccount> =
        Result.success(
            current.value ?: LogDateAccount(username = "user", displayName = "User"),
        )

    override suspend fun checkUsernameAvailability(username: String): Result<Boolean> = Result.success(true)
}

internal class OnboardingFakeSessionStorage : SessionStorage {
    private val sessionFlow = MutableStateFlow<UserSession?>(null)

    fun reset() {
        sessionFlow.value = null
    }

    override fun getSession(): UserSession? = sessionFlow.value

    override fun getSessionFlow(): Flow<UserSession?> = sessionFlow.asStateFlow()

    override suspend fun hasValidSession(): Boolean = sessionFlow.value != null

    override suspend fun saveSession(session: UserSession) {
        sessionFlow.value = session
    }

    override suspend fun clearSession() {
        sessionFlow.value = null
    }
}

internal class OnboardingFakeMemoriesSettingsRepository : MemoriesSettingsRepository {
    private val state = MutableStateFlow(MemoriesSettings())

    fun reset() {
        state.value = MemoriesSettings()
    }

    override suspend fun getSettings(): MemoriesSettings = state.value

    override fun observeSettings(): Flow<MemoriesSettings> = state.asStateFlow()

    override suspend fun updateSettings(settings: MemoriesSettings) {
        state.value = settings
    }

    override suspend fun setContextualRecommendationsEnabled(enabled: Boolean) {
        state.value = state.value.copy(contextualRecommendationsEnabled = enabled)
    }

    override suspend fun setAmbientPromptsEnabled(enabled: Boolean) {
        state.value = state.value.copy(ambientPromptsEnabled = enabled)
    }

    override suspend fun setCaptureNudgesEnabled(enabled: Boolean) {
        state.value = state.value.copy(captureNudgesEnabled = enabled)
    }

    override suspend fun setDraftRescueEnabled(enabled: Boolean) {
        state.value = state.value.copy(draftRescueEnabled = enabled)
    }

    override suspend fun setMemoryRecallNotificationsEnabled(enabled: Boolean) {
        state.value = state.value.copy(memoryRecallNotificationsEnabled = enabled)
    }

    override suspend fun setMorningPromptEnabled(enabled: Boolean) {
        state.value = state.value.copy(morningPromptEnabled = enabled)
    }

    override suspend fun setEveningPromptEnabled(enabled: Boolean) {
        state.value = state.value.copy(eveningPromptEnabled = enabled)
    }

    override suspend fun setMorningPromptTime(time: AmbientPromptTime) {
        state.value = state.value.copy(morningPromptTime = time)
    }

    override suspend fun setEveningPromptTime(time: AmbientPromptTime) {
        state.value = state.value.copy(eveningPromptTime = time)
    }

    override suspend fun setAiRecallEnabled(enabled: Boolean) {
        state.value = state.value.copy(aiRecallEnabled = enabled)
    }

    override suspend fun setRecallMode(mode: RecallMode) {
        state.value = state.value.copy(recallMode = mode)
    }

    override suspend fun setWidgetContentTypes(types: Set<WidgetContentType>) {
        state.value = state.value.copy(widgetContentTypes = types)
    }
}
