package app.logdate.client.sync

import app.logdate.client.sync.SyncWorkerRetryPolicy.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncWorkerRetryPolicyTest {
    private val max = SyncWorkerRetryPolicy.MAX_ATTEMPTS
    private val done = SyncResult(success = true)
    private val morePending = SyncResult(success = true, hasMorePending = true)
    private val transient = SyncResult(success = false, errors = listOf(SyncError(SyncErrorType.NETWORK_ERROR, "offline")))
    private val auth = SyncResult(success = false, errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "expired")))

    @Test
    fun `a finished run succeeds`() {
        assertEquals(Outcome.SUCCESS, SyncWorkerRetryPolicy.decide(done, attemptCount = 0))
    }

    @Test
    fun `pending work retries under the cap`() {
        assertEquals(Outcome.RETRY, SyncWorkerRetryPolicy.decide(morePending, attemptCount = 1))
    }

    @Test
    fun `pending work at the cap is left for the next scheduled run`() {
        assertEquals(Outcome.SUCCESS, SyncWorkerRetryPolicy.decide(morePending, attemptCount = max))
    }

    @Test
    fun `a transient failure retries under the cap`() {
        assertEquals(Outcome.RETRY, SyncWorkerRetryPolicy.decide(transient, attemptCount = 1))
    }

    @Test
    fun `a transient failure at the cap fails`() {
        assertEquals(Outcome.FAILURE, SyncWorkerRetryPolicy.decide(transient, attemptCount = max))
    }

    @Test
    fun `an authentication failure never retries`() {
        assertEquals(Outcome.FAILURE, SyncWorkerRetryPolicy.decide(auth, attemptCount = 0))
    }

    @Test
    fun `the sixth execution cannot schedule a seventh attempt`() {
        assertEquals(Outcome.RETRY, SyncWorkerRetryPolicy.decide(transient, attemptCount = 4))
        assertEquals(Outcome.FAILURE, SyncWorkerRetryPolicy.decide(transient, attemptCount = 5))
        assertEquals(Outcome.SUCCESS, SyncWorkerRetryPolicy.decide(morePending, attemptCount = 5))
    }
}
