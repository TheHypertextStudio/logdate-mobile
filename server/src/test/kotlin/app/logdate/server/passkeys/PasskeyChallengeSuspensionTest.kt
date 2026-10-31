package app.logdate.server.passkeys

import app.logdate.shared.model.PasskeyChallenge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Issuing a passkey challenge waits for the challenge store without holding the calling thread. */
@OptIn(ExperimentalUuidApi::class)
class PasskeyChallengeSuspensionTest {
    /** A store whose writes finish only when [released] completes, as a slow database would. */
    private class SlowChallengeStore : PasskeyChallengeRepository {
        val released = CompletableDeferred<Unit>()

        override suspend fun save(
            scope: String,
            challenge: PasskeyChallenge,
        ) {
            withTimeout(5.seconds) { released.await() }
        }

        override suspend fun consume(
            scope: String,
            challenge: String,
            expectedType: String,
            expectedUserId: Uuid?,
            now: Instant,
        ): PasskeyChallenge? = null
    }

    @Test
    fun `a slow challenge store leaves the calling thread free for other requests`() {
        val store = SlowChallengeStore()
        val service = WebAuthnPasskeyService(strictVerificationEnabled = false, challengeRepository = store)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { requestThread ->
            runBlocking(requestThread) {
                val options = async { service.generateRegistrationOptions(Uuid.random(), "alex", "Alex") }
                launch { store.released.complete(Unit) }

                assertTrue(options.await().challenge.isNotBlank())
            }
        }
    }
}
