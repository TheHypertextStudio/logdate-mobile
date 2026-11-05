package app.logdate.feature.core.settings.account.signin

import app.logdate.client.repository.account.NotSignedInException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignInMethodsOperationTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adding a passkey retains accepted rows when follow up load fails`() =
        runTest {
            val added = passkey("new", "New device")
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey("existing")),
                    linkedProviders = listOf(googleProvider()),
                    addPasskeyResult = Result.success(added),
                )
            val model = signInViewModel(repository)
            repository.listFailureAfterMutation = IllegalStateException("Private provider detail")
            model.addPasskey()
            val state = assertIs<SignInMethodsUiState.Loaded>(model.state.value)
            assertEquals(listOf("existing", "new"), state.passkeys.map { it.credentialId })
            assertEquals("alice@example.com", state.linkedProviders.single().email)
            assertEquals(SignInMethodsRefreshState.Failed(SignInMethodsLoadError.SERVER), state.refresh)
            assertEquals(AddPasskeyState.Idle, state.add)
            assertEquals(
                SignInMethodsEvent.PasskeyAdded("New device"),
                model.notices.value
                    .single()
                    .event,
            )
        }

    @Test
    fun `removal retains remaining rows when follow up load fails`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a"), passkey("b")))
            val model = signInViewModel(repository)
            repository.listFailureAfterMutation = IllegalStateException("Private provider detail")
            model.removePasskey("a")
            val state = assertIs<SignInMethodsUiState.Loaded>(model.state.value)
            assertEquals(listOf("b"), state.passkeys.map { it.credentialId })
            assertEquals(SignInMethodsRefreshState.Failed(SignInMethodsLoadError.SERVER), state.refresh)
            assertEquals(PasskeyRemovalState.Idle, state.removal)
            assertEquals(
                SignInMethodsEvent.PasskeyRemoved,
                model.notices.value
                    .single()
                    .event,
            )
        }

    @Test
    fun `thrown add failures stay on add and retry clears their old error immediately`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a")))
            val model = signInViewModel(repository)
            repository.addThrows = IllegalStateException("Private provider detail")
            model.addPasskey()
            assertEquals(AddPasskeyState.Failed(AddPasskeyFailure.SERVER), assertIs<SignInMethodsUiState.Loaded>(model.state.value).add)
            repository.addThrows = null
            repository.addGate = CompletableDeferred()
            repository.addPasskeyResult = Result.success(passkey("b"))
            model.addPasskey()
            assertEquals(AddPasskeyState.Running, assertIs<SignInMethodsUiState.Loaded>(model.state.value).add)
            repository.addGate?.complete(Unit)
            assertEquals(listOf("a", "b"), assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys.map { it.credentialId })
        }

    @Test
    fun `result wrapped add cancellation is quiet and releases pending state`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey("a")),
                    addPasskeyResult = Result.failure(CancellationException("Cancelled")),
                )
            val model = signInViewModel(repository)
            model.addPasskey()
            assertEquals(AddPasskeyState.Idle, assertIs<SignInMethodsUiState.Loaded>(model.state.value).add)
            assertTrue(model.notices.value.isEmpty())
            repository.addPasskeyResult = Result.success(passkey("b"))
            model.addPasskey()
            assertEquals(2, assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys.size)
        }

    @Test
    fun `thrown removal cancellation is quiet and permits explicit retry`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a"), passkey("b")))
            val model = signInViewModel(repository)
            repository.deleteThrows = CancellationException("Cancelled")
            model.removePasskey("a")
            assertEquals(PasskeyRemovalState.Idle, assertIs<SignInMethodsUiState.Loaded>(model.state.value).removal)
            assertTrue(model.notices.value.isEmpty())
            repository.deleteThrows = null
            model.removePasskey("a")
            assertEquals(listOf("b"), assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys.map { it.credentialId })
        }

    @Test
    fun `refresh coalesces duplicates and excludes conflicting mutations`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a"), passkey("b")))
            val model = signInViewModel(repository)
            repository.listGate = CompletableDeferred()
            model.refresh()
            model.refresh()
            model.addPasskey()
            model.removePasskey("a")
            assertEquals(2, repository.listCallCount)
            assertEquals(0, repository.addCallCount)
            assertTrue(repository.deletedCredentialIds.isEmpty())
            assertEquals(SignInMethodsRefreshState.Running, assertIs<SignInMethodsUiState.Loaded>(model.state.value).refresh)
            repository.listGate?.complete(Unit)
            assertEquals(SignInMethodsRefreshState.Idle, assertIs<SignInMethodsUiState.Loaded>(model.state.value).refresh)
        }

    @Test
    fun `pending add excludes duplicate add removal and refresh until its new snapshot`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a"), passkey("b")))
            val model = signInViewModel(repository)
            repository.addGate = CompletableDeferred()
            repository.addPasskeyResult = Result.success(passkey("c"))
            model.addPasskey()
            model.addPasskey()
            model.removePasskey("a")
            model.refresh()
            assertEquals(1, repository.addCallCount)
            assertEquals(1, repository.listCallCount)
            assertTrue(repository.deletedCredentialIds.isEmpty())
            repository.addGate?.complete(Unit)
            assertEquals(listOf("a", "b", "c"), assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys.map { it.credentialId })
        }

    @Test
    fun `unknown and known last factors never reach the delete endpoint`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a")))
            val model = signInViewModel(repository)
            model.removePasskey("unknown")
            model.removePasskey("a")
            assertTrue(repository.deletedCredentialIds.isEmpty())
            assertEquals(listOf("a"), assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys.map { it.credentialId })
        }

    @Test
    fun `initial cancellation leaves explicit retry without a false failure`() =
        runTest {
            val repository = SignInFakeAccountRepository(listError = CancellationException("Cancelled"))
            val model = signInViewModel(repository)
            assertEquals(SignInMethodsUiState.NotLoaded, model.state.value)
            repository.listError = null
            repository.passkeys = listOf(passkey("a"))
            model.refresh()
            assertIs<SignInMethodsUiState.Loaded>(model.state.value)
        }

    @Test
    fun `refresh cancellation retains loaded rows and removes pending progress`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a")))
            val model = signInViewModel(repository)
            repository.listThrows = CancellationException("Cancelled")
            model.refresh()
            val state = assertIs<SignInMethodsUiState.Loaded>(model.state.value)
            assertEquals(listOf("a"), state.passkeys.map { it.credentialId })
            assertEquals(SignInMethodsRefreshState.Idle, state.refresh)
        }

    @Test
    fun `provider refresh failure retains known providers`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a")), linkedProviders = listOf(googleProvider()))
            val model = signInViewModel(repository)
            repository.providersError = IllegalStateException("Unavailable")
            model.refresh()
            val state = assertIs<SignInMethodsUiState.Loaded>(model.state.value)
            assertEquals("alice@example.com", state.linkedProviders.single().email)
            assertTrue(state.passkeys.single().canRemove)
            assertEquals(SignInMethodsRefreshState.Failed(SignInMethodsLoadError.SERVER), state.refresh)
        }

    @Test
    fun `actual signed out refresh invalidates previous account rows`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey("a")))
            val model = signInViewModel(repository)
            repository.listError = NotSignedInException()
            model.refresh()
            assertEquals(SignInMethodsUiState.Failed(SignInMethodsLoadError.NOT_SIGNED_IN), model.state.value)
        }

    @Test
    fun `notices survive stopped observers and stale acknowledgement cannot remove a new result`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(passkeys = listOf(passkey("a"), passkey("b")), linkedProviders = listOf(googleProvider()))
            val model = signInViewModel(repository)
            model.removePasskey("a")
            val first = model.notices.first().single()
            assertEquals(first, model.notices.first().single())
            model.removePasskey("b")
            val second = model.notices.value.last()
            model.acknowledgeNotice(first.id)
            assertEquals(listOf(second), model.notices.first())
            model.acknowledgeNotice(first.id)
            assertEquals(listOf(second), model.notices.value)
            model.acknowledgeNotice(second.id)
            assertTrue(model.notices.value.isEmpty())
        }
}
