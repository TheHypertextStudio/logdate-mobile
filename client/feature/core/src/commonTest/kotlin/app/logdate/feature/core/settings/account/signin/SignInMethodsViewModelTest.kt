package app.logdate.feature.core.settings.account.signin

import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.permissions.PasskeyErrorCodes
import app.logdate.client.permissions.PasskeyException
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.client.repository.account.NotSignedInException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SignInMethodsViewModelTest {
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
    fun `passkeys are listed by device with the days they were added and last used`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys =
                        listOf(
                            passkey(
                                credentialId = "cred-pixel",
                                nickname = "Pixel 9",
                                createdAt = "2026-09-03T10:00:00Z",
                                lastUsedAt = "2026-09-22T18:00:00Z",
                            ),
                        ),
                )

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository).state.value)

            val row = state.passkeys.single()
            assertEquals("cred-pixel", row.credentialId)
            assertEquals("Pixel 9", row.deviceName)
            assertEquals(LocalDate(2026, 9, 3), row.addedOn)
            assertEquals(LocalDate(2026, 9, 22), row.lastUsedOn)
        }

    @Test
    fun `a passkey still carrying the server's default name is shown as unnamed`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey(nickname = "LogDate")))

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository, defaultPasskeyName = "LogDate").state.value)

            assertNull(state.passkeys.single().deviceName)
        }

    @Test
    fun `a linked Google account is listed with its address`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    linkedProviders =
                        listOf(
                            LinkedSignInProvider(
                                kind = LinkedSignInProvider.Kind.GOOGLE,
                                email = "alice@example.com",
                                linkedAt = Instant.parse("2026-09-01T10:00:00Z"),
                                lastSignInAt = Instant.parse("2026-09-20T10:00:00Z"),
                            ),
                        ),
                )

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository).state.value)

            val google = state.linkedProviders.single()
            assertEquals(LinkedSignInProvider.Kind.GOOGLE, google.kind)
            assertEquals("alice@example.com", google.email)
            assertEquals(LocalDate(2026, 9, 20), google.lastSignInOn)
        }

    @Test
    fun `the only way to sign in cannot be removed`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey()))

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository).state.value)

            assertFalse(state.passkeys.single().canRemove)
        }

    @Test
    fun `a passkey can be removed when a linked account still signs in`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    linkedProviders = listOf(googleProvider()),
                )

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository).state.value)

            assertTrue(state.passkeys.single().canRemove)
        }

    @Test
    fun `adding a passkey announces it and refreshes the list`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey(credentialId = "cred-a")))
            val viewModel = signInViewModel(repository)

            repository.passkeys = repository.passkeys + passkey(credentialId = "cred-b", nickname = "Pixel 9")
            repository.addPasskeyResult = Result.success(passkey(credentialId = "cred-b", nickname = "Pixel 9"))
            viewModel.addPasskey()

            assertEquals(listOf(SignInMethodsEvent.PasskeyAdded(deviceName = "Pixel 9")), viewModel.notices.value.map { it.event })
            val state = assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value)
            assertEquals(listOf("cred-a", "cred-b"), state.passkeys.map { it.credentialId })
            assertFalse(state.isAddingPasskey)
        }

    @Test
    fun `cancelling the passkey prompt says nothing`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    addPasskeyResult = Result.failure(PasskeyException("Cancelled", PasskeyErrorCodes.USER_CANCELLED)),
                )
            val viewModel = signInViewModel(repository)

            viewModel.addPasskey()

            assertTrue(viewModel.notices.value.isEmpty())
            assertFalse(assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value).isAddingPasskey)
        }

    @Test
    fun `a passkey this device already holds is reported as such`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    addPasskeyResult = Result.failure(PasskeyException("Exists", PasskeyErrorCodes.INVALID_STATE)),
                )
            val viewModel = signInViewModel(repository)

            viewModel.addPasskey()

            assertEquals(
                AddPasskeyState.Failed(AddPasskeyFailure.ALREADY_ON_THIS_DEVICE),
                assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value).add,
            )
        }

    @Test
    fun `adding a passkey is not offered where the platform has no passkeys`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey()))

            val state =
                assertIs<SignInMethodsUiState.Loaded>(
                    signInViewModel(repository, passkeyManager = SignInFakePasskeyManager(supported = false)).state.value,
                )

            assertFalse(state.canAddPasskey)
        }

    @Test
    fun `removing a passkey announces it and refreshes the list`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(passkeys = listOf(passkey(credentialId = "cred-a"), passkey(credentialId = "cred-b")))
            val viewModel = signInViewModel(repository)

            viewModel.removePasskey("cred-a")

            assertEquals(listOf("cred-a"), repository.deletedCredentialIds)
            assertEquals(listOf(SignInMethodsEvent.PasskeyRemoved), viewModel.notices.value.map { it.event })
            val state = assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value)
            assertEquals(listOf("cred-b"), state.passkeys.map { it.credentialId })
            assertNull(state.removingCredentialId)
        }

    @Test
    fun `the server refusing to remove the last sign-in method is reported as such`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey(credentialId = "cred-a"), passkey(credentialId = "cred-b")),
                    deleteResult = Result.failure(PasskeyApiException(PasskeyApiErrorCodes.LAST_SIGNIN_FACTOR, "Last")),
                )
            val viewModel = signInViewModel(repository)

            viewModel.removePasskey("cred-a")

            assertEquals(
                PasskeyRemovalState.Failed("cred-a", RemovePasskeyFailure.LAST_SIGN_IN_METHOD),
                assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value).removal,
            )
        }

    @Test
    fun `adding a passkey is not offered for a server this platform can't create passkeys for`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey()))

            val state =
                assertIs<SignInMethodsUiState.Loaded>(
                    signInViewModel(
                        repository,
                        connectedRpId = "journal.example.com",
                        passkeysWorkWith = { it.endsWith("logdate.app") },
                    ).state.value,
                )

            assertFalse(state.canAddPasskey)
        }

    @Test
    fun `passkeys still show when linked accounts can't be loaded`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    providersError = PasskeyApiException(PasskeyApiErrorCodes.SERVER_ERROR, "Not found"),
                )

            val state = assertIs<SignInMethodsUiState.Loaded>(signInViewModel(repository).state.value)

            assertEquals(1, state.passkeys.size)
            assertTrue(state.linkedProviders.isEmpty())
        }

    @Test
    fun `an unreachable server is reported as offline and can be retried`() =
        runTest {
            val repository =
                SignInFakeAccountRepository(
                    passkeys = listOf(passkey()),
                    listError = PasskeyApiException(PasskeyApiErrorCodes.NETWORK_ERROR, "Offline"),
                )
            val viewModel = signInViewModel(repository)

            assertEquals(SignInMethodsUiState.Failed(SignInMethodsLoadError.OFFLINE), viewModel.state.value)

            repository.listError = null
            viewModel.refresh()

            assertIs<SignInMethodsUiState.Loaded>(viewModel.state.value)
        }

    @Test
    fun `without a session the screen says so`() =
        runTest {
            val repository = SignInFakeAccountRepository(listError = NotSignedInException())

            assertEquals(
                SignInMethodsUiState.Failed(SignInMethodsLoadError.NOT_SIGNED_IN),
                signInViewModel(repository).state.value,
            )
        }

    @Test
    fun `a failed refresh preserves known sign in methods`() =
        runTest {
            val repository = SignInFakeAccountRepository(passkeys = listOf(passkey()))
            val model = signInViewModel(repository)
            val before = assertIs<SignInMethodsUiState.Loaded>(model.state.value)
            repository.listError = IllegalStateException("Unavailable")
            model.refresh()
            assertEquals(before.passkeys, assertIs<SignInMethodsUiState.Loaded>(model.state.value).passkeys)
        }
}
