package app.logdate.client.data.account

import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlueskyEnrollmentStoreTest {
    @Test
    fun `restarting retains the original backend and one-use identity grant`() =
        runTest {
            val storage = RenewalStorage()
            BlueskyEnrollmentStore(
                storage,
            ) { 1000 }.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val restored = assertNotNull(BlueskyEnrollmentStore(storage) { 2000 }.read())
            assertEquals(ORIGIN, restored.backendOrigin)
            assertEquals(OWNER, restored.ownerId)
            assertEquals(ATTEMPT, restored.attemptId)
            assertEquals(TOKEN, assertIs<BlueskyEnrollmentStep.Identity>(restored.step).token)
            assertFalse(restored.toString().contains(TOKEN))
            assertFalse(restored.step.toString().contains(TOKEN))
        }

    @Test
    fun `passkey registration retains its options and original owner across process restart`() =
        runTest {
            val storage = RenewalStorage()
            val store = BlueskyEnrollmentStore(storage) { 1000 }
            val identity = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            val profile = BlueskyEnrollmentProfile(OWNER, "alice", "Alice", "private-profile-marker")
            val options = PasskeyRegistrationOptions("challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice"))
            assertNotNull(store.advance(identity, BlueskyEnrollmentStep.Registration(NEXT_TOKEN, profile, options)))
            val resumed = assertNotNull(BlueskyEnrollmentStore(storage) { 2000 }.read())
            assertEquals(OWNER, resumed.ownerId)
            val restored = assertIs<BlueskyEnrollmentStep.Registration>(resumed.step)
            assertEquals(OWNER, restored.profile.ownerId)
            assertEquals("challenge", restored.options.challenge)
            assertEquals(NEXT_TOKEN, restored.token)
            assertFalse(restored.profile.toString().contains("private-profile-marker"))
        }

    @Test
    fun `a stale response or cancellation cannot replace or clear a newer attempt`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val old = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CONFIRM_PASSKEY), OWNER)
            store.begin(OTHER_ATTEMPT, ORIGIN, BlueskyExchangeResponse(NEXT_TOKEN, BlueskyAccountNextStep.CONFIRM_PASSKEY), OWNER)
            assertNull(
                store.advance(old, BlueskyEnrollmentStep.Authentication(TOKEN, PasskeyAuthenticationOptions("challenge", "logdate.app"))),
            )
            store.clear(ATTEMPT)
            assertEquals(OTHER_ATTEMPT, store.read()?.attemptId)
            store.clear(OTHER_ATTEMPT)
            assertNull(store.read())
        }

    @Test
    fun `expired grants cannot be read or advanced and the next step cannot change account action`() =
        runTest {
            val storage = RenewalStorage()
            val store = BlueskyEnrollmentStore(storage) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            assertNull(
                store.advance(
                    initial,
                    BlueskyEnrollmentStep.Authentication(NEXT_TOKEN, PasskeyAuthenticationOptions("challenge", "logdate.app")),
                ),
            )
            val expired = BlueskyEnrollmentStore(storage) { 301000 }
            assertNull(expired.read())
            assertNull(expired.advance(initial, registration()))
        }

    @Test
    fun `silent secure-storage failures cannot claim a durable enrollment`() =
        runTest {
            val storage =
                object : SecureStorage by RenewalStorage() {
                    override suspend fun putString(
                        key: String,
                        value: String,
                    ) = Unit
                }
            val store = BlueskyEnrollmentStore(storage) { 1000 }
            assertFailsWith<IllegalStateException> {
                store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            }
            assertNull(store.read())
        }

    @Test
    fun `only the exact current stage can finish even when the public attempt is unchanged`() =
        runTest {
            val store = BlueskyEnrollmentStore(RenewalStorage()) { 1000 }
            val identity = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            assertEquals(true, store.isCurrent(identity))
            val next = assertNotNull(store.advance(identity, registration()))
            assertFalse(store.isCurrent(identity))
            assertEquals(true, store.isCurrent(next))
            store.clear(ATTEMPT)
            assertFalse(store.isCurrent(next))
        }

    @Test
    fun `completion is claimed durably before a one-use request and cannot be claimed twice`() =
        runTest {
            val storage = RenewalStorage()
            val store = BlueskyEnrollmentStore(storage) { 1000 }
            val initial = store.begin(ATTEMPT, ORIGIN, BlueskyExchangeResponse(TOKEN, BlueskyAccountNextStep.CREATE_ACCOUNT), OWNER)
            assertNull(store.claimCompletion(initial))
            val registration = assertNotNull(store.advance(initial, registration()))
            val claimed = assertNotNull(store.claimCompletion(registration))
            assertEquals(true, claimed.completionStarted)
            assertEquals(true, BlueskyEnrollmentStore(storage) { 2000 }.read()?.completionStarted)
            assertNull(store.claimCompletion(registration))
            assertNull(store.claimCompletion(claimed))
        }

    private fun registration(): BlueskyEnrollmentStep.Registration =
        BlueskyEnrollmentStep.Registration(
            NEXT_TOKEN,
            BlueskyEnrollmentProfile(OWNER, "alice", "Alice"),
            PasskeyRegistrationOptions("challenge", "logdate.app", "LogDate", PasskeyUser(OWNER, "alice", "Alice")),
        )

    private companion object {
        val ATTEMPT = "a".repeat(43)
        val OTHER_ATTEMPT = "b".repeat(43)
        val TOKEN = "c".repeat(43)
        val NEXT_TOKEN = "d".repeat(43)
        const val ORIGIN = "https://cloud.logdate.app"
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
    }
}
