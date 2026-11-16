package app.logdate.feature.core.settings.account.recovery

import app.logdate.client.repository.account.AccountDerivedPlcRecoveryKey
import app.logdate.client.repository.account.AccountExportedSigningKey
import app.logdate.client.repository.account.AccountHostedPlcOperation
import app.logdate.client.repository.account.AccountIdentityRepository
import app.logdate.client.repository.account.AccountIdentityStatus
import app.logdate.client.repository.account.AccountImportedSigningKey
import app.logdate.client.repository.account.AccountRegisteredPlcRecoveryKey
import app.logdate.client.repository.account.AccountRotatedSigningKey
import app.logdate.client.repository.account.IdentityRecoveryException
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.client.repository.account.PasskeyConfirmation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Account recovery is set up on its own, with the passkey confirmation the person already gave when
 * signing in: nobody opens or saves the recovery phrase for it, and nothing it does is silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticAccountRecoveryTest {
    private val phrase = List(12) { "word$it" }

    @Test
    fun `a passkey sign in registers the recovery key without another prompt`() =
        runTest {
            val identity = FakeIdentity()
            val setup = setup(identity)

            setup.signIn(withConfirmation = true)

            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
            assertEquals(DERIVED, identity.registered)
            assertEquals(listOf(false), identity.registrationPrompts)
            assertEquals(0, identity.prompts)
        }

    @Test
    fun `an account that already has this key is left as it is`() =
        runTest {
            val identity = FakeIdentity(registered = DERIVED)
            val setup = setup(identity)

            setup.signIn(withConfirmation = true)

            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
            assertTrue(identity.registrationPrompts.isEmpty())
            assertEquals(0, identity.prompts)
        }

    @Test
    fun `without a recent sign in nothing is sent and setup waits for one passkey confirmation`() =
        runTest {
            val identity = FakeIdentity()
            val setup = setup(identity)

            setup.signIn(withConfirmation = false)

            assertEquals(AccountRecoveryUiState.NeedsSetup, setup.recovery.state.value)
            assertNull(identity.registered)
            assertEquals(0, identity.prompts)

            identity.confirmation = "next-sign-in"
            setup.confirmations.value = PasskeyConfirmation(ACCOUNT, "next-sign-in", Instant.fromEpochSeconds(0))
            runCurrent()

            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
            assertEquals(DERIVED, identity.registered)
        }

    @Test
    fun `a closed passkey prompt leaves setup waiting rather than finished`() =
        runTest {
            val identity = FakeIdentity(promptFailure = IdentityRecoveryFailure.STEP_UP_CANCELLED)
            val setup = setup(identity)
            setup.signIn(withConfirmation = false)

            setup.recovery.finishWithPasskey()
            runCurrent()

            assertEquals(AccountRecoveryUiState.NeedsSetup, setup.recovery.state.value)
            assertNull(identity.registered)
            assertEquals(1, identity.prompts)

            identity.promptFailure = null
            setup.recovery.finishWithPasskey()
            runCurrent()

            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
            assertEquals(DERIVED, identity.registered)
        }

    @Test
    fun `a connection failure is shown and tried again until it works`() =
        runTest {
            val identity = FakeIdentity(transientFailures = 2)
            val setup = setup(identity)

            setup.signIn(withConfirmation = true)
            assertEquals(AccountRecoveryUiState.Retrying(IdentityRecoveryFailure.UNREACHABLE), setup.recovery.state.value)

            advanceTimeBy(RETRY + 1.seconds)
            assertEquals(AccountRecoveryUiState.Retrying(IdentityRecoveryFailure.UNREACHABLE), setup.recovery.state.value)

            advanceTimeBy(RETRY + 1.seconds)
            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
            assertEquals(3, identity.registrationPrompts.size)
            assertEquals(0, identity.prompts)
        }

    @Test
    fun `a connection failure that outlasts every retry stays visible and can be tried again`() =
        runTest {
            val identity = FakeIdentity(transientFailures = Int.MAX_VALUE)
            val setup = setup(identity)

            setup.signIn(withConfirmation = true)
            advanceTimeBy(10.minutes)

            assertEquals(AccountRecoveryUiState.SetupFailed(IdentityRecoveryFailure.UNREACHABLE), setup.recovery.state.value)

            identity.transientFailures = 0
            setup.recovery.checkAgain()
            runCurrent()

            assertEquals(AccountRecoveryUiState.Ready, setup.recovery.state.value)
        }

    @Test
    fun `a failure the person has to fix says so instead of retrying`() =
        runTest {
            val identity = FakeIdentity(registerFailure = IdentityRecoveryFailure.PASSKEY_REQUIRED)
            val setup = setup(identity)

            setup.signIn(withConfirmation = true)
            advanceTimeBy(10.minutes)

            assertEquals(AccountRecoveryUiState.SetupFailed(IdentityRecoveryFailure.PASSKEY_REQUIRED), setup.recovery.state.value)
            assertEquals(1, identity.registrationPrompts.size)
        }

    @Test
    fun `nothing happens while signed out or without a phrase or where it is not offered`() =
        runTest {
            val signedOut = setup(FakeIdentity())
            signedOut.recovery.start()
            runCurrent()

            val noPhrase = setup(FakeIdentity(), storedPhrase = null)
            noPhrase.signIn(withConfirmation = true)

            val notOffered = setup(FakeIdentity(), offered = false)
            notOffered.signIn(withConfirmation = true)

            listOf(signedOut, noPhrase, notOffered).forEach { setup ->
                assertNull(setup.recovery.state.value)
                assertNull(setup.identity.registered)
            }
        }

    private class Setup(
        val recovery: AutomaticAccountRecovery,
        val identity: FakeIdentity,
        val account: MutableStateFlow<String?>,
        val confirmations: MutableStateFlow<PasskeyConfirmation?>,
        private val scope: TestScope,
    ) {
        fun signIn(withConfirmation: Boolean) {
            recovery.start()
            if (withConfirmation) {
                identity.confirmation = "sign-in"
                confirmations.value = PasskeyConfirmation(ACCOUNT, "sign-in", Instant.fromEpochSeconds(0))
            }
            account.value = ACCOUNT
            scope.runCurrent()
        }
    }

    private fun TestScope.setup(
        identity: FakeIdentity,
        storedPhrase: List<String>? = phrase,
        offered: Boolean = true,
    ): Setup {
        val account = MutableStateFlow<String?>(null)
        val confirmations = MutableStateFlow<PasskeyConfirmation?>(null)
        val recovery =
            AutomaticAccountRecovery(
                recovery = IdentityAccountRecovery(identity, MutableStateFlow(offered)),
                loadPhrase = { storedPhrase },
                signedInAccount = account,
                passkeyConfirmations = confirmations,
                scope = backgroundScope,
                retryDelays = listOf(RETRY, RETRY, RETRY),
            )
        return Setup(recovery, identity, account, confirmations, this)
    }

    /**
     * The identity as the repository presents it: a sign-in [confirmation] is spent once, and the
     * passkey prompt can be closed.
     */
    private class FakeIdentity(
        var registered: String? = null,
        var promptFailure: IdentityRecoveryFailure? = null,
        var transientFailures: Int = 0,
        var registerFailure: IdentityRecoveryFailure? = null,
    ) : AccountIdentityRepository {
        var confirmation: String? = null
        var prompts = 0
        val registrationPrompts = mutableListOf<Boolean>()

        override suspend fun getIdentityStatus(): Result<AccountIdentityStatus> =
            Result.success(
                AccountIdentityStatus(
                    did = "did:plc:alice123",
                    handle = "alice.logdate.app",
                    signingKeyPublicMultibase = "zServer",
                    signingKeyDidKey = "did:key:zServer",
                    plcRecoveryDidKey = registered,
                ),
            )

        override suspend fun derivePlcRecoveryDidKey(recoveryPhrase: String): Result<AccountDerivedPlcRecoveryKey> =
            Result.success(AccountDerivedPlcRecoveryKey(DERIVED))

        override suspend fun registerRecoveryKey(
            recoveryPhrase: List<String>,
            askForPasskey: Boolean,
        ): Result<AccountRegisteredPlcRecoveryKey> {
            registrationPrompts += askForPasskey
            if (transientFailures > 0) {
                transientFailures--
                return failure(IdentityRecoveryFailure.UNREACHABLE)
            }
            registerFailure?.let { return failure(it) }
            when {
                confirmation != null -> {
                    confirmation = null
                }

                !askForPasskey -> {
                    return failure(IdentityRecoveryFailure.CONFIRMATION_NEEDED)
                }

                else -> {
                    prompts++
                    promptFailure?.let { return failure(it) }
                }
            }
            registered = DERIVED
            return Result.success(AccountRegisteredPlcRecoveryKey("did:plc:alice123", "alice.logdate.app", DERIVED))
        }

        override suspend fun replaceRecoveryKey(recoveryPhrase: List<String>): Result<AccountRegisteredPlcRecoveryKey> = unused()

        override suspend fun recoverIdentity(recoveryPhrase: List<String>): Result<AccountImportedSigningKey> = unused()

        override suspend fun getHostedPlcOperations(): Result<List<AccountHostedPlcOperation>> = unused()

        override suspend fun exportSigningKey(passphrase: String): Result<AccountExportedSigningKey> = unused()

        override suspend fun rotateSigningKey(passphrase: String): Result<AccountRotatedSigningKey> = unused()

        override suspend fun importSigningKey(
            passphrase: String,
            exportedKeyJson: String,
        ): Result<AccountImportedSigningKey> = unused()

        override suspend fun importSigningKeyWithRecovery(
            passphrase: String,
            exportedKeyJson: String,
            recoveryPhrase: String,
        ): Result<AccountImportedSigningKey> = unused()

        private fun <T> failure(failure: IdentityRecoveryFailure): Result<T> =
            Result.failure(IdentityRecoveryException(failure, failure.name))

        private fun <T> unused(): Result<T> = error("not used by automatic account recovery")
    }

    private companion object {
        const val ACCOUNT = "account-123"
        const val DERIVED = "did:key:zDnaeDerived"
        val RETRY = 30.seconds
    }
}
