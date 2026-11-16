package app.logdate.feature.core.settings.account

import app.logdate.client.domain.identity.ResolvedUserIdentity
import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.permissions.EmailVerificationOutcome
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.settings.account.recovery.AccountRecoveryUiState
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `without a session the screen offers sign-in`() =
        runTest {
            val viewModel = viewModel(repository = FakeAccountRepository(account = null))

            assertEquals(AccountUiState.SignedOut, viewModel.state.value)
        }

    @Test
    fun `the header shows the person's name and username`() =
        runTest {
            val state = signedIn(viewModel(identity = identity(displayName = "Alice Chen", username = "alice")))

            assertEquals("Alice Chen", state.header.displayName)
            assertEquals("alice", state.header.username)
        }

    @Test
    fun `sign-in methods are summarised by count and linked account`() =
        runTest {
            val repository =
                FakeAccountRepository(
                    passkeys = listOf(passkey(), passkey()),
                    linkedProviders = listOf(googleProvider()),
                )

            val state = signedIn(viewModel(repository = repository))

            assertEquals(
                SignInSummary.Known(passkeyCount = 2, linkedProviders = listOf(LinkedSignInProvider.Kind.GOOGLE)),
                state.signIn,
            )
        }

    @Test
    fun `sign-in methods that cannot be read leave the row without counts`() =
        runTest {
            val repository = FakeAccountRepository(listError = PasskeyApiException(PasskeyApiErrorCodes.NETWORK_ERROR, "Offline"))

            assertEquals(SignInSummary.Unknown, signedIn(viewModel(repository = repository)).signIn)
        }

    @Test
    fun `a device without the recovery phrase says so`() =
        runTest {
            assertEquals(false, signedIn(viewModel(hasRecoveryPhrase = false)).hasRecoveryPhrase)
            assertEquals(true, signedIn(viewModel(hasRecoveryPhrase = true)).hasRecoveryPhrase)
        }

    @Test
    fun `account recovery that waits on the person is flagged on the account and clears once set up`() =
        runTest {
            val recovery = MutableStateFlow<AccountRecoveryUiState?>(AccountRecoveryUiState.NeedsSetup)
            val viewModel = viewModel(accountRecovery = recovery)

            assertEquals(true, signedIn(viewModel).accountRecoveryNeedsAttention)

            recovery.value = AccountRecoveryUiState.Retrying(IdentityRecoveryFailure.UNREACHABLE)
            assertEquals(false, signedIn(viewModel).accountRecoveryNeedsAttention)

            recovery.value = AccountRecoveryUiState.SetupFailed(IdentityRecoveryFailure.PASSKEY_REQUIRED)
            assertEquals(true, signedIn(viewModel).accountRecoveryNeedsAttention)

            recovery.value = AccountRecoveryUiState.Ready
            assertEquals(false, signedIn(viewModel).accountRecoveryNeedsAttention)
        }

    @Test
    fun `the recovery phrase is not called saved before it has been checked`() =
        runTest {
            val phraseCheck = CompletableDeferred<Boolean>()

            val viewModel = viewModel(checkRecoveryPhrase = { phraseCheck.await() })

            assertNull(signedIn(viewModel).hasRecoveryPhrase)
            phraseCheck.complete(false)
            assertEquals(false, signedIn(viewModel).hasRecoveryPhrase)
        }

    @Test
    fun `a verified email is shown as verified`() =
        runTest {
            val repository = FakeAccountRepository(account = account(email = "alice@example.com", emailVerified = true))

            val email = signedIn(viewModel(repository = repository, isEmailVerificationAvailable = true)).email

            assertEquals(EmailRow(address = "alice@example.com", isVerified = true, canVerify = false), email)
        }

    @Test
    fun `an unverified account is offered verification where it is available`() =
        runTest {
            val email = signedIn(viewModel(isEmailVerificationAvailable = true)).email

            assertEquals(EmailRow(address = null, isVerified = false, canVerify = true), email)
        }

    @Test
    fun `no email row appears when there is no email and no way to verify one`() =
        runTest {
            assertNull(signedIn(viewModel(isEmailVerificationAvailable = false)).email)
        }

    @Test
    fun `a successful verification reloads the account`() =
        runTest {
            val repository = FakeAccountRepository()
            val viewModel =
                viewModel(
                    repository = repository,
                    isEmailVerificationAvailable = true,
                    verifyEmail = { EmailVerificationOutcome.Success("alice@example.com", Clock.System.now()) },
                )

            viewModel.verifyEmail()

            assertEquals(1, repository.accountInfoRequests)
            assertIs<EmailVerificationOutcome.Success>(viewModel.emailVerification.value.outcome)
        }

    @Test
    fun `the hosting row names the server and its address`() =
        runTest {
            val server =
                FakeConnectedServer(
                    ConnectedServerInfo(
                        origin = "https://journal.example.com",
                        displayName = "Alice's LogDate",
                        isLogDateCloud = false,
                        publishesIdentityChanges = false,
                    ),
                )

            val state = signedIn(viewModel(server = server))

            assertEquals(ServerRow(name = "Alice's LogDate", host = "journal.example.com", isLogDateCloud = false), state.server)
        }

    @Test
    fun `opening the screen asks the server for its current description`() =
        runTest {
            val server = FakeConnectedServer()

            viewModel(server = server)

            assertEquals(1, server.refreshes)
        }

    @Test
    fun `signing out announces it`() =
        runTest {
            val repository = FakeAccountRepository()
            val viewModel = viewModel(repository = repository)
            val events = mutableListOf<AccountEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

            viewModel.signOut()

            assertEquals(listOf<AccountEvent>(AccountEvent.SignedOut), events)
        }

    @Test
    fun `a failed sign-out says why and keeps the person signed in`() =
        runTest {
            val repository =
                FakeAccountRepository(signOutResult = Result.failure(PasskeyApiException(PasskeyApiErrorCodes.NETWORK_ERROR, "Offline")))
            val viewModel = viewModel(repository = repository)
            val events = mutableListOf<AccountEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

            viewModel.signOut()

            assertEquals(listOf<AccountEvent>(AccountEvent.SignOutFailed), events)
            assertFalse(signedIn(viewModel).isSigningOut)
        }

    private fun signedIn(viewModel: AccountViewModel): AccountUiState.SignedIn = assertIs(viewModel.state.value)

    private fun viewModel(
        repository: FakeAccountRepository = FakeAccountRepository(),
        identity: ResolvedUserIdentity = identity(),
        server: FakeConnectedServer = FakeConnectedServer(),
        hasRecoveryPhrase: Boolean = true,
        checkRecoveryPhrase: suspend () -> Boolean = { hasRecoveryPhrase },
        isEmailVerificationAvailable: Boolean = false,
        verifyEmail: suspend () -> EmailVerificationOutcome = { EmailVerificationOutcome.Unsupported },
        accountRecovery: Flow<AccountRecoveryUiState?> = MutableStateFlow(null),
    ) = AccountViewModel(
        accountRepository = repository,
        userIdentity = MutableStateFlow(identity),
        connectedServer = server,
        hasRecoveryPhrase = checkRecoveryPhrase,
        isEmailVerificationAvailable = { isEmailVerificationAvailable },
        verifyEmail = verifyEmail,
        accountRecovery = accountRecovery,
    )

    private fun identity(
        displayName: String = "Alice Chen",
        username: String? = "alice",
    ) = ResolvedUserIdentity(
        displayName = displayName,
        username = username,
        profilePhotoUri = null,
        bio = null,
        birthday = null,
        onboardedDate = null,
        isAuthenticated = true,
        cloudAccountId = null,
    )

    private fun account(
        email: String? = null,
        emailVerified: Boolean = false,
    ) = LogDateAccount(
        id = Uuid.random(),
        username = "alice",
        displayName = "Alice Chen",
        email = email,
        emailVerified = emailVerified,
    )

    private fun passkey() =
        PasskeyInfo(
            id = Uuid.random(),
            credentialId = "cred-${Uuid.random()}",
            nickname = "Pixel 9",
            deviceType = "platform",
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            lastUsedAt = null,
            isActive = true,
        )

    private fun googleProvider() =
        LinkedSignInProvider(
            kind = LinkedSignInProvider.Kind.GOOGLE,
            email = "alice@example.com",
            linkedAt = Instant.parse("2026-09-01T10:00:00Z"),
            lastSignInAt = null,
        )

    private class FakeConnectedServer(
        initial: ConnectedServerInfo =
            ConnectedServerInfo(
                origin = "https://cloud.logdate.app",
                displayName = "LogDate Cloud",
                isLogDateCloud = true,
                publishesIdentityChanges = false,
            ),
    ) : ConnectedServer {
        var refreshes = 0
        override val info: Flow<ConnectedServerInfo> = MutableStateFlow(initial)

        override suspend fun refresh(): ServerHealth {
            refreshes++
            return ServerHealth.Reachable(version = "1.4.0")
        }
    }

    private inner class FakeAccountRepository(
        account: LogDateAccount? = account(),
        private val passkeys: List<PasskeyInfo> = listOf(passkey()),
        private val linkedProviders: List<LinkedSignInProvider> = emptyList(),
        private val listError: Throwable? = null,
        private val signOutResult: Result<Unit> = Result.success(Unit),
    ) : StubPasskeyAccountRepository(account = account, isAuthenticated = account != null) {
        var accountInfoRequests = 0

        override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = listError?.let { Result.failure(it) } ?: Result.success(passkeys)

        override suspend fun listLinkedSignInProviders(): Result<List<LinkedSignInProvider>> =
            listError?.let { Result.failure(it) } ?: Result.success(linkedProviders)

        override suspend fun signOut(): Result<Unit> = signOutResult

        override suspend fun getAccountInfo(): Result<LogDateAccount> {
            accountInfoRequests++
            return Result.failure(NotImplementedError())
        }
    }
}
