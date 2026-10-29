package app.logdate.client.data.account

import app.logdate.client.datastore.SessionInstallationGuard
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.networking.PasskeyApiClientContract
import app.logdate.client.permissions.PasskeyAuthenticationResult
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.LocalDataAdoptionRequiredException
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAllowCredential
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PasskeyCeremonyIntegrationTest {
    @Test
    fun `external registration uses the chosen ceremony but keeps owner and session bookkeeping`() =
        runTest {
            val fixture = Fixture()
            val result = fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api)
            assertEquals(fixture.account, result.getOrThrow())
            assertEquals(OWNER, fixture.api.registrationRequest?.requestedOwnerId)
            assertEquals("device-name", fixture.api.registrationCompletion?.nickname)
            assertEquals("registration-secret", fixture.api.registrationCompletion?.sessionToken)
            assertEquals(OWNER, fixture.sessions.getSession()?.accountId)
            assertEquals(fixture.account, fixture.state.account)
            assertEquals(1, fixture.platform.addAccountCalls)
        }

    @Test
    fun `a resumed ceremony cannot start under a different local owner`() =
        runTest {
            for (creating in listOf(true, false)) {
                val fixture = Fixture()
                fixture.api.originalOwnerId = Uuid.random().toString()
                val result =
                    if (creating) {
                        fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api)
                    } else {
                        fixture.coordinator.authenticateWithPasskey(null, false, fixture.api)
                    }
                assertTrue(result.isFailure)
                assertEquals(0, fixture.api.begins)
                assertNull(fixture.sessions.getSession())
            }
        }

    @Test
    fun `external sign-in uses the existing owner guard and session bookkeeping`() =
        runTest {
            val fixture = Fixture()
            val result = fixture.coordinator.authenticateWithPasskey(null, false, fixture.api)
            assertEquals(fixture.account, result.getOrThrow())
            assertEquals("authentication-challenge", fixture.api.authenticationCompletion?.challenge)
            assertEquals(
                "credential123",
                fixture.api.authenticationCompletion
                    ?.credential
                    ?.id,
            )
            assertEquals(OWNER, fixture.sessions.getSession()?.accountId)
            assertEquals(fixture.account, fixture.state.account)
            assertEquals(1, fixture.platformUpdates)
        }

    @Test
    fun `registration cannot substitute another owner`() =
        runTest {
            val fixture = Fixture()
            fixture.api.returnedAccount = fixture.account.copy(id = Uuid.random())
            assertTrue(fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api).isFailure)
            assertNull(fixture.sessions.getSession())
            assertNull(fixture.state.account)
        }

    @Test
    fun `sign-in cannot replace a bound different owner`() =
        runTest {
            val fixture = Fixture()
            fixture.api.returnedAccount = fixture.account.copy(id = Uuid.random())
            assertIs<CanonicalOwnerMismatchException>(
                fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).exceptionOrNull(),
            )
            assertNull(fixture.sessions.getSession())
            assertNull(fixture.state.account)
        }

    @Test
    fun `offline entries need consent before an external account adopts them`() =
        runTest {
            val fixture = Fixture(bound = false, hasLocalData = true)
            assertIs<LocalDataAdoptionRequiredException>(
                fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).exceptionOrNull(),
            )
            assertEquals(0, fixture.api.begins, "Consent must precede a one-use passkey ceremony")
            assertNull(fixture.sessions.getSession())
            assertNull(fixture.state.account)
        }

    @Test
    fun `a ceremony for another server is refused before any passkey or network step`() =
        runTest {
            val fixture = Fixture()
            fixture.api.backendOrigin = "https://staging.logdate.app"
            assertTrue(fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api).isFailure)
            assertEquals(0, fixture.api.begins)
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `changing the backend after a challenge prevents the platform prompt`() =
        runTest {
            val fixture = Fixture()
            fixture.api.onBegin = { fixture.config.updateBackendUrl("https://staging.logdate.app") }
            assertTrue(fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api).isFailure)
            assertEquals(0, fixture.passkeys.registerCalls)
            assertNull(fixture.api.registrationCompletion)
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `changing the backend after authentication challenge prevents the platform prompt`() =
        runTest {
            val fixture = Fixture()
            fixture.api.onBegin = { fixture.config.updateBackendUrl("https://staging.logdate.app") }
            assertTrue(fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).isFailure)
            assertEquals(0, fixture.authenticationPrompts)
            assertNull(fixture.api.authenticationCompletion)
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `a later identity decision invalidates the challenge before prompting`() =
        runTest {
            val fixture = Fixture(guarded = true)
            fixture.api.onBegin = { fixture.guardCurrent = false }
            assertTrue(fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api).isFailure)
            assertEquals(0, fixture.passkeys.registerCalls)
            assertNull(fixture.api.registrationCompletion)
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `the local passkey unlock secret is cleared after external authentication`() =
        runTest {
            val fixture = Fixture()
            fixture.unlockSecret = ByteArray(32) { 42 }
            assertTrue(fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).isSuccess)
            assertTrue(fixture.unlockSecret!!.all { it == 0.toByte() })
        }

    @Test
    fun `missing account keys cannot adopt offline ownership or install a personal-space session`() =
        runTest {
            val fixture = Fixture(bound = false, hasLocalData = true)
            fixture.api.returnedAccount = fixture.account.copy(id = Uuid.random())
            fixture.keys.status = AccountKeyUnlockStatus.APPROVAL_REQUIRED
            fixture.keys.locallyReady = false
            val failure = fixture.coordinator.authenticateWithPasskey(null, true, fixture.api).exceptionOrNull()
            assertIs<AccountKeysNotReadyException>(failure)
            assertEquals(OWNER, fixture.ownerId)
            assertEquals(0, fixture.adoptions)
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `personal-space sign-in refuses a missing key-access coordinator`() =
        runTest {
            val fixture = Fixture(provideKeyAccess = false)
            assertIs<AccountKeysNotReadyException>(fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).exceptionOrNull())
            assertNull(fixture.sessions.getSession())
        }

    @Test
    fun `new personal-space account requires its retained local keys before publishing a session`() =
        runTest {
            val fixture = Fixture()
            fixture.keys.bindNew = false
            assertIs<AccountKeysNotReadyException>(
                fixture.coordinator.createAccountWithPasskey(AccountCreationRequest("alice", "Alice"), fixture.api).exceptionOrNull(),
            )
            assertNull(fixture.sessions.getSession())
            assertNull(fixture.state.account)
        }

    @Test
    fun `an already verified local account key remains usable when a passkey has no PRF`() =
        runTest {
            val fixture = Fixture()
            fixture.keys.status = AccountKeyUnlockStatus.APPROVAL_REQUIRED
            fixture.keys.locallyReady = true
            assertTrue(fixture.coordinator.authenticateWithPasskey(null, false, fixture.api).isSuccess)
            assertEquals(OWNER, fixture.sessions.getSession()?.accountId)
        }

    private class Fixture(
        bound: Boolean = true,
        hasLocalData: Boolean = false,
        guarded: Boolean = false,
        provideKeyAccess: Boolean = true,
    ) {
        val account =
            LogDateAccount(
                id = Uuid.parse(OWNER),
                username = "alice",
                displayName = "Alice",
                did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
                identityKind = AccountIdentityKind.EXTERNAL,
                storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            )
        val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
        private val baseSessions = DefaultPasskeyAccountRepositoryTest.FakeSessionStorage()
        var guardCurrent = true
        val sessions =
            object : SessionStorage by baseSessions {
                override val supportsGuardedAuthentication = guarded

                override suspend fun captureAuthenticationGuard(): SessionInstallationGuard? =
                    if (guarded) SessionInstallationGuard(config.getCurrentBackendUrl(), 0, "guard") else null

                override suspend fun isAuthenticationGuardCurrent(expected: SessionInstallationGuard): Boolean = guardCurrent

                override suspend fun installExplicitSessionIfCurrent(
                    expected: SessionInstallationGuard,
                    session: UserSession,
                ): Boolean {
                    if (!guardCurrent) return false
                    baseSessions.saveSession(session)
                    return true
                }
            }
        val passkeys = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager()
        var authenticationPrompts = 0
        var unlockSecret: ByteArray? = null
        val platform = DefaultPasskeyAccountRepositoryTest.FakePlatformAccountManager()
        val state = PasskeyAccountSessionState()
        val api = Ceremony(config.getCurrentBackendUrl(), account)
        var platformUpdates = 0
        val keys = Keys()
        var ownerId = OWNER
        var adoptions = 0
        private var ownerBound = bound
        private val owner =
            object : CanonicalOwnerProvider {
                override suspend fun getCanonicalOwnerId(): String = ownerId

                override suspend fun hasBoundOwner(): Boolean = ownerBound

                override suspend fun adoptRemoteOwnerIfUninitialized(remoteOwnerId: String): Boolean {
                    adoptions++
                    if (ownerBound && remoteOwnerId != ownerId) return false
                    ownerId = remoteOwnerId
                    ownerBound = true
                    return true
                }
            }
        private val ordinaryApi =
            object : PasskeyApiClientContract by DefaultPasskeyAccountRepositoryTest().FakePasskeyApiClient() {
                override suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
                    Result.failure(IllegalStateException("Wrong registration API"))

                override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
                    Result.failure(IllegalStateException("Wrong authentication API"))
            }
        val coordinator =
            PasskeyRegistrationCoordinator(
                apiClient = ordinaryApi,
                passkeyManager =
                    object : PasskeyManager by passkeys {
                        override suspend fun authenticateWithUnlock(
                            options: PasskeyAuthenticationOptions,
                        ): Result<PasskeyAuthenticationResult> {
                            authenticationPrompts++
                            return passkeys.authenticateWithPasskey(options).map { PasskeyAuthenticationResult(it, unlockSecret) }
                        }
                    },
                sessionStorage = sessions,
                platformAccountManager = platform,
                configRepository = config,
                canonicalOwnerProvider = owner,
                bindingGuard = CanonicalOwnerBindingGuard(owner, { hasLocalData }, config),
                credentialCodec = PasskeyCredentialCodec(Json { ignoreUnknownKeys = true }),
                sessionState = state,
                createRestoreKey = { Result.success(Unit) },
                updateTokensOrRegisterPlatformAccount = { _, _, _, _ -> platformUpdates++ },
                deviceName = { "device-name" },
                accountKeyUnlockCoordinator = if (provideKeyAccess) keys else null,
            )
    }

    private class Keys : AccountKeyAccess {
        var status = AccountKeyUnlockStatus.UNLOCKED
        var locallyReady = true
        var bindNew = true

        override suspend fun hasLocalAccountKeys(accountId: String): Boolean = locallyReady

        override suspend fun bindLocalKeysForNewAccount(accountId: String): Boolean = bindNew

        override suspend fun unlockOrProvision(
            apiBaseUrl: String,
            accountId: String,
            accessToken: String,
            credentialId: String,
            unlockSecret: ByteArray?,
            mayPublishLocalKeys: Boolean,
        ): AccountKeyUnlockStatus = status
    }

    private class Ceremony(
        override var backendOrigin: String,
        var returnedAccount: LogDateAccount,
    ) : PasskeyCeremonyApi {
        override var originalOwnerId: String? = null
        var onBegin: suspend () -> Unit = {}
        var begins = 0
        var registrationRequest: BeginAccountCreationRequest? = null
        var registrationCompletion: CompleteAccountCreationRequest? = null
        var authenticationCompletion: CompleteAuthenticationRequest? = null

        override suspend fun beginRegistration(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> {
            begins++
            onBegin()
            registrationRequest = request
            return Result.success(
                BeginAccountCreationData(
                    "registration-secret",
                    PasskeyRegistrationOptions("registration-challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice")),
                ),
            )
        }

        override suspend fun completeRegistration(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData> {
            registrationCompletion = request
            return Result.success(CompleteAccountCreationData(returnedAccount, TOKENS))
        }

        override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> {
            begins++
            onBegin()
            return Result.success(
                BeginAuthenticationData(
                    "authentication-challenge",
                    "logdate.app",
                    listOf(PasskeyAllowCredential(id = "credential123")),
                    300000,
                    "required",
                ),
            )
        }

        override suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> {
            authenticationCompletion = request
            return Result.success(CompleteAuthenticationData(returnedAccount, TOKENS))
        }
    }

    private companion object {
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        val TOKENS = AccountTokens("access", "refresh")
    }
}
