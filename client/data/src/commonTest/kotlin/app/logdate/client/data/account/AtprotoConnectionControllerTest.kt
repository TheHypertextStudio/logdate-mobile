package app.logdate.client.data.account

import app.logdate.client.device.auth.AtprotoConnectionBinding
import app.logdate.client.device.auth.AtprotoConnectionPendingAction
import app.logdate.client.device.auth.AtprotoConnectionPendingStore
import app.logdate.client.networking.atproto.AtprotoConnectionApi
import app.logdate.client.networking.atproto.AtprotoConnectionApiException
import app.logdate.client.networking.atproto.AtprotoConnectionApiFailure
import app.logdate.client.repository.account.AccountConnectionProblem
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectReceipt
import app.logdate.shared.model.AtprotoDisconnectResponse
import app.logdate.shared.model.BlueskyBrowserReturn
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtprotoConnectionControllerTest {
    @Test
    fun `expired reconnect is consumed only by connection handling and permits another explicit request`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            controller.reconnect().getOrThrow()
            f.api.completeFailure = AtprotoConnectionApiFailure.PROOF_EXPIRED
            assertTrue(controller.resume("${BlueskyBrowserReturn.URL}?attempt=$ID"))
            assertFalse(controller.state.value.waitingForBrowser)
            assertNull(f.pending.current(f.binding!!))
            assertEquals(0, f.passkeys)
            assertTrue(controller.reconnect().isSuccess)
        }

    @Test
    fun `definitive server refusal releases only its frozen proof and a later action needs a new passkey`() =
        runTest {
            for (failure in listOf(
                AtprotoConnectionApiFailure.PROOF_EXPIRED,
                AtprotoConnectionApiFailure.PROOF_INVALID,
                AtprotoConnectionApiFailure.CHANGED,
                AtprotoConnectionApiFailure.INELIGIBLE,
                AtprotoConnectionApiFailure.LAST_SIGN_IN_FACTOR,
            )) {
                val f = ConnectionWorld()
                val controller = f.controller()
                controller.refresh()
                controller.requestDisconnect()
                f.api.deleteFailure = failure
                controller.confirmDisconnect()
                assertFalse(controller.state.value.hasPendingDisconnect)
                assertNull(f.pending.current(f.binding!!))
                assertEquals(1, f.passkeys)
                f.api.deleteFailure = null
                controller.refresh()
                controller.requestDisconnect()
                controller.confirmDisconnect()
                assertEquals(2, f.passkeys)
                assertEquals(
                    AtprotoConnectionState.DISCONNECTED,
                    controller.state.value.snapshot
                        ?.state,
                )
            }
        }

    @Test
    fun `refresh is read only and disabled capability cannot present account actions`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            assertEquals(
                AtprotoConnectionState.CONNECTED,
                controller.state.value.snapshot
                    ?.state,
            )
            assertEquals(0, f.passkeys)
            f.enabled = false
            controller.refresh()
            controller.requestDisconnect()
            controller.confirmDisconnect()
            controller.reconnect()
            assertFalse(controller.state.value.available)
            assertEquals(0, f.api.begins + f.api.starts + f.passkeys)
        }

    @Test
    fun `older status leaves the complete current connection state unchanged`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            f.api.current = snapshot(revision = 5)
            controller.refresh()
            val current = controller.state.value
            f.api.current = snapshot(revision = 1)
            controller.refresh()
            assertEquals(current, controller.state.value)
            assertFalse(controller.state.value.observationFailed)
        }

    @Test
    fun `only explicit confirmation requests a fresh passkey and cancellation consumes no authorization`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            controller.requestDisconnect()
            assertTrue(controller.state.value.confirmingDisconnect)
            assertEquals(0, f.passkeys)
            controller.cancelDisconnectConfirmation()
            controller.confirmDisconnect()
            assertEquals(0, f.passkeys)
            controller.requestDisconnect()
            controller.confirmDisconnect()
            assertEquals(1, f.passkeys)
            assertEquals(1, f.api.deletes)
            assertEquals(
                AtprotoConnectionState.DISCONNECTED,
                controller.state.value.snapshot
                    ?.state,
            )
            assertTrue(controller.state.value.remoteRevocationUnconfirmed)
        }

    @Test
    fun `a changed revision while confirmation is open never targets the replacement connection`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            controller.requestDisconnect()
            f.api.current = snapshot(revision = 5)
            controller.refresh()
            controller.confirmDisconnect()
            assertEquals(0, f.passkeys + f.api.begins + f.api.deletes)
            assertEquals(AccountConnectionProblem.CHANGED, controller.state.value.problem)
        }

    @Test
    fun `lost disconnect response retries the frozen decision after restart without a second passkey`() =
        runTest {
            val f = ConnectionWorld()
            val first = f.controller()
            first.refresh()
            first.requestDisconnect()
            f.api.loseDelete = true
            first.confirmDisconnect()
            assertTrue(first.state.value.hasPendingDisconnect)
            assertEquals(1, f.passkeys)
            val restarted = f.controller()
            restarted.refresh()
            f.api.loseDelete = false
            restarted.requestDisconnect()
            restarted.confirmDisconnect()
            assertEquals(1, f.passkeys)
            assertEquals(1, f.api.begins)
            assertEquals(2, f.api.deletes)
            assertEquals(
                AtprotoConnectionState.DISCONNECTED,
                restarted.state.value.snapshot
                    ?.state,
            )
            assertNull(f.pending.current(f.binding!!))
        }

    @Test
    fun `reconnect persists a distinct proof before returning its URL and only claims its matching return`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            assertEquals("https://pds.example/authorize", controller.reconnect().getOrThrow())
            val saved = assertNotNull(f.pending.current(f.binding!!))
            val proof = assertIs<AtprotoConnectionPendingAction.Reconnect>(saved.action)
            assertEquals(ID, proof.attemptId)
            assertTrue(controller.state.value.waitingForBrowser)
            val restarted = f.controller()
            assertFalse(restarted.resume("${BlueskyBrowserReturn.URL}?attempt=${"B".repeat(43)}"))
            assertTrue(restarted.resume("${BlueskyBrowserReturn.URL}?attempt=$ID"))
            assertEquals(1, f.api.completions)
            assertEquals(0, f.passkeys)
            assertNull(f.pending.current(f.binding!!))
        }

    @Test
    fun `delayed state cannot cross backend account or grant ownership and missing session clears previous state`() =
        runTest {
            for (changed in listOf(
                BINDING.copy(backendOrigin = "https://staging.logdate.app"),
                BINDING.copy(accountId = OTHER_OWNER),
                BINDING.copy(authenticationGeneration = "D".repeat(43)),
            )) {
                val f = ConnectionWorld()
                val controller = f.controller()
                f.api.readGate = CompletableDeferred()
                val request = async { controller.refresh() }
                withTimeout(1000) { f.api.readEntered.await() }
                f.binding = changed
                f.api.readGate!!.complete(Unit)
                runCatching { request.await() }
                assertNull(controller.state.value.snapshot)
                assertFalse(controller.state.value.busy)
            }
            val f = ConnectionWorld()
            val controller = f.controller()
            controller.refresh()
            f.binding = null
            controller.refresh()
            assertNull(controller.state.value.snapshot)
            assertFalse(controller.state.value.available)
        }

    @Test
    fun `observation failure retains labeled last state while first party unauthorized renews only once`() =
        runTest {
            val f = ConnectionWorld()
            val controller = f.controller()
            f.api.unauthorizedOnce = true
            controller.refresh()
            assertEquals(listOf("initial-token", "renewed-token"), f.api.tokens)
            assertEquals(1, f.renewals)
            f.api.offline = true
            controller.refresh()
            assertTrue(controller.state.value.observationFailed)
            assertEquals(
                AtprotoConnectionState.CONNECTED,
                controller.state.value.snapshot
                    ?.state,
            )
            assertEquals(0, f.passkeys)
        }

    private class ConnectionWorld {
        var binding: AtprotoConnectionBinding? = BINDING
        var enabled = true
        var passkeys = 0
        var renewals = 0
        val api = ConnectionAPI()
        val pending = AtprotoConnectionPendingStore(RenewalStorage()) { NOW }

        fun controller() =
            AtprotoConnectionController(
                binding = { binding },
                enabled = { enabled },
                accessToken = { _, rejected ->
                    if (rejected != null) {
                        renewals += 1
                        "renewed-token"
                    } else {
                        "initial-token"
                    }
                },
                api = { api },
                pending = pending,
                nowMillis = { NOW },
                passkey = {
                    passkeys += 1
                    PasskeyAssertionResponse("AQ", "AQ", PasskeyAssertionAuthenticatorResponse("e30", "AQ", "AQ", ""))
                },
            )
    }

    private class ConnectionAPI : AtprotoConnectionApi {
        override val backendOrigin = BINDING.backendOrigin
        var current = snapshot()
        var begins = 0
        var deletes = 0
        var starts = 0
        var completions = 0
        var loseDelete = false
        var deleteFailure: AtprotoConnectionApiFailure? = null
        var completeFailure: AtprotoConnectionApiFailure? = null
        var unauthorizedOnce = false
        var offline = false
        val tokens = mutableListOf<String>()
        var readGate: CompletableDeferred<Unit>? = null
        val readEntered = CompletableDeferred<Unit>()

        override suspend fun snapshot(accessToken: String): Result<AtprotoConnectionSnapshot> {
            tokens += accessToken
            readEntered.complete(Unit)
            readGate?.await()
            if (unauthorizedOnce) {
                unauthorizedOnce = false
                return Result.failure(AtprotoConnectionApiException(AtprotoConnectionApiFailure.UNAUTHORIZED))
            }
            return if (offline) Result.failure(IllegalStateException("offline")) else Result.success(current)
        }

        override suspend fun beginDisconnect(
            accessToken: String,
            expectedRevision: Long,
        ): Result<AtprotoDisconnectChallenge> {
            begins += 1
            return Result.success(
                AtprotoDisconnectChallenge(
                    ID,
                    expectedRevision,
                    PasskeyAuthenticationOptions(ID, "logdate.app", allowCredentials = listOf("AQ")),
                    "2026-10-27T00:04:59Z",
                ),
            )
        }

        override suspend fun disconnect(
            accessToken: String,
            encodedProof: String,
        ): Result<AtprotoDisconnectResponse> {
            deletes += 1
            deleteFailure?.let { return Result.failure(AtprotoConnectionApiException(it)) }
            if (loseDelete) return Result.failure(IllegalStateException("lost response"))
            current = snapshot(AtprotoConnectionState.DISCONNECTED, 2)
            return Result.success(AtprotoDisconnectResponse(AtprotoDisconnectReceipt(ID, 2, current.observedAt, false), current))
        }

        override suspend fun startReconnect(
            accessToken: String,
            challenge: String,
        ): Result<BlueskyStartResponse> {
            starts += 1
            return Result.success(BlueskyStartResponse(ID, "https://pds.example/authorize"))
        }

        override suspend fun completeReconnect(
            accessToken: String,
            attemptId: String,
            verifier: String,
        ): Result<AtprotoConnectionSnapshot> {
            completions += 1
            completeFailure?.let { return Result.failure(AtprotoConnectionApiException(it)) }
            return Result.success(snapshot(revision = 3))
        }
    }

    private companion object {
        const val OTHER_OWNER = "00000000-0000-4000-8000-000000000002"
        const val NOW = 1793059200000L
        val ID = "A".repeat(43)
        val BINDING = AtprotoConnectionBinding("https://cloud.logdate.app", "00000000-0000-4000-8000-000000000001", "C".repeat(43))

        fun snapshot(
            state: AtprotoConnectionState = AtprotoConnectionState.CONNECTED,
            revision: Long = 1,
        ) = AtprotoConnectionSnapshot(state, revision, "2026-10-27T00:00:00Z", true, state == AtprotoConnectionState.CONNECTED)
    }
}
