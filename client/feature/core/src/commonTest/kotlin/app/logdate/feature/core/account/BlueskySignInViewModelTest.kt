package app.logdate.feature.core.account

import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyCapabilities
import app.logdate.shared.model.PasskeyRegistrationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BlueskySignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BlueskySignInViewModel>()

    @BeforeTest fun prepare() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `leaving a completed flow clears its transient account before navigation and cannot replay sync`() =
        runTest {
            val f = Fixture()
            var syncs = 0
            val vm =
                f.model(finish = {
                    syncs++
                    InitialSyncStatus.Success
                })
            runCurrent()
            var exits = 0
            vm.leaveCompletedFlow { exits++ }
            runCurrent()
            assertEquals(0, exits)
            f.repo.state.value = BlueskySignInState.Complete(LogDateAccount(username = "alice", displayName = "Alice"), ORIGIN)
            runCurrent()
            assertTrue(vm.uiState.value.finished)
            val leave: () -> Unit = {
                assertEquals(BlueskySignInState.Idle, f.repo.state.value)
                exits++
            }
            vm.leaveCompletedFlow(leave)
            vm.leaveCompletedFlow(leave)
            runCurrent()
            assertEquals(1, exits)
            f.model(finish = {
                syncs++
                InitialSyncStatus.Success
            })
            runCurrent()
            assertEquals(1, syncs)
        }

    @Test
    fun `start opens the browser only through the available shared flow`() =
        runTest {
            val f = Fixture()
            val vm = f.model()
            runCurrent()
            assertTrue(vm.uiState.value.available)
            vm.updateHandle(" @alice.test ")
            vm.start()
            runCurrent()
            assertEquals("alice.test", f.repo.startedHandle)
            assertEquals("https://bsky.example/authorize", vm.uiState.value.browserUrl)
            vm.browserOpened()
            assertNull(vm.uiState.value.browserUrl)
            assertEquals(0, f.repo.passkeyCalls)
        }

    @Test
    fun `saved registration details win over a late local profile and cannot be edited`() =
        runTest {
            val f = Fixture()
            val profile = CompletableDeferred<AccountCreationRequest>()
            val vm = f.model(profile = { profile.await() })
            runCurrent()
            val saved = AccountCreationRequest("saved", "Saved Name", "saved bio")
            f.repo.state.value = BlueskySignInState.AccountDetails(saved)
            runCurrent()
            profile.complete(AccountCreationRequest("local", "Local Name"))
            runCurrent()
            vm.updateDetails("changed", "Changed")
            assertEquals(saved, vm.uiState.value.details)
            assertTrue(vm.uiState.value.detailsLocked)
            vm.createAccount()
            runCurrent()
            assertEquals(saved, f.repo.creation)
        }

    @Test
    fun `successful authentication waits for actual initial sync and finishes only once`() =
        runTest {
            val f = Fixture()
            val synced = CompletableDeferred<InitialSyncStatus>()
            var runs = 0
            val vm =
                f.model(finish = {
                    runs++
                    synced.await()
                })
            runCurrent()
            val account = LogDateAccount(username = "alice", displayName = "Alice")
            f.repo.state.value = BlueskySignInState.Complete(account, ORIGIN)
            runCurrent()
            assertEquals(InitialSyncStatus.Running, vm.uiState.value.initialSync)
            assertFalse(vm.uiState.value.finished)
            synced.complete(InitialSyncStatus.Partial)
            runCurrent()
            assertTrue(vm.uiState.value.finished)
            assertEquals(InitialSyncStatus.Partial, vm.uiState.value.initialSync)
            f.repo.state.value = BlueskySignInState.Working
            runCurrent()
            f.repo.state.value = BlueskySignInState.Complete(account, ORIGIN)
            runCurrent()
            assertEquals(1, runs)
        }

    @Test
    fun `a backend change during sync cannot finish the account from the old server`() =
        runTest {
            val f = Fixture()
            val synced = CompletableDeferred<InitialSyncStatus>()
            val vm = f.model(finish = { synced.await() })
            runCurrent()
            f.repo.state.value = BlueskySignInState.Complete(LogDateAccount(username = "alice", displayName = "Alice"), ORIGIN)
            runCurrent()
            f.config.updateBackendUrl("https://other.logdate.app")
            runCurrent()
            synced.complete(InitialSyncStatus.Success)
            runCurrent()
            assertFalse(vm.uiState.value.finished)
            assertTrue(f.repo.cancellations > 0)
        }

    @Test
    fun `approval that requires fresh sign in never resubmits the spent passkey stage`() =
        runTest {
            val f = Fixture()
            val vm = f.model()
            runCurrent()
            f.repo.state.value = BlueskySignInState.DeviceApproval(requiresNewSignIn = true)
            vm.confirmPasskey(true)
            runCurrent()
            assertEquals(0, f.repo.passkeyCalls)
        }

    @Test
    fun `turning the feature off clears a pending browser handoff`() =
        runTest {
            val f = Fixture()
            val vm = f.model()
            runCurrent()
            vm.updateHandle("alice.test")
            vm.start()
            runCurrent()
            f.enabled.value = false
            runCurrent()
            assertFalse(vm.uiState.value.available)
            assertNull(vm.uiState.value.browserUrl)
            assertTrue(f.repo.cancellations > 0)
        }

    @Test
    fun `late profile loading cannot replace typed details`() =
        runTest {
            val f = Fixture()
            val loaded = CompletableDeferred<AccountCreationRequest>()
            val vm = f.model(profile = { loaded.await() })
            runCurrent()
            vm.updateDetails("typed", "Typed Name")
            loaded.complete(AccountCreationRequest("old", "Old Name"))
            runCurrent()
            assertEquals("typed", vm.uiState.value.details.username)
            assertEquals("Typed Name", vm.uiState.value.details.displayName)
        }

    @Test
    fun `start failures are visible without exposing the provider error`() =
        runTest {
            val f = Fixture()
            f.repo.startFailure = IllegalStateException("private-provider-detail")
            val vm = f.model()
            runCurrent()
            vm.updateHandle("alice.test")
            vm.start()
            runCurrent()
            assertEquals(app.logdate.client.repository.account.BlueskySignInFailure.UNAVAILABLE, vm.uiState.value.failure)
            assertFalse(
                vm.uiState.value
                    .toString()
                    .contains("private-provider-detail"),
            )
            assertNull(vm.uiState.value.browserUrl)
        }

    @Test
    fun `a completion from another server never starts sync`() =
        runTest {
            val f = Fixture()
            var synced = false
            val vm =
                f.model(finish = {
                    synced = true
                    InitialSyncStatus.Success
                })
            runCurrent()
            f.repo.state.value =
                BlueskySignInState.Complete(LogDateAccount(username = "alice", displayName = "Alice"), "https://other.logdate.app")
            runCurrent()
            assertFalse(synced)
            assertFalse(vm.uiState.value.finished)
        }

    @Test
    fun `failed browser launch discards the pending UI launch and shows a bounded problem`() =
        runTest {
            val f = Fixture()
            val vm = f.model()
            runCurrent()
            vm.updateHandle("alice.test")
            vm.start()
            runCurrent()
            vm.browserLaunchFailed()
            assertNull(vm.uiState.value.browserUrl)
            assertEquals(app.logdate.client.repository.account.BlueskySignInFailure.UNAVAILABLE, vm.uiState.value.failure)
        }

    @Test
    fun `starting a fresh flow clears the old grant and unlocks saved profile fields`() =
        runTest {
            val f = Fixture()
            val vm = f.model()
            runCurrent()
            f.repo.state.value = BlueskySignInState.AccountDetails(AccountCreationRequest("saved", "Saved"))
            runCurrent()
            vm.restart()
            runCurrent()
            assertFalse(vm.uiState.value.detailsLocked)
            assertEquals(BlueskySignInState.Idle, vm.stage.value)
            assertTrue(f.repo.cancellations > 0)
        }

    @Test
    fun `cancel cannot finish even when the sync collaborator converts cancellation to a failure`() =
        runTest {
            val f = Fixture()
            val wait = CompletableDeferred<Unit>()
            val vm =
                f.model(finish = {
                    try {
                        wait.await()
                        InitialSyncStatus.Success
                    } catch (_: CancellationException) {
                        InitialSyncStatus.Failed
                    }
                })
            runCurrent()
            f.repo.state.value = BlueskySignInState.Complete(LogDateAccount(username = "alice", displayName = "Alice"), ORIGIN)
            runCurrent()
            vm.cancel()
            runCurrent()
            assertFalse(vm.uiState.value.finished)
        }

    private inner class Fixture {
        val config = DefaultLogDateConfigRepository(initialBackendUrl = ORIGIN)
        val repo = Repository()
        val enabled = MutableStateFlow(true)

        fun model(
            profile: suspend () -> AccountCreationRequest = { AccountCreationRequest("alice", "Alice") },
            finish: suspend (LogDateAccount) -> InitialSyncStatus = { InitialSyncStatus.Success },
        ): BlueskySignInViewModel =
            BlueskySignInViewModel(
                repo,
                config,
                object : FeatureFlagStore {
                    override fun observe(flag: FeatureFlag) = enabled

                    override suspend fun setEnabled(
                        flag: FeatureFlag,
                        enabled: Boolean,
                    ) {
                        this@Fixture.enabled.value = enabled
                    }
                },
                Manager(),
                profile,
                finish,
            ).also { models += it }
    }

    private class Repository : BlueskySignInRepository {
        override val state = MutableStateFlow<BlueskySignInState>(BlueskySignInState.Idle)
        var startFailure: Exception? = null
        var startedHandle: String? = null
        var creation: AccountCreationRequest? = null
        var passkeyCalls = 0
        var cancellations = 0

        override suspend fun isAvailable() = true

        override suspend fun start(handle: String): Result<String> {
            startFailure?.let { return Result.failure(it) }
            startedHandle = handle
            state.value =
                BlueskySignInState.AwaitingBrowser
            return Result.success("https://bsky.example/authorize")
        }

        override suspend fun resume(returnUrl: String) = true

        override suspend fun restore() = Unit

        override suspend fun createAccount(request: AccountCreationRequest) {
            creation = request
            passkeyCalls++
        }

        override suspend fun confirmPasskey(adoptLocalData: Boolean) {
            passkeyCalls++
        }

        override suspend fun cancel() {
            cancellations++
            state.value = BlueskySignInState.Idle
        }
    }

    private class Manager : PasskeyManager {
        override suspend fun getCapabilities() = PasskeyCapabilities(true, true, listOf("ES256"))

        override suspend fun isPlatformAuthenticatorAvailable() = true

        override suspend fun registerPasskey(options: PasskeyRegistrationOptions): Result<String> = error("No native ceremony")

        override suspend fun authenticateWithPasskey(options: PasskeyAuthenticationOptions): Result<String> = error("No native ceremony")

        override fun getAvailabilityStatus() = MutableStateFlow(PasskeyCapabilities(true, true, listOf("ES256")))
    }

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
    }
}
