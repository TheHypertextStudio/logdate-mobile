package app.logdate.client.device.auth

import app.logdate.client.device.storage.FakeSecureStorage
import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.model.BlueskyBrowserReturn
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlueskyAttemptStoreTest {
    @Test
    fun `process restart resumes only the matching attempt with original backend and verifier`() =
        runTest {
            val storage = FakeSecureStorage()
            BlueskyAttemptStore(storage) { 1000 }.remember(ATTEMPT, VERIFIER, ORIGIN, OWNER)
            val restarted = BlueskyAttemptStore(storage) { 2000 }
            assertNull(restarted.forReturn(returnUrl(OTHER_ATTEMPT)))
            val pending = assertNotNull(restarted.forReturn(returnUrl(ATTEMPT)))
            assertEquals(ATTEMPT, pending.attemptId)
            assertEquals(VERIFIER, pending.verifier)
            assertEquals(ORIGIN, pending.backendOrigin)
            assertEquals(OWNER, pending.ownerId)
            assertFalse(pending.toString().contains(VERIFIER))
            assertNotNull(restarted.forReturn("${BlueskyBrowserReturn.NATIVE_URL}?attempt=$ATTEMPT"))
        }

    @Test
    fun `expiry and cancellation never clear a newer attempt`() =
        runTest {
            val storage = FakeSecureStorage()
            val store = BlueskyAttemptStore(storage) { 1000 }
            store.remember(ATTEMPT, VERIFIER, ORIGIN, OWNER)
            store.remember(OTHER_ATTEMPT, OTHER_VERIFIER, "https://staging.logdate.app", OWNER)
            store.forget(ATTEMPT)
            assertNotNull(store.forReturn(returnUrl(OTHER_ATTEMPT)))
            assertNull(BlueskyAttemptStore(storage) { 601000 }.forReturn(returnUrl(OTHER_ATTEMPT)))
            store.forget(OTHER_ATTEMPT)
            assertNull(store.forReturn(returnUrl(OTHER_ATTEMPT)))
        }

    @Test
    fun `a failed secure write cannot open a browser with an unresumable attempt`() =
        runTest {
            val storage =
                object : SecureStorage by FakeSecureStorage() {
                    override suspend fun putString(
                        key: String,
                        value: String,
                    ) = Unit
                }
            val store = BlueskyAttemptStore(storage) { 1000 }
            val failure = assertFailsWith<IllegalStateException> { store.remember(ATTEMPT, VERIFIER, ORIGIN, OWNER) }
            assertFalse(failure.toString().contains(VERIFIER))
            assertNull(store.forReturn(returnUrl(ATTEMPT)))
        }

    @Test
    fun `a failed removal cannot silently retain a completed proof`() =
        runTest {
            val delegate = FakeSecureStorage()
            val storage =
                object : SecureStorage by delegate {
                    override suspend fun remove(key: String) = Unit
                }
            val store = BlueskyAttemptStore(storage) { 1000 }
            store.remember(ATTEMPT, VERIFIER, ORIGIN, OWNER)
            assertFailsWith<IllegalStateException> { store.forget(ATTEMPT) }
        }

    @Test
    fun `malformed proof or backend destinations are refused before storage`() =
        runTest {
            val store = BlueskyAttemptStore(FakeSecureStorage()) { 1000 }
            for (origin in listOf(
                "http://cloud.logdate.app",
                "https://user:secret@cloud.logdate.app",
                "https://cloud.logdate.app/path",
                "https://cloud.logdate.app?x=1",
                "https://cloud.logdate.app#",
                " https://cloud.logdate.app",
            )) {
                assertFailsWith<IllegalArgumentException>(origin) { store.remember(ATTEMPT, VERIFIER, origin, OWNER) }
            }
            assertFailsWith<IllegalArgumentException> { store.remember("short", VERIFIER, ORIGIN, OWNER) }
            assertFailsWith<IllegalArgumentException> { store.remember(ATTEMPT, "short", ORIGIN, OWNER) }
            assertNull(store.forReturn(returnUrl(ATTEMPT)))
        }

    private fun returnUrl(attempt: String): String = "${BlueskyBrowserReturn.URL}?attempt=$attempt"

    private companion object {
        val ATTEMPT = "a".repeat(43)
        val OTHER_ATTEMPT = "b".repeat(43)
        val VERIFIER = "c".repeat(43)
        val OTHER_VERIFIER = "d".repeat(43)
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        const val ORIGIN = "https://cloud.logdate.app"
    }
}
