package app.logdate.feature.core.settings.ui

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.shared.model.user.AppSecurityLevel
import app.logdate.shared.model.user.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val prompt = PrivacyAuthenticationPrompt("Localized title", "Localized subtitle", "Localized cancel", "Localized description")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preferences do not invent disabled values before the first persisted snapshot`() =
        runTest {
            val store = PrivacyPreferencesDataStore(readGate = CompletableDeferred())
            val repository = FakeUserStateRepository(readGate = CompletableDeferred())
            val model = buildViewModel(store = store, userStateRepository = repository)

            assertIs<PrivacyPreferenceState.Loading>(model.state.value.biometrics)
            assertIs<PrivacyPreferenceState.Loading>(model.state.value.systemSearchVisibility)
            model.setBiometricEnabled(true, prompt)
            model.setSystemSearchVisibilityEnabled(false)
            assertTrue(repository.writes.isEmpty())
            assertEquals(0, store.writes)
        }

    @Test
    fun `a delayed search preference does not hide an available biometric setting`() =
        runTest {
            val store = PrivacyPreferencesDataStore(readGate = CompletableDeferred())
            val model = buildViewModel(store = store)

            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            assertIs<PrivacyPreferenceState.Loading>(model.state.value.systemSearchVisibility)
            store.readGate?.complete(Unit)
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
        }

    @Test
    fun `enabling biometric persists only when authentication succeeds`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper()
            val repository = FakeUserStateRepository()
            val model = buildViewModel(gatekeeper, repository)

            model.setBiometricEnabled(true, prompt)

            assertEquals(listOf(true), repository.writes)
            assertEquals(
                listOf(prompt.title.toUiText(), prompt.subtitle.toUiText(), prompt.cancelLabel.toUiText(), prompt.description.toUiText()),
                gatekeeper.lastPrompt,
            )
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.biometrics
                    .operation(),
            )
        }

    @Test
    fun `cancelled authentication returns quietly to the prior value`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper(resultToReport = AppAuthState.REQUIRE_PROMPT)
            val repository = FakeUserStateRepository()
            val model = buildViewModel(gatekeeper, repository)

            model.setBiometricEnabled(true, prompt)

            assertTrue(repository.writes.isEmpty())
            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.biometrics
                    .operation(),
            )
        }

    @Test
    fun `disabling biometric persists immediately and skips the gatekeeper`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper()
            val repository = FakeUserStateRepository(initialUserData = UserData(securityLevel = AppSecurityLevel.BIOMETRIC))
            val model = buildViewModel(gatekeeper, repository)

            model.setBiometricEnabled(false, prompt)

            assertEquals(0, gatekeeper.authenticateInvocations)
            assertEquals(listOf(false), repository.writes)
        }

    @Test
    fun `repeated enable requests share one authentication challenge`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper(respondImmediately = false)
            val model = buildViewModel(gatekeeper)

            model.setBiometricEnabled(true, prompt)
            model.setBiometricEnabled(true, prompt)

            assertEquals(1, gatekeeper.authenticateInvocations)
            assertEquals(
                PrivacyPreferenceOperation.Authenticating,
                model.state.value.biometrics
                    .operation(),
            )
        }

    @Test
    fun `a departed authentication request cannot persist a late success`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper(respondImmediately = false)
            val repository = FakeUserStateRepository()
            val model = buildViewModel(gatekeeper, repository)
            model.setBiometricEnabled(true, prompt)

            model.cancelAuthentication()
            gatekeeper.complete(AppAuthState.AUTHENTICATED)

            assertTrue(repository.writes.isEmpty())
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.biometrics
                    .operation(),
            )
        }

    @Test
    fun `an old challenge cannot complete a newer authentication attempt`() =
        runTest {
            val gatekeeper = ScriptedBiometricGatekeeper(respondImmediately = false)
            val repository = FakeUserStateRepository()
            val model = buildViewModel(gatekeeper, repository)
            model.setBiometricEnabled(true, prompt)
            model.cancelAuthentication()
            model.setBiometricEnabled(true, prompt)

            gatekeeper.complete(AppAuthState.AUTHENTICATED, index = 0)
            assertTrue(repository.writes.isEmpty())
            assertEquals(
                PrivacyPreferenceOperation.Authenticating,
                model.state.value.biometrics
                    .operation(),
            )
            gatekeeper.complete(AppAuthState.AUTHENTICATED, index = 1)
            gatekeeper.complete(AppAuthState.AUTHENTICATED, index = 1)
            assertEquals(listOf(true), repository.writes)
        }

    @Test
    fun `failed biometric persistence retains its value and clears only its error on retry`() =
        runTest {
            val repository = FakeUserStateRepository(initialUserData = UserData(securityLevel = AppSecurityLevel.BIOMETRIC))
            repository.beforeWrite = { error("private provider detail") }
            val model = buildViewModel(userStateRepository = repository)
            model.setBiometricEnabled(false, prompt)

            val failed =
                assertIs<PrivacyPreferenceOperation.Failed>(
                    model.state.value.biometrics
                        .operation(),
                )
            assertEquals(PrivacyPreferenceFailure.SAVE, failed.reason)
            assertFalse(failed.targetEnabled)
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.systemSearchVisibility
                    .operation(),
            )

            val gate = CompletableDeferred<Unit>()
            repository.beforeWrite = { gate.await() }
            model.retryBiometrics(prompt)
            assertEquals(
                PrivacyPreferenceOperation.Saving(false),
                model.state.value.biometrics
                    .operation(),
            )
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            gate.complete(Unit)
            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.biometrics
                    .operation(),
            )
        }

    @Test
    fun `search writes coalesce while retaining the authoritative value and allowing biometric changes`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = PrivacyPreferencesDataStore().apply { beforeWrite = { gate.await() } }
            val repository = FakeUserStateRepository()
            val model = buildViewModel(store = store, userStateRepository = repository)

            model.setSystemSearchVisibilityEnabled(false)
            model.setSystemSearchVisibilityEnabled(false)
            model.setBiometricEnabled(true, prompt)

            assertEquals(1, store.writes)
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Saving(false),
                model.state.value.systemSearchVisibility
                    .operation(),
            )
            assertEquals(listOf(true), repository.writes)
            gate.complete(Unit)
            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
        }

    @Test
    fun `failed search persistence retries the failed target and clears the old error immediately`() =
        runTest {
            val store = PrivacyPreferencesDataStore().apply { beforeWrite = { error("private detail") } }
            val model = buildViewModel(store = store)
            model.setSystemSearchVisibilityEnabled(false)
            assertEquals(
                PrivacyPreferenceOperation.Failed(false, PrivacyPreferenceFailure.SAVE),
                model.state.value.systemSearchVisibility
                    .operation(),
            )

            val gate = CompletableDeferred<Unit>()
            store.beforeWrite = { gate.await() }
            model.retrySystemSearchVisibility()
            assertEquals(
                PrivacyPreferenceOperation.Saving(false),
                model.state.value.systemSearchVisibility
                    .operation(),
            )
            gate.complete(Unit)
            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.systemSearchVisibility
                    .operation(),
            )
        }

    @Test
    fun `cancelled persistence does not become a save error`() =
        runTest {
            val store = PrivacyPreferencesDataStore().apply { beforeWrite = { throw CancellationException("cancelled") } }
            val model = buildViewModel(store = store)

            model.setSystemSearchVisibilityEnabled(false)

            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.systemSearchVisibility
                    .operation(),
            )
        }

    @Test
    fun `failed initial preference observation has a scoped retry`() =
        runTest {
            val store = PrivacyPreferencesDataStore().apply { readFailure = true }
            val model = buildViewModel(store = store)
            assertIs<PrivacyPreferenceState.Unavailable>(model.state.value.systemSearchVisibility)
            assertIs<PrivacyPreferenceState.Available>(model.state.value.biometrics)

            store.readFailure = false
            model.retrySystemSearchVisibility()

            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
            assertEquals(2, store.reads)
        }

    @Test
    fun `a failed live preference observation retains its authoritative value during retry`() =
        runTest {
            val store = PrivacyPreferencesDataStore()
            val model = buildViewModel(store = store)

            store.failObservation()
            val failed = assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility)
            assertTrue(failed.enabled)
            assertTrue(failed.refreshFailed)

            val gate = CompletableDeferred<Unit>()
            store.nextReadGate = gate
            model.retrySystemSearchVisibility()
            val retrying = assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility)
            assertTrue(retrying.enabled)
            assertTrue(retrying.isRefreshing)
            assertFalse(retrying.refreshFailed)
            gate.complete(Unit)
            assertFalse(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).isRefreshing)
        }

    @Test
    fun `authentication capability failures stay on the biometric operation`() =
        runTest {
            val model = buildViewModel(ScriptedBiometricGatekeeper(AppAuthState.UNSUPPORTED))

            model.setBiometricEnabled(true, prompt)

            assertEquals(
                PrivacyPreferenceOperation.Failed(true, PrivacyPreferenceFailure.AUTHENTICATION_UNAVAILABLE),
                model.state.value.biometrics
                    .operation(),
            )
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.systemSearchVisibility
                    .operation(),
            )
        }

    @Test
    fun `a confirmed write reconciles its value after observation has failed`() =
        runTest {
            val store = PrivacyPreferencesDataStore()
            val model = buildViewModel(store = store)
            store.failObservation()
            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).refreshFailed)

            model.setSystemSearchVisibilityEnabled(false)

            val updated = assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility)
            assertFalse(updated.enabled)
            assertTrue(updated.refreshFailed)
            assertEquals(PrivacyPreferenceOperation.Idle, updated.operation)
        }

    @Test
    fun `write completion does not replace a newer observed preference value`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = PrivacyPreferencesDataStore().apply { afterWrite = { gate.await() } }
            val model = buildViewModel(store = store)
            model.setSystemSearchVisibilityEnabled(false)
            store.publishSearchVisibility(true)

            gate.complete(Unit)

            assertTrue(assertIs<PrivacyPreferenceState.Available>(model.state.value.systemSearchVisibility).enabled)
            assertEquals(
                PrivacyPreferenceOperation.Idle,
                model.state.value.systemSearchVisibility
                    .operation(),
            )
        }

    private fun buildViewModel(
        gatekeeper: BiometricGatekeeper = ScriptedBiometricGatekeeper(),
        userStateRepository: UserStateRepository = FakeUserStateRepository(),
        store: PrivacyPreferencesDataStore = PrivacyPreferencesDataStore(),
    ): PrivacySettingsViewModel =
        PrivacySettingsViewModel(
            preferencesDataSource = LogdatePreferencesDataSource(store),
            userStateRepository = userStateRepository,
            biometricGatekeeper = gatekeeper,
            supportsSystemSearchVisibilityToggle = true,
        )

    private fun PrivacyPreferenceState.operation(): PrivacyPreferenceOperation = assertIs<PrivacyPreferenceState.Available>(this).operation

    private class ScriptedBiometricGatekeeper(
        private val resultToReport: AppAuthState = AppAuthState.AUTHENTICATED,
        private val respondImmediately: Boolean = true,
    ) : BiometricGatekeeper {
        override val authState: StateFlow<AppAuthState> = MutableStateFlow(AppAuthState.NO_PROMPT_NEEDED)
        var authenticateInvocations = 0
        var lastPrompt: List<UiText?>? = null
        private val pendingResults = mutableListOf<(AppAuthState) -> Unit>()

        override fun authenticate(
            title: UiText,
            subtitle: UiText,
            cancelLabel: UiText,
            requireConfirmation: Boolean,
            requestEnrollmentIfNecessary: Boolean,
            description: UiText?,
            onResult: (AppAuthState) -> Unit,
        ) {
            authenticateInvocations++
            lastPrompt = listOf(title, subtitle, cancelLabel, description)
            pendingResults += onResult
            if (respondImmediately) onResult(resultToReport)
        }

        fun complete(
            result: AppAuthState,
            index: Int = pendingResults.lastIndex,
        ) {
            pendingResults[index](result)
        }

        override fun requestEnrollment() {}
    }

    private class FakeUserStateRepository(
        initialUserData: UserData = UserData(),
        private val readGate: CompletableDeferred<Unit>? = null,
    ) : UserStateRepository {
        private val mutableUserData = MutableStateFlow(initialUserData)
        override val userData: Flow<UserData> =
            flow {
                readGate?.await()
                emitAll(mutableUserData)
            }
        val writes = mutableListOf<Boolean>()
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun setBirthday(birthday: Instant) {}

        override suspend fun setIsOnboardingComplete(isComplete: Boolean) {}

        override suspend fun setBiometricEnabled(isEnabled: Boolean) {
            writes += isEnabled
            beforeWrite()
            mutableUserData.update {
                it.copy(securityLevel = if (isEnabled) AppSecurityLevel.BIOMETRIC else AppSecurityLevel.NONE)
            }
        }

        override suspend fun addFavoriteNote(vararg noteId: String) {}
    }
}
