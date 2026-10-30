package app.logdate.feature.core.account

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.domain.account.AuthenticateWithPasskeyUseCase
import app.logdate.client.domain.account.BackfillLocalDataUseCase
import app.logdate.client.domain.account.BackfilledAccountTracker
import app.logdate.client.domain.account.CheckUsernameAvailabilityUseCase
import app.logdate.client.domain.account.CreatePasskeyAccountUseCase
import app.logdate.client.domain.account.EmailVerificationAvailability
import app.logdate.client.domain.account.EnqueueAllLocalDataUseCase
import app.logdate.client.domain.account.GetCurrentEntitlementUseCase
import app.logdate.client.domain.account.SignInWithGoogleUseCase
import app.logdate.client.domain.account.TriggerInitialSyncUseCase
import app.logdate.client.domain.account.VerifyEmailUseCase
import app.logdate.client.networking.PasskeyApiClientContract
import app.logdate.client.networking.ServerDiscoveryClient
import app.logdate.client.networking.ServerHealthChecker
import app.logdate.client.networking.ServerHealthInfo
import app.logdate.client.permissions.EmailVerificationManager
import app.logdate.client.permissions.EmailVerificationOutcome
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.feature.core.settings.ui.ServerConfigurationCoordinator
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.Journal
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyCapabilities
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_validation_display_name_required
import logdate.client.feature.core.generated.resources.account_validation_username_too_short
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Focused on the email-verification slice introduced in Commits 5 and 7. The rest of the
 * onboarding flow is exercised through the per-use-case unit tests and the manual emulator pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudAccountOnboardingViewModelTest {
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
    fun `init caches email verification availability flag true`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = true)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isEmailVerificationAvailable)
        }

    @Test
    fun `init caches email verification availability flag false`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = false)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isEmailVerificationAvailable)
        }

    @Test
    fun `goToNextStep from PasskeyCreation enters EmailVerification when available`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = true)
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.PasskeyCreation)
            advanceUntilIdle()

            viewModel.goToNextStep()

            assertEquals(OnboardingStep.EmailVerification, viewModel.uiState.value.currentStep)
        }

    @Test
    fun `goToNextStep from PasskeyCreation skips to Complete when unavailable`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = false)
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.PasskeyCreation)
            advanceUntilIdle()

            viewModel.goToNextStep()

            assertEquals(OnboardingStep.Complete, viewModel.uiState.value.currentStep)
        }

    @Test
    fun `goToPreviousStep from EmailVerification returns to PasskeyCreation`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = true)
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.PasskeyCreation)
            advanceUntilIdle()
            viewModel.goToNextStep()
            assertEquals(OnboardingStep.EmailVerification, viewModel.uiState.value.currentStep)

            viewModel.goToPreviousStep()

            assertEquals(OnboardingStep.PasskeyCreation, viewModel.uiState.value.currentStep)
        }

    @Test
    fun `onVerifyEmailClicked surfaces Success and auto-advances after the 1s hold`() =
        runTest {
            val verifiedAt = Instant.fromEpochSeconds(1_775_083_422)
            val viewModel =
                buildViewModel(
                    isEmailVerificationAvailable = true,
                    verifyOutcome = EmailVerificationOutcome.Success("u@example.com", verifiedAt),
                )
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.EmailVerification)
            advanceUntilIdle()

            viewModel.onVerifyEmailClicked()
            // Run tasks ready at virtual t=0 — the outcome resolves but the 1s hold is still pending.
            runCurrent()

            val outcome = assertIs<EmailVerificationOutcome.Success>(viewModel.uiState.value.emailVerificationOutcome)
            assertEquals("u@example.com", outcome.email)
            assertFalse(viewModel.uiState.value.isVerifyingEmail)
            assertEquals(OnboardingStep.EmailVerification, viewModel.uiState.value.currentStep)

            // Advancing past the 1s hold lets the auto-advance fire.
            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(OnboardingStep.Complete, viewModel.uiState.value.currentStep)
        }

    @Test
    fun `onVerifyEmailClicked surfaces a Failed outcome without advancing the step`() =
        runTest {
            val viewModel =
                buildViewModel(
                    isEmailVerificationAvailable = true,
                    verifyOutcome = EmailVerificationOutcome.Failed("issuer_signature_invalid"),
                )
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.EmailVerification)
            advanceUntilIdle()

            viewModel.onVerifyEmailClicked()
            advanceUntilIdle()
            advanceTimeBy(2_000)
            advanceUntilIdle()

            val outcome = assertIs<EmailVerificationOutcome.Failed>(viewModel.uiState.value.emailVerificationOutcome)
            assertEquals("issuer_signature_invalid", outcome.reason)
            // Failure must not auto-advance — the user gets a retry affordance.
            assertEquals(OnboardingStep.EmailVerification, viewModel.uiState.value.currentStep)
        }

    @Test
    fun `onSkipEmailVerification jumps to Complete and marks isSkipped`() =
        runTest {
            val viewModel = buildViewModel(isEmailVerificationAvailable = true)
            advanceUntilIdle()
            viewModel.setInitialStep(OnboardingStep.EmailVerification)
            advanceUntilIdle()

            viewModel.onSkipEmailVerification()

            assertEquals(OnboardingStep.Complete, viewModel.uiState.value.currentStep)
            assertTrue(viewModel.uiState.value.isSkipped)
        }

    @Test
    fun `account signup carries the original introduction with the displayed bio`() =
        runTest {
            val passkeyRepo = FakePasskeyAccountRepository()
            val profileRepo =
                FakeProfileRepository(
                    LogDateProfile(
                        displayName = "Writer",
                        bio = "A polished description",
                        originalBio = "I keep a journal every morning",
                    ),
                )
            val viewModel =
                buildViewModel(
                    isEmailVerificationAvailable = false,
                    passkeyRepo = passkeyRepo,
                    profileRepo = profileRepo,
                )
            advanceUntilIdle()
            viewModel.updateDisplayName("Writer")
            viewModel.updateUsername("writer")
            viewModel.createAccount()
            advanceUntilIdle()

            assertEquals("A polished description", passkeyRepo.lastCreationRequest?.bio)
            assertEquals("I keep a journal every morning", passkeyRepo.lastCreationRequest?.originalBio)
        }

    @Test
    fun `createAccount reports every invalid field at once`() =
        runTest {
            val passkeyRepo = FakePasskeyAccountRepository()
            val viewModel = buildViewModel(isEmailVerificationAvailable = false, passkeyRepo = passkeyRepo)
            advanceUntilIdle()
            viewModel.updateDisplayName("")
            viewModel.updateUsername("ab")

            viewModel.createAccount()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(Res.string.account_validation_display_name_required, state.displayNameError)
            assertEquals(Res.string.account_validation_username_too_short, state.usernameError)
            assertFalse(state.isCreatingAccount)
            assertNull(passkeyRepo.lastCreationRequest)
        }

    // --- helpers -----------------------------------------------------------

    private fun buildViewModel(
        isEmailVerificationAvailable: Boolean,
        verifyOutcome: EmailVerificationOutcome = EmailVerificationOutcome.Failed("not_invoked"),
        passkeyRepo: FakePasskeyAccountRepository = FakePasskeyAccountRepository(),
        profileRepo: FakeProfileRepository = FakeProfileRepository(),
    ): CloudAccountOnboardingViewModel {
        val sessionStorage = FakeSessionStorage()
        val emailManager = FakeEmailVerificationManager()
        val verifyEmailUseCase = StubVerifyEmailUseCase(sessionStorage, emailManager, verifyOutcome)
        val emailAvailability = StubEmailVerificationAvailability(emailManager, isEmailVerificationAvailable)
        return CloudAccountOnboardingViewModel(
            createPasskeyAccountUseCase = CreatePasskeyAccountUseCase(passkeyRepo),
            checkUsernameAvailabilityUseCase = CheckUsernameAvailabilityUseCase(passkeyRepo),
            authenticateWithPasskeyUseCase = AuthenticateWithPasskeyUseCase(passkeyRepo),
            signInWithGoogleUseCase = SignInWithGoogleUseCase(passkeyRepo),
            triggerInitialSyncUseCase = TriggerInitialSyncUseCase(FakeSyncManager()),
            backfillLocalDataUseCase =
                BackfillLocalDataUseCase(
                    enqueueAllLocalData =
                        EnqueueAllLocalDataUseCase(
                            journalRepository = FakeJournalRepository(),
                            journalNotesRepository = FakeJournalNotesRepository(),
                            syncMetadataService = FakeSyncMetadataService(),
                        ),
                    tracker = FakeBackfilledAccountTracker(),
                ),
            passkeyManager = FakePasskeyManager(),
            verifyEmailUseCase = verifyEmailUseCase,
            emailVerificationAvailability = emailAvailability,
            profileRepository = profileRepo,
            syncManager = FakeSyncManager(),
            serverConfigurationCoordinator =
                ServerConfigurationCoordinator(
                    serverHealthChecker = FakeServerHealthChecker(),
                    serverDiscoveryClient = FakeServerDiscoveryClient(),
                    configRepository = DefaultLogDateConfigRepository(),
                ),
        )
    }

    // --- fakes -------------------------------------------------------------

    private class StubVerifyEmailUseCase(
        sessionStorage: SessionStorage,
        manager: EmailVerificationManager,
        private val outcome: EmailVerificationOutcome,
    ) : VerifyEmailUseCase(sessionStorage, manager) {
        override suspend operator fun invoke(): EmailVerificationOutcome = outcome
    }

    private class StubEmailVerificationAvailability(
        manager: EmailVerificationManager,
        private val available: Boolean,
    ) : EmailVerificationAvailability(
            manager,
            GetCurrentEntitlementUseCase(FakeSessionStorage(), FakePasskeyApiClient()),
        ) {
        override suspend fun isAvailable(): Boolean = available
    }

    private class FakeEmailVerificationManager : EmailVerificationManager {
        override val isSupported: Boolean = true

        override suspend fun verifyEmail(accessToken: String): EmailVerificationOutcome = EmailVerificationOutcome.Unsupported
    }

    private class FakeSessionStorage : SessionStorage {
        override fun getSession(): UserSession? = null

        override fun getSessionFlow(): StateFlow<UserSession?> = MutableStateFlow(null)

        override suspend fun hasValidSession(): Boolean = false

        override suspend fun saveSession(session: UserSession) {}

        override suspend fun clearSession() {}
    }

    private class FakePasskeyManager : PasskeyManager {
        override suspend fun getCapabilities(): PasskeyCapabilities =
            PasskeyCapabilities(isSupported = true, isPlatformAuthenticatorAvailable = true)

        override suspend fun isPlatformAuthenticatorAvailable(): Boolean = true

        override suspend fun registerPasskey(options: PasskeyRegistrationOptions): Result<String> = Result.failure(NotImplementedError())

        override suspend fun authenticateWithPasskey(options: PasskeyAuthenticationOptions): Result<String> =
            Result.failure(NotImplementedError())

        override fun getAvailabilityStatus(): Flow<PasskeyCapabilities> = emptyFlow()
    }

    private class FakePasskeyAccountRepository : PasskeyAccountRepository {
        var lastCreationRequest: AccountCreationRequest? = null
        override val currentAccount: StateFlow<LogDateAccount?> = MutableStateFlow(null)
        override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> {
            lastCreationRequest = request
            return Result.failure(NotImplementedError())
        }

        override suspend fun authenticateWithPasskey(
            username: String?,
            adoptLocalData: Boolean,
        ): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun checkUsernameAvailability(username: String): Result<Boolean> = Result.success(true)

        override suspend fun signOut(): Result<Unit> = Result.success(Unit)

        override suspend fun getCurrentAccount(): LogDateAccount? = null

        override suspend fun getAccountInfo(): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun refreshAuthentication(): Result<Unit> = Result.success(Unit)

        override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = Result.success(emptyList())

        override suspend fun deletePasskey(credentialId: String): Result<Unit> = Result.success(Unit)

        override suspend fun createRestoreKey(): Result<Unit> = Result.success(Unit)

        override suspend fun signInWithRestoreKey(): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun deleteRestoreKey(): Result<Unit> = Result.success(Unit)
    }

    private class FakeSyncManager : SyncManager {
        override val syncStatusFlow: StateFlow<SyncStatus> =
            MutableStateFlow(
                SyncStatus(
                    isEnabled = true,
                    lastSyncTime = null,
                    pendingUploads = 0,
                    isSyncing = false,
                    hasErrors = false,
                ),
            )

        override fun sync(startNow: Boolean) {}

        override suspend fun uploadPendingChanges(): SyncResult = SyncResult(success = true)

        override suspend fun downloadRemoteChanges(): SyncResult = SyncResult(success = true)

        override suspend fun syncContent(): SyncResult = SyncResult(success = true)

        override suspend fun syncJournals(): SyncResult = SyncResult(success = true)

        override suspend fun syncAssociations(): SyncResult = SyncResult(success = true)

        override suspend fun syncDrafts(): SyncResult = SyncResult(success = true)

        override suspend fun fullSync(): SyncResult = SyncResult(success = true)

        override suspend fun getSyncStatus(): SyncStatus = syncStatusFlow.value

        override fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>> = flowOf(emptyList())

        override suspend fun retryDeadLetter(id: String) {}

        override suspend fun discardDeadLetter(id: String) {}
    }

    private class FakeJournalRepository : JournalRepository {
        override val allJournalsObserved: Flow<List<Journal>> = MutableStateFlow<List<Journal>>(emptyList())

        override fun observeJournalById(id: Uuid): Flow<Journal> = flowOf(Journal(id = id))

        override suspend fun getJournalById(id: Uuid): Journal? = null

        override suspend fun create(journal: Journal): Uuid = journal.id

        override suspend fun update(journal: Journal) {}

        override suspend fun delete(journalId: Uuid) {}

        override suspend fun saveDraft(draft: EditorDraft) {}

        override suspend fun getLatestDraft(): EditorDraft? = null

        override suspend fun getAllDrafts(): List<EditorDraft> = emptyList()

        override suspend fun getDraft(id: Uuid): EditorDraft? = null

        override suspend fun deleteDraft(id: Uuid) {}
    }

    private class FakeJournalNotesRepository : JournalNotesRepository {
        override val allNotesObserved: Flow<List<JournalNote>> = MutableStateFlow<List<JournalNote>>(emptyList())

        override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

        override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()

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

        override suspend fun getNoteById(noteId: Uuid): JournalNote? = null

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
    }

    private class FakeSyncMetadataService : SyncMetadataService {
        override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> = emptyList()

        override suspend fun markAsSynced(
            entityId: String,
            entityType: EntityType,
            syncedAt: Instant,
            version: Long,
        ) {}

        override suspend fun getLastSyncTime(entityType: EntityType): Instant? = null

        override suspend fun updateLastSyncTime(
            entityType: EntityType,
            syncedAt: Instant,
        ) {}

        override suspend fun enqueuePending(
            entityId: String,
            entityType: EntityType,
            operation: PendingOperation,
        ) {}

        override suspend fun resetSyncStatus(
            entityId: String,
            entityType: EntityType,
        ) {}

        override suspend fun getPendingCount(): Int = 0

        override fun observePendingCount(): Flow<Int> = flowOf(0)

        override fun observePendingUploads(): Flow<List<QueuedUpload>> = flowOf(emptyList())

        override suspend fun incrementRetryCount(
            entityId: String,
            entityType: EntityType,
        ) {}

        override suspend fun clearPending() {}

        override suspend fun resetAllCursors() {}
    }

    private class FakeBackfilledAccountTracker : BackfilledAccountTracker {
        override suspend fun getBackfilledAccountIds(): Set<String> = emptySet()

        override suspend fun markAccountBackfilled(accountId: String) {}
    }

    private class FakeProfileRepository(
        private val profile: LogDateProfile = LogDateProfile(),
    ) : ProfileRepository {
        override val currentProfile: Flow<LogDateProfile> = flowOf(profile)

        override suspend fun updateDisplayName(displayName: String): Result<LogDateProfile> =
            Result.success(LogDateProfile(displayName = displayName))

        override suspend fun updateBirthday(birthday: Instant?): Result<LogDateProfile> = Result.success(LogDateProfile())

        override suspend fun updateProfilePhoto(profilePhotoUri: String?): Result<LogDateProfile> = Result.success(LogDateProfile())

        override suspend fun updateBio(
            bio: String?,
            originalBio: String?,
        ): Result<LogDateProfile> = Result.success(LogDateProfile())

        override suspend fun getCurrentProfile(): LogDateProfile = profile

        override suspend fun clearProfile(): Result<Unit> = Result.success(Unit)
    }

    private class FakeServerHealthChecker : ServerHealthChecker {
        override suspend fun checkServerHealth(baseUrl: String): Result<ServerHealthInfo> =
            Result.success(ServerHealthInfo(status = "ok", version = "test"))
    }

    private class FakeServerDiscoveryClient : ServerDiscoveryClient {
        override suspend fun discoverServer(serverOrigin: String): Result<ServerDescriptor> =
            Result.success(
                ServerDescriptor(
                    serverOrigin = serverOrigin,
                    apiBaseUrl = "$serverOrigin/api/v1",
                    deploymentKind = DeploymentKind.SELF_HOSTED,
                    displayName = "Test",
                ),
            )
    }

    private class FakePasskeyApiClient : PasskeyApiClientContract {
        override suspend fun checkUsernameAvailability(username: String): Result<UsernameAvailabilityData> =
            Result.failure(NotImplementedError())

        override suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
            Result.failure(NotImplementedError())

        override suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData> =
            Result.failure(NotImplementedError())

        override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
            Result.failure(NotImplementedError())

        override suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
            Result.failure(NotImplementedError())

        override suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun getEntitlement(accessToken: String): Result<EntitlementResponse> = Result.failure(NotImplementedError())

        override suspend fun updateAccountProfile(
            accessToken: String,
            displayName: String?,
            username: String?,
            bio: String?,
        ): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun refreshToken(refreshToken: String): Result<String> = Result.failure(NotImplementedError())

        override suspend fun listPasskeys(accessToken: String): Result<List<PasskeyInfo>> = Result.success(emptyList())

        override suspend fun deletePasskey(
            accessToken: String,
            credentialId: String,
        ): Result<Unit> = Result.failure(NotImplementedError())

        override suspend fun beginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions> =
            Result.failure(NotImplementedError())

        override suspend fun completeRestoreKeyRegistration(
            accessToken: String,
            credentialJson: String,
            challenge: String,
        ): Result<Unit> = Result.failure(NotImplementedError())

        override suspend fun beginRestoreSignIn(): Result<BeginAuthenticationData> = Result.failure(NotImplementedError())

        override suspend fun completeRestoreSignIn(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
            Result.failure(NotImplementedError())

        override suspend fun deleteAccount(accessToken: String): Result<Unit> = Result.failure(NotImplementedError())
    }
}
