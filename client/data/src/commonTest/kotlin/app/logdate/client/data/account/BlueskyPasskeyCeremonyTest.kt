package app.logdate.client.data.account

import app.logdate.client.networking.bluesky.BlueskyApi
import app.logdate.client.networking.bluesky.BlueskyApiException
import app.logdate.client.networking.bluesky.BlueskyApiFailure
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class BlueskyPasskeyCeremonyTest {
    @Test
    fun `registration advances securely before the platform ceremony and preserves owner and credential`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val api = Api()
            val ceremony = BlueskyPasskeyCeremony(initial, store, api)
            val begin = ceremony.beginRegistration(PROFILE).getOrThrow()
            assertEquals(OWNER, api.registrationBegin?.requestedOwnerId)
            assertEquals(NEXT_TOKEN, begin.sessionToken)
            assertIs<BlueskyEnrollmentStep.Registration>(store.read()?.step)
            val credential = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager().registerPasskeyResponse.getOrThrow()
            val complete =
                ceremony
                    .completeRegistration(
                        CompleteAccountCreationRequest(
                            begin.sessionToken,
                            PasskeyCredentialCodec(
                                kotlinx.serialization.json.Json {
                                    ignoreUnknownKeys =
                                        true
                                },
                            ).parseCredentialResponse(credential),
                            "device",
                        ),
                    ).getOrThrow()
            assertEquals(OWNER, complete.account.id.toString())
            assertEquals("credential123", api.registrationComplete?.credential?.id)
            assertEquals("device", api.registrationComplete?.nickname)
            assertNull(complete.stepUpGrant)
        }

    @Test
    fun `registration canonicalizes only the wire owner and retains original local spelling`() =
        runTest {
            val original = OWNER.uppercase()
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), original)
            val api = Api()
            BlueskyPasskeyCeremony(initial, store, api).beginRegistration(PROFILE.copy(requestedOwnerId = original)).getOrThrow()
            assertEquals(OWNER, api.registrationBegin?.requestedOwnerId)
            assertEquals(original, assertIs<BlueskyEnrollmentStep.Registration>(store.read()?.step).profile.ownerId)
        }

    @Test
    fun `restarted registration uses the persisted challenge without spending the original grant again`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val first = Api()
            BlueskyPasskeyCeremony(initial, store, first).beginRegistration(PROFILE).getOrThrow()
            val restarted = Api()
            val stored = assertNotNull(store.read())
            val begin = BlueskyPasskeyCeremony(stored, store, restarted).beginRegistration(PROFILE).getOrThrow()
            assertEquals(NEXT_TOKEN, begin.sessionToken)
            assertNull(restarted.registrationBegin)
        }

    @Test
    fun `authentication maps only the public passkey assertion and its bound challenge`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CONFIRM_PASSKEY), OWNER)
            val api = Api()
            val ceremony = BlueskyPasskeyCeremony(initial, store, api)
            val begin = ceremony.beginAuthentication(BeginAuthenticationRequest()).getOrThrow()
            assertEquals("challenge", begin.challenge)
            assertEquals("credential123", begin.allowCredentials.single().id)
            val assertion = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager().authenticateWithPasskeyResponse.getOrThrow()
            ceremony
                .completeAuthentication(
                    CompleteAuthenticationRequest(
                        PasskeyCredentialCodec(
                            kotlinx.serialization.json.Json {
                                ignoreUnknownKeys =
                                    true
                            },
                        ).parseAssertionResponse(assertion),
                        begin.challenge,
                    ),
                ).getOrThrow()
            assertEquals(NEXT_TOKEN, api.authenticationComplete?.continuationToken)
            assertEquals("credential123", api.authenticationComplete?.credential?.id)
        }

    @Test
    fun `a stale ceremony or changed registration profile cannot finish`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val api = Api()
            val ceremony = BlueskyPasskeyCeremony(initial, store, api)
            ceremony.beginRegistration(PROFILE).getOrThrow()
            val stored = assertNotNull(store.read())
            val changed = BlueskyPasskeyCeremony(stored, store, api).beginRegistration(PROFILE.copy(displayName = "Someone else"))
            assertEquals(BlueskyApiFailure.INVALID_REQUEST, assertIs<BlueskyApiException>(changed.exceptionOrNull()).kind)
            store.clear(ATTEMPT)
            val stale = ceremony.beginRegistration(PROFILE)
            assertEquals(BlueskyApiFailure.EXPIRED, assertIs<BlueskyApiException>(stale.exceptionOrNull()).kind)
        }

    @Test
    fun `a continuation cannot be attached to a client for another backend`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val api = Api().apply { backendOrigin = "https://staging.logdate.app" }
            val failure = assertFailsWith<BlueskyApiException> { BlueskyPasskeyCeremony(initial, store, api) }
            assertEquals(BlueskyApiFailure.EXPIRED, failure.kind)
        }

    private class Api : BlueskyApi {
        override var backendOrigin: String = ORIGIN
        var registrationBegin: BlueskyRegistrationBeginRequest? = null
        var registrationComplete: BlueskyRegistrationCompleteRequest? = null
        var authenticationComplete: BlueskyAuthenticationCompleteRequest? = null

        override suspend fun start(request: BlueskyStartRequest): Result<BlueskyStartResponse> = error("No provider operation")

        override suspend fun exchange(request: BlueskyExchangeRequest): Result<BlueskyExchangeResponse> = error("No provider operation")

        override suspend fun beginRegistration(request: BlueskyRegistrationBeginRequest): Result<BlueskyRegistrationBeginResponse> {
            registrationBegin = request
            return Result.success(
                BlueskyRegistrationBeginResponse(
                    NEXT_TOKEN,
                    PasskeyRegistrationOptions("challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice")),
                ),
            )
        }

        override suspend fun completeRegistration(request: BlueskyRegistrationCompleteRequest): Result<CompleteAuthenticationData> {
            registrationComplete = request
            return Result.success(SESSION)
        }

        override suspend fun beginAuthentication(request: BlueskyAuthenticationBeginRequest): Result<BlueskyAuthenticationBeginResponse> =
            Result.success(
                BlueskyAuthenticationBeginResponse(
                    NEXT_TOKEN,
                    PasskeyAuthenticationOptions("challenge", "logdate.app", listOf("credential123")),
                ),
            )

        override suspend fun completeAuthentication(request: BlueskyAuthenticationCompleteRequest): Result<CompleteAuthenticationData> {
            authenticationComplete = request
            return Result.success(SESSION)
        }
    }

    private companion object {
        val ATTEMPT = "a".repeat(43)
        val TOKEN = "b".repeat(43)
        val NEXT_TOKEN = "c".repeat(43)
        const val ORIGIN = "https://cloud.logdate.app"
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        val PROFILE = BeginAccountCreationRequest("alice", "Alice", requestedOwnerId = OWNER)
        val SESSION =
            CompleteAuthenticationData(
                LogDateAccount(
                    id = Uuid.parse(OWNER),
                    username = "alice",
                    displayName = "Alice",
                    did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                ),
                AccountTokens("access", "refresh"),
            )
    }
}
