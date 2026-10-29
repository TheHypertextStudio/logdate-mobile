package app.logdate.client.data.account

import app.logdate.client.device.auth.BlueskyAttemptStore
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.networking.bluesky.BlueskyApi
import app.logdate.client.networking.bluesky.BlueskyApiException
import app.logdate.client.networking.bluesky.BlueskyApiFailure
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.client.repository.account.LocalDataAdoptionRequiredException
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyBrowserReturn
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BlueskySignInControllerTest {
    @Test
    fun `browser destination is returned only after proof and original context are durable`() =
        runTest {
            val f = Fixture()
            assertTrue(f.controller().isAvailable())
            assertEquals("https://bsky.example/authorize", f.controller().start("alice.test").getOrThrow())
            val stored = assertNotNull(f.attempts.forReturn(RETURN))
            assertEquals(OWNER, stored.ownerId)
            assertEquals(ORIGIN, stored.backendOrigin)
            assertEquals(43, stored.verifier.length)
            assertEquals(
                43,
                f.api.startRequest
                    ?.challenge
                    ?.length,
            )
        }

    @Test
    fun `process restart and duplicate returns reuse durable enrollment without spending proof twice`() =
        runTest {
            val f = Fixture()
            f.controller().start("alice.test").getOrThrow()
            val resumed = f.controller()
            assertTrue(resumed.resume(RETURN))
            assertIs<BlueskySignInState.AccountDetails>(resumed.state.value)
            assertEquals(OWNER, f.enrollments.read()?.ownerId)
            assertNull(f.attempts.forReturn(RETURN))
            val restarted = f.controller()
            restarted.restore()
            assertIs<BlueskySignInState.AccountDetails>(restarted.state.value)
            assertTrue(restarted.resume(RETURN))
            assertEquals(1, f.api.exchangeCalls)
        }

    @Test
    fun `changed owner or server cannot exchange original browser proof`() =
        runTest {
            for (changeOwner in listOf(true, false)) {
                val f = Fixture()
                f.controller().start("alice.test").getOrThrow()
                if (changeOwner) f.owner = OTHER_OWNER else f.origin = "https://other.logdate.app"
                val controller = f.controller()
                assertTrue(controller.resume(RETURN))
                assertIs<BlueskySignInState.Failed>(controller.state.value)
                assertEquals(0, f.api.exchangeCalls)
                assertNull(f.enrollments.read())
            }
        }

    @Test
    fun `context changed while exchange is in flight cannot install an enrollment`() =
        runTest {
            val f = Fixture()
            f.controller().start("alice.test").getOrThrow()
            f.api.beforeExchange = { f.owner = OTHER_OWNER }
            val controller = f.controller()
            assertTrue(controller.resume(RETURN))
            assertIs<BlueskySignInState.Failed>(controller.state.value)
            assertNull(f.enrollments.read())
            assertNotNull(f.attempts.forReturn(RETURN))
        }

    @Test
    fun `unrelated links are ignored and disabled sign in never contacts provider`() =
        runTest {
            val f = Fixture()
            f.enabled = false
            val controller = f.controller()
            assertFalse(controller.resume("https://attacker.example/?attempt=$ATTEMPT"))
            assertTrue(controller.start("alice.test").isFailure)
            assertNull(f.api.startRequest)
        }

    @Test
    fun `temporary exchange failure and coroutine cancellation retain the browser proof`() =
        runTest {
            val f = Fixture()
            f.controller().start("alice.test").getOrThrow()
            f.api.beforeExchange = { throw IllegalStateException("private-provider-detail") }
            val controller = f.controller()
            assertTrue(controller.resume(RETURN))
            assertIs<BlueskySignInState.Failed>(controller.state.value)
            assertFalse(
                controller.state.value
                    .toString()
                    .contains("private-provider-detail"),
            )
            assertNotNull(f.attempts.forReturn(RETURN))
            f.api.beforeExchange = { throw CancellationException() }
            assertFailsWith<CancellationException> { controller.resume(RETURN) }
            assertNotNull(f.attempts.forReturn(RETURN))
        }

    @Test
    fun `owner changed during secure proof persistence cannot return a browser destination`() =
        runTest {
            val f = Fixture()
            f.afterStorageWrite = { f.owner = OTHER_OWNER }
            assertTrue(f.controller().start("alice.test").isFailure)
        }

    @Test
    fun `only an explicit passkey action delegates session acceptance and clears enrollment`() =
        runTest {
            val f = Fixture()
            val controller = f.controller()
            controller.start("alice.test").getOrThrow()
            controller.resume(RETURN)
            assertEquals(0, f.registrationCalls)
            controller.createAccount(AccountCreationRequest("alice", "Alice"))
            assertEquals(1, f.registrationCalls)
            assertIs<BlueskySignInState.Complete>(controller.state.value)
            assertNull(f.enrollments.read())
            assertNotNull(f.ceremony)
        }

    @Test
    fun `a resumed registration exposes the saved profile without spending its grant again`() =
        runTest {
            val f = Fixture()
            val initial =
                f.enrollments.begin(
                    ATTEMPT,
                    ORIGIN,
                    BlueskyExchangeResponse("t".repeat(43), BlueskyAccountNextStep.CREATE_ACCOUNT),
                    OWNER,
                )
            f.enrollments.advance(
                initial,
                BlueskyEnrollmentStep.Registration(
                    "u".repeat(43),
                    BlueskyEnrollmentProfile(OWNER, "alice", "Alice", "saved biography"),
                    PasskeyRegistrationOptions("challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice")),
                ),
            )
            val controller = f.controller()
            controller.restore()
            val details = assertIs<BlueskySignInState.AccountDetails>(controller.state.value)
            assertEquals(AccountCreationRequest("alice", "Alice", "saved biography"), details.savedRequest)
            assertFalse(details.toString().contains("saved biography"))
            assertEquals(0, f.api.exchangeCalls)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancel interrupts an active passkey action before it can accept a session`() =
        runTest {
            val f = Fixture()
            val response = CompletableDeferred<LogDateAccount>()
            f.registerAction = { Result.success(response.await()) }
            val controller = f.controller()
            controller.start("alice.test").getOrThrow()
            controller.resume(RETURN)
            val signIn = launch { controller.createAccount(AccountCreationRequest("alice", "Alice")) }
            runCurrent()
            val cancel = launch { controller.cancel() }
            runCurrent()
            try {
                assertTrue(signIn.isCancelled, "Cancel must interrupt the guarded passkey workflow")
            } finally {
                response.complete(LogDateAccount(id = Uuid.parse(OWNER), username = "alice", displayName = "Alice"))
                signIn.join()
                cancel.join()
            }
            assertIs<BlueskySignInState.Idle>(controller.state.value)
            assertNull(f.enrollments.read())
        }

    @Test
    fun `declined provider authorization clears only that browser attempt`() =
        runTest {
            val f = Fixture()
            val controller = f.controller()
            controller.start("alice.test").getOrThrow()
            f.api.beforeExchange = { throw BlueskyApiException(BlueskyApiFailure.CANCELED) }
            controller.resume(RETURN)
            assertNull(f.attempts.current())
            val restarted = f.controller()
            restarted.restore()
            assertIs<BlueskySignInState.Idle>(restarted.state.value)
        }

    @Test
    fun `key approval or late consent after verified completion cannot reuse a spent grant`() =
        runTest {
            for (failure in listOf(AccountKeysNotReadyException(), LocalDataAdoptionRequiredException())) {
                val f = Fixture()
                f.registerAction = { ceremony ->
                    val begin =
                        ceremony
                            .beginRegistration(
                                BeginAccountCreationRequest("alice", "Alice", requestedOwnerId = OWNER),
                            ).getOrThrow()
                    val credential = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager().registerPasskeyResponse.getOrThrow()
                    ceremony
                        .completeRegistration(
                            CompleteAccountCreationRequest(
                                begin.sessionToken,
                                PasskeyCredentialCodec(Json { ignoreUnknownKeys = true }).parseCredentialResponse(credential),
                            ),
                        ).getOrThrow()
                    Result.failure(failure)
                }
                val controller = f.controller()
                controller.start("alice.test").getOrThrow()
                controller.resume(RETURN)
                controller.createAccount(AccountCreationRequest("alice", "Alice"))
                assertNull(f.enrollments.read(), "The server has consumed this grant")
                if (failure is AccountKeysNotReadyException) {
                    assertTrue(assertIs<BlueskySignInState.DeviceApproval>(controller.state.value).requiresNewSignIn)
                } else {
                    assertTrue(assertIs<BlueskySignInState.LocalDataConsent>(controller.state.value).requiresNewSignIn)
                }
                val restarted = f.controller()
                restarted.restore()
                assertIs<BlueskySignInState.Idle>(restarted.state.value)
            }
        }

    @Test
    fun `an interrupted one-use completion is never restored as a retryable passkey grant`() =
        runTest {
            val f = Fixture()
            f.registerAction = { ceremony ->
                val begin = ceremony.beginRegistration(BeginAccountCreationRequest("alice", "Alice", requestedOwnerId = OWNER)).getOrThrow()
                val credential = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager().registerPasskeyResponse.getOrThrow()
                ceremony
                    .completeRegistration(
                        CompleteAccountCreationRequest(
                            begin.sessionToken,
                            PasskeyCredentialCodec(Json { ignoreUnknownKeys = true }).parseCredentialResponse(credential),
                        ),
                    ).getOrThrow()
                error("Completion should be interrupted")
            }
            f.api.beforeRegistrationComplete = {
                assertEquals(true, f.enrollments.read()?.completionStarted)
                throw CancellationException()
            }
            val controller = f.controller()
            controller.start("alice.test").getOrThrow()
            controller.resume(RETURN)
            assertFailsWith<CancellationException> { controller.createAccount(AccountCreationRequest("alice", "Alice")) }
            val restarted = f.controller()
            restarted.restore()
            assertIs<BlueskySignInState.Failed>(restarted.state.value)
            assertNull(f.enrollments.read())
        }

    @Test
    fun `stale exchange cannot replace a newer browser attempt`() =
        runTest {
            val f = Fixture()
            val controller = f.controller()
            controller.start("alice.test").getOrThrow()
            f.api.beforeExchange = {
                f.attempts.remember("b".repeat(43), "v".repeat(43), ORIGIN, OWNER)
            }
            controller.resume(RETURN)
            assertIs<BlueskySignInState.Failed>(controller.state.value)
            assertNull(f.enrollments.read())
            assertEquals("b".repeat(43), f.attempts.current()?.attemptId)
        }

    private class Fixture {
        var origin = ORIGIN
        var owner = OWNER
        var enabled = true
        var afterStorageWrite: () -> Unit = {}
        private val delegate = RenewalStorage()
        val storage =
            object : SecureStorage by delegate {
                override suspend fun putString(
                    key: String,
                    value: String,
                ) {
                    delegate.putString(key, value)
                    afterStorageWrite()
                }
            }
        var registerAction: suspend (PasskeyCeremonyApi) -> Result<LogDateAccount> = {
            Result.success(LogDateAccount(id = Uuid.parse(OWNER), username = "alice", displayName = "Alice"))
        }
        var registrationCalls = 0
        var ceremony: PasskeyCeremonyApi? = null
        val attempts = BlueskyAttemptStore(storage) { 1000 }
        val enrollments = BlueskyEnrollmentStore(storage) { 1000 }
        val api = Api()

        fun controller() =
            BlueskySignInController(
                { origin },
                { owner },
                { enabled },
                attempts,
                enrollments,
                { api },
                { _, supplied ->
                    registrationCalls++
                    ceremony = supplied
                    registerAction(supplied)
                },
                { _, _ -> error("No passkey requested") },
            )
    }

    private class Api : BlueskyApi {
        override val backendOrigin = ORIGIN
        var startRequest: BlueskyStartRequest? = null
        var exchangeCalls = 0
        var beforeExchange: suspend () -> Unit = {}
        var beforeRegistrationComplete: suspend () -> Unit = {}

        override suspend fun start(request: BlueskyStartRequest): Result<BlueskyStartResponse> {
            startRequest = request
            return Result.success(BlueskyStartResponse(ATTEMPT, "https://bsky.example/authorize"))
        }

        override suspend fun exchange(request: BlueskyExchangeRequest): Result<BlueskyExchangeResponse> {
            exchangeCalls++
            beforeExchange()
            return Result.success(BlueskyExchangeResponse("t".repeat(43), BlueskyAccountNextStep.CREATE_ACCOUNT))
        }

        override suspend fun beginRegistration(request: BlueskyRegistrationBeginRequest): Result<BlueskyRegistrationBeginResponse> =
            Result.success(
                BlueskyRegistrationBeginResponse(
                    "u".repeat(43),
                    PasskeyRegistrationOptions("challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice")),
                ),
            )

        override suspend fun completeRegistration(request: BlueskyRegistrationCompleteRequest): Result<CompleteAuthenticationData> {
            beforeRegistrationComplete()
            return Result.success(
                CompleteAuthenticationData(
                    LogDateAccount(
                        id = Uuid.parse(OWNER),
                        username = "alice",
                        displayName = "Alice",
                        did = "did:plc:external",
                        storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                        identityKind = AccountIdentityKind.EXTERNAL,
                    ),
                    AccountTokens("access", "refresh"),
                ),
            )
        }

        override suspend fun beginAuthentication(request: BlueskyAuthenticationBeginRequest): Result<BlueskyAuthenticationBeginResponse> =
            error("Unused")

        override suspend fun completeAuthentication(request: BlueskyAuthenticationCompleteRequest): Result<CompleteAuthenticationData> =
            error("Unused")
    }

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        const val OTHER_OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe8"
        val ATTEMPT = "a".repeat(43)
        val RETURN = "${BlueskyBrowserReturn.URL}?attempt=$ATTEMPT"
    }
}
