package app.logdate.feature.core

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.domain.account.TryRestoreSignInUseCase
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.permissions.RestoreCredentialError
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.text.UiText
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.user.AppSecurityLevel
import app.logdate.shared.model.user.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Unit tests for [AppViewModel].
 *
 * Tests global application state management, including session restoration,
 * network availability monitoring, and onboarding state initialization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resuming the app retries pending account recovery without another restore callback`() =
        runTest {
            val repository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                )
            viewModel.tryRestoreSignInAfterCloudRestore(recoverMissingContent = false)
            advanceUntilIdle()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(2, repository.restoreSignInAttempts)
        }

    @Test
    fun `reconnecting retries a pending account recovery without recreating the activity`() =
        runTest {
            val network = FakeNetworkAvailabilityMonitor(true)
            val repository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    network,
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                )
            viewModel.tryRestoreSignInAfterCloudRestore(recoverMissingContent = false)
            advanceUntilIdle()
            network.setConnected(false)
            advanceUntilIdle()
            network.setConnected(true)
            advanceUntilIdle()
            assertEquals(2, repository.restoreSignInAttempts)
        }

    @Test
    fun `ui state becomes loaded with stable initial online network state`() =
        runTest {
            val viewModel =
                AppViewModel(
                    userStateRepository = FakeUserStateRepository(UserData(isOnboarded = true)),
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )

            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(true, state.isOnboarded)
            assertEquals(true, state.isOnline)
        }

    @Test
    fun `ui state becomes loaded with stable initial offline network state`() =
        runTest {
            val viewModel =
                AppViewModel(
                    userStateRepository = FakeUserStateRepository(UserData(isOnboarded = false)),
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = false),
                    sessionStorage = FakeSessionStorage(),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )

            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(false, state.isOnboarded)
            assertEquals(false, state.isOnline)
        }

    @Test
    fun `does not attempt restore sign-in during init`() =
        runTest {
            val passkeyAccountRepository = FakePasskeyAccountRepository()
            AppViewModel(
                userStateRepository = FakeUserStateRepository(UserData(isOnboarded = true)),
                biometricGatekeeper = FakeBiometricGatekeeper(),
                networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                sessionStorage = FakeSessionStorage(),
                tryRestoreSignInUseCase = TryRestoreSignInUseCase(passkeyAccountRepository),
            )

            advanceUntilIdle()

            assertEquals(0, passkeyAccountRepository.restoreSignInAttempts)
        }

    @Test
    fun `cloud restore sign-in skips when a valid session exists`() =
        runTest {
            val passkeyAccountRepository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    userStateRepository = FakeUserStateRepository(UserData(isOnboarded = true)),
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(hasValidSession = true),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(passkeyAccountRepository),
                )

            viewModel.tryRestoreSignInAfterCloudRestore()
            advanceUntilIdle()

            assertEquals(0, passkeyAccountRepository.restoreSignInAttempts)
        }

    @Test
    fun `successful account recovery does not import over an intact transferred journal`() =
        runTest {
            var imports = 0
            var resolutions = 0
            val repository =
                FakePasskeyAccountRepository(Result.success(LogDateAccount(username = "fixture", displayName = "Fixture account")))
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                    onCloudRestoreSignIn = { imports++ },
                )

            viewModel.tryRestoreSignInAfterCloudRestore(recoverMissingContent = false, onAccountResolved = { resolutions++ })
            advanceUntilIdle()

            assertEquals(1, repository.restoreSignInAttempts)
            assertEquals(1, resolutions)
            assertEquals(0, imports)

            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(1, repository.restoreSignInAttempts)
            assertEquals(1, resolutions)
            assertEquals(0, imports)
        }

    @Test
    fun `existing account access does not import over an intact transferred journal`() =
        runTest {
            var imports = 0
            var resolutions = 0
            val repository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(hasValidSession = true),
                    TryRestoreSignInUseCase(repository),
                    onCloudRestoreSignIn = { imports++ },
                )

            viewModel.tryRestoreSignInAfterCloudRestore(recoverMissingContent = false, onAccountResolved = { resolutions++ })
            advanceUntilIdle()

            assertEquals(0, repository.restoreSignInAttempts)
            assertEquals(1, resolutions)
            assertEquals(0, imports)

            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, repository.restoreSignInAttempts)
            assertEquals(1, resolutions)
            assertEquals(0, imports)
        }

    @Test
    fun `missing content recovery resumes when account access already exists`() =
        runTest {
            var imports = 0
            var resolved = false
            val repository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(hasValidSession = true),
                    TryRestoreSignInUseCase(repository),
                    onCloudRestoreSignIn = { imports++ },
                )

            viewModel.tryRestoreSignInAfterCloudRestore(recoverMissingContent = true, onAccountResolved = { resolved = true })
            advanceUntilIdle()

            assertEquals(true, resolved)
            assertEquals(1, imports)
            assertEquals(0, repository.restoreSignInAttempts)
        }

    @Test
    fun `app starts locked when biometric security is already enabled`() =
        runTest {
            val viewModel =
                AppViewModel(
                    userStateRepository =
                        FakeUserStateRepository(
                            UserData(isOnboarded = true, securityLevel = AppSecurityLevel.BIOMETRIC),
                        ),
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )

            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(true, state.requiresUnlock)
        }

    @Test
    fun `restored app lock protects the journal when destination authentication is unavailable`() =
        runTest {
            listOf(
                AppAuthState.REQUEST_ENROLLMENT,
                AppAuthState.UNSUPPORTED,
                AppAuthState.UPDATE_REQUIRED,
                AppAuthState.UNKNOWN,
            ).forEach { authState ->
                val viewModel = lockedViewModel(FakeBiometricGatekeeper(authState))
                advanceUntilIdle()
                val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
                assertEquals(authState, state.authState)
                assertEquals(true, state.requiresUnlock, authState.name)
            }
        }

    @Test
    fun `returning from enrollment rechecks authentication without reopening canceled setup`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper(AppAuthState.REQUEST_ENROLLMENT)
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()

            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(listOf(false), gatekeeper.enrollmentRequests)
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `backgrounding requires a new challenge after previously successful authentication`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)

            viewModel.onAppBackgrounded()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)

            gatekeeper.setState(AppAuthState.REQUIRE_PROMPT)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `a new app session never reuses the prior session biometric success`() =
        runTest {
            val viewModel = lockedViewModel(FakeBiometricGatekeeper(AppAuthState.AUTHENTICATED))
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `a success delivered after backgrounding cannot unlock the next foreground session`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            gatekeeper.setState(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `a queued background auth state cannot unlock after the foreground callback`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            gatekeeper.setState(AppAuthState.AUTHENTICATED)
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `an unrelated security check cannot unlock the journal challenge`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            gatekeeper.setState(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `a successful callback queued before backgrounding cannot unlock after return`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            viewModel.onAppBackgrounded()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `an overlapping journal challenge preserves the current unlock request`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = lockedViewModel(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.completeRequest(0, AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(1, gatekeeper.callbacks.size)
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    private fun lockedViewModel(gatekeeper: BiometricGatekeeper): AppViewModel =
        AppViewModel(
            userStateRepository =
                FakeUserStateRepository(
                    UserData(isOnboarded = true, securityLevel = AppSecurityLevel.BIOMETRIC),
                ),
            biometricGatekeeper = gatekeeper,
            networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
            sessionStorage = FakeSessionStorage(),
            tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
        )

    @Test
    fun `enabling biometric mid-session does not lock the app`() =
        runTest {
            val userRepo =
                FakeUserStateRepository(
                    UserData(isOnboarded = true, securityLevel = AppSecurityLevel.NONE),
                )
            val viewModel =
                AppViewModel(
                    userStateRepository = userRepo,
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )
            advanceUntilIdle()

            userRepo.setUserData(
                UserData(isOnboarded = true, securityLevel = AppSecurityLevel.BIOMETRIC),
            )
            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(false, state.requiresUnlock)
        }

    @Test
    fun `disabling biometric unlocks the app`() =
        runTest {
            val userRepo =
                FakeUserStateRepository(
                    UserData(isOnboarded = true, securityLevel = AppSecurityLevel.BIOMETRIC),
                )
            val viewModel =
                AppViewModel(
                    userStateRepository = userRepo,
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )
            advanceUntilIdle()

            userRepo.setUserData(
                UserData(isOnboarded = true, securityLevel = AppSecurityLevel.NONE),
            )
            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(false, state.requiresUnlock)
        }

    @Test
    fun `cloud restore sign-in attempts once when launched repeatedly`() =
        runTest {
            val passkeyAccountRepository = FakePasskeyAccountRepository()
            val viewModel =
                AppViewModel(
                    userStateRepository = FakeUserStateRepository(UserData(isOnboarded = true)),
                    biometricGatekeeper = FakeBiometricGatekeeper(),
                    networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
                    sessionStorage = FakeSessionStorage(hasValidSession = false),
                    tryRestoreSignInUseCase = TryRestoreSignInUseCase(passkeyAccountRepository),
                )

            viewModel.tryRestoreSignInAfterCloudRestore()
            viewModel.tryRestoreSignInAfterCloudRestore()
            advanceUntilIdle()

            assertEquals(1, passkeyAccountRepository.restoreSignInAttempts)
        }

    @Test
    fun `credential-only restore can recover an account before onboarding data arrives`() =
        runTest {
            val repository =
                FakePasskeyAccountRepository(Result.success(LogDateAccount(username = "fixture", displayName = "Fixture account")))
            var resolved = false
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = false)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                )

            viewModel.tryRestoreSignInAfterCloudRestore(onAccountResolved = { resolved = true })
            advanceUntilIdle()

            assertEquals(1, repository.restoreSignInAttempts)
            assertEquals(true, resolved)
        }

    @Test
    fun `an absent credential stays pending for delayed native delivery`() =
        runTest {
            val repository = FakePasskeyAccountRepository()
            var resolved = false
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = false)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                )

            viewModel.tryRestoreSignInAfterCloudRestore(onAccountResolved = { resolved = true })
            advanceUntilIdle()
            viewModel.tryRestoreSignInAfterCloudRestore(onAccountResolved = { resolved = true })
            advanceUntilIdle()

            assertEquals(false, resolved)
            assertEquals(2, repository.restoreSignInAttempts)
        }

    @Test
    fun `an account recovery error remains retryable in the same application process`() =
        runTest {
            val repository = FakePasskeyAccountRepository(Result.failure(IllegalStateException("Server unavailable")))
            var resolved = false
            val viewModel =
                AppViewModel(
                    FakeUserStateRepository(UserData(isOnboarded = true)),
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(repository),
                )

            viewModel.tryRestoreSignInAfterCloudRestore(onAccountResolved = { resolved = true })
            advanceUntilIdle()
            viewModel.tryRestoreSignInAfterCloudRestore(onAccountResolved = { resolved = true })
            advanceUntilIdle()

            assertEquals(2, repository.restoreSignInAttempts)
            assertEquals(false, resolved)
        }

    @Test
    fun `failed sensitive challenge preserves the lock after the app backgrounds`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            var requestResult: AppAuthState? = null
            gatekeeper.complete(AppAuthState.UNKNOWN) { requestResult = it }
            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(AppAuthState.UNKNOWN, state.authState)
            assertEquals(true, state.requiresUnlock)
            assertEquals(AppAuthState.UNKNOWN, requestResult)
        }

    @Test
    fun `failed sensitive challenge preserves an already unlocked session`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            var requestResult: AppAuthState? = null
            gatekeeper.complete(AppAuthState.UNKNOWN) { requestResult = it }
            advanceUntilIdle()

            val state = assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value)
            assertEquals(AppAuthState.NO_PROMPT_NEEDED, state.authState)
            assertEquals(false, state.requiresUnlock)
            assertEquals(AppAuthState.UNKNOWN, requestResult)
        }

    @Test
    fun `background locks despite the retained successful provider result`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)

            viewModel.onAppBackgrounded()
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `each fresh unlock callback works even when provider success has not changed`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            repeat(3) {
                viewModel.onAppForegrounded()
                viewModel.showNativeUnlockPrompt()
                gatekeeper.complete(AppAuthState.AUTHENTICATED)
                advanceUntilIdle()
                assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
                viewModel.onAppBackgrounded()
                advanceUntilIdle()
                assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
            }
        }

    @Test
    fun `provider capability outcomes cannot bypass the app lock`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            for (outcome in AppAuthState.entries.filter { it != AppAuthState.AUTHENTICATED }) {
                viewModel.showNativeUnlockPrompt()
                gatekeeper.publish(outcome)
                gatekeeper.complete(outcome)
                advanceUntilIdle()
                assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock, outcome.name)
            }
        }

    @Test
    fun `a successful sensitive action cannot unlock the application`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()

            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `duplicate unlock taps use a single request and completed callbacks cannot unlock again`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.showNativeUnlockPrompt()
            assertEquals(1, gatekeeper.callbacks.size)
            val oldCallback = gatekeeper.callbacks.single()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            oldCallback(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `successful device credential waits for foreground return`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.onAppPaused()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)

            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `another background invalidates a deferred successful unlock`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.onAppPaused()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `an active challenge is retired by authoritative backgrounding`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.onAppBackgrounded()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `a second pause retires an unfinished native handoff`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            viewModel.onAppPaused()
            viewModel.onAppForegrounded()
            viewModel.onAppPaused()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `backgrounded app cannot start an unlock prompt`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            viewModel.showNativeUnlockPrompt()

            assertEquals(0, gatekeeper.callbacks.size)
            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `duplicate terminal results cannot revoke a deferred native success`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showNativeUnlockPrompt()
            val callback = gatekeeper.callbacks.single()
            viewModel.onAppPaused()
            callback(AppAuthState.AUTHENTICATED)
            advanceUntilIdle()
            callback(AppAuthState.UNKNOWN)
            advanceUntilIdle()
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(false, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `enabling protection while backgrounded requires authentication on return`() =
        runTest {
            val userRepo = FakeUserStateRepository(UserData(isOnboarded = true))
            val viewModel =
                AppViewModel(
                    userRepo,
                    FakeBiometricGatekeeper(),
                    FakeNetworkAvailabilityMonitor(true),
                    FakeSessionStorage(),
                    TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
                )
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            userRepo.setBiometricEnabled(true)
            advanceUntilIdle()
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(true, assertIs<GlobalAppUiLoadedState>(viewModel.uiState.value).requiresUnlock)
        }

    @Test
    fun `automatic unlock attempts once per foreground while manual retry stays available`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.showAutomaticUnlockPrompt()
            gatekeeper.complete(AppAuthState.REQUIRE_PROMPT)
            advanceUntilIdle()
            viewModel.showAutomaticUnlockPrompt()
            assertEquals(1, gatekeeper.callbacks.size)

            viewModel.showNativeUnlockPrompt()
            assertEquals(2, gatekeeper.callbacks.size)
        }

    @Test
    fun `immediate background after unlock starts a new automatic challenge on return`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            repeat(3) {
                viewModel.showAutomaticUnlockPrompt()
                gatekeeper.complete(AppAuthState.AUTHENTICATED)
                advanceUntilIdle()
                viewModel.onAppBackgrounded()
                viewModel.onAppForegrounded()
            }
            assertEquals(3, gatekeeper.callbacks.size)
        }

    @Test
    fun `inactive automatic request does not consume the next foreground attempt`() =
        runTest {
            val gatekeeper = FakeBiometricGatekeeper()
            val viewModel = biometricApp(gatekeeper)
            advanceUntilIdle()
            viewModel.onAppBackgrounded()
            viewModel.showAutomaticUnlockPrompt()
            assertEquals(0, gatekeeper.callbacks.size)
            viewModel.onAppForegrounded()
            viewModel.showAutomaticUnlockPrompt()
            assertEquals(1, gatekeeper.callbacks.size)
        }

    private fun biometricApp(gatekeeper: FakeBiometricGatekeeper) =
        AppViewModel(
            userStateRepository = FakeUserStateRepository(UserData(isOnboarded = true, securityLevel = AppSecurityLevel.BIOMETRIC)),
            biometricGatekeeper = gatekeeper,
            networkMonitor = FakeNetworkAvailabilityMonitor(isAvailable = true),
            sessionStorage = FakeSessionStorage(),
            tryRestoreSignInUseCase = TryRestoreSignInUseCase(FakePasskeyAccountRepository()),
        )

    private class FakeUserStateRepository(
        initialValue: UserData,
    ) : UserStateRepository {
        private val _userData = MutableStateFlow(initialValue)
        override val userData: StateFlow<UserData> = _userData

        fun setUserData(value: UserData) {
            _userData.value = value
        }

        override suspend fun setBirthday(birthday: Instant) {}

        override suspend fun setIsOnboardingComplete(isComplete: Boolean) {}

        override suspend fun setBiometricEnabled(isEnabled: Boolean) {
            val level = if (isEnabled) AppSecurityLevel.BIOMETRIC else AppSecurityLevel.NONE
            _userData.value = _userData.value.copy(securityLevel = level)
        }

        override suspend fun addFavoriteNote(vararg noteId: String) {}
    }

    private class FakeBiometricGatekeeper(
        initialState: AppAuthState = AppAuthState.NO_PROMPT_NEEDED,
    ) : BiometricGatekeeper {
        private val state = MutableStateFlow(initialState)
        override val authState: StateFlow<AppAuthState> = state

        val enrollmentRequests = mutableListOf<Boolean>()
        val callbacks = mutableListOf<(AppAuthState) -> Unit>()
        private var pending: ((AppAuthState) -> Unit)? = null

        fun setState(value: AppAuthState) = publish(value)

        fun completeRequest(index: Int, value: AppAuthState) {
            completeAuthenticationChallenge(state, value, callbacks[index])
        }

        fun publish(result: AppAuthState) {
            state.value = result
        }

        fun complete(
            result: AppAuthState,
            onResult: (AppAuthState) -> Unit = {},
        ) {
            val callback = pending
            pending = null
            completeAuthenticationChallenge(state, result) {
                callback?.invoke(it)
                onResult(it)
            }
        }

        override fun authenticate(
            title: UiText,
            subtitle: UiText,
            cancelLabel: UiText,
            requireConfirmation: Boolean,
            requestEnrollmentIfNecessary: Boolean,
            description: UiText?,
            onResult: (AppAuthState) -> Unit,
        ) {
            enrollmentRequests += requestEnrollmentIfNecessary
            callbacks += onResult
            pending = onResult
        }

        override fun requestEnrollment() {
        }
    }

    private class FakeNetworkAvailabilityMonitor(
        private val isAvailable: Boolean,
    ) : NetworkAvailabilityMonitor {
        private val networkState =
            MutableStateFlow<NetworkState>(
                if (isAvailable) {
                    NetworkState.Connected(Clock.System.now())
                } else {
                    NetworkState.NotConnected(Clock.System.now())
                },
            )

        fun setConnected(connected: Boolean) {
            networkState.value =
                if (connected) NetworkState.Connected(Clock.System.now()) else NetworkState.NotConnected(Clock.System.now())
        }

        override fun isNetworkAvailable(): Boolean = networkState.value is NetworkState.Connected

        override fun observeNetwork(): SharedFlow<NetworkState> = networkState
    }

    private class FakeSessionStorage(
        private val session: UserSession? = null,
        private val hasValidSession: Boolean = false,
    ) : SessionStorage {
        override fun getSession(): UserSession? = session

        override fun getSessionFlow(): StateFlow<UserSession?> = MutableStateFlow(session)

        override suspend fun hasValidSession(): Boolean = hasValidSession

        override suspend fun saveSession(session: UserSession) {}

        override suspend fun clearSession() {}
    }

    private class FakePasskeyAccountRepository(
        private val signInWithRestoreKeyResult: Result<LogDateAccount> = Result.failure(RestoreCredentialError.NoCredential()),
    ) : PasskeyAccountRepository {
        override val currentAccount: StateFlow<LogDateAccount?> = MutableStateFlow(null)
        override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(false)
        var restoreSignInAttempts: Int = 0
            private set

        override suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> =
            Result.failure(NotImplementedError())

        override suspend fun authenticateWithPasskey(
            username: String?,
            adoptLocalData: Boolean,
        ): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun checkUsernameAvailability(username: String): Result<Boolean> = Result.success(true)

        override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = Result.success(emptyList())

        override suspend fun signOut(): Result<Unit> = Result.success(Unit)

        override suspend fun getCurrentAccount(): LogDateAccount? = null

        override suspend fun getAccountInfo(): Result<LogDateAccount> = Result.failure(NotImplementedError())

        override suspend fun refreshAuthentication(): Result<Unit> = Result.success(Unit)

        override suspend fun deletePasskey(credentialId: String): Result<Unit> = Result.success(Unit)

        override suspend fun createRestoreKey(): Result<Unit> = Result.success(Unit)

        override suspend fun signInWithRestoreKey(): Result<LogDateAccount> {
            restoreSignInAttempts += 1
            return signInWithRestoreKeyResult
        }

        override suspend fun deleteRestoreKey(): Result<Unit> = Result.success(Unit)
    }
}
