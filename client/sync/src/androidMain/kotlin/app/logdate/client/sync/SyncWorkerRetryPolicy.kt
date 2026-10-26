package app.logdate.client.sync

/** Decides what a periodic sync worker reports to WorkManager after one run. */
internal object SyncWorkerRetryPolicy {
    const val MAX_ATTEMPTS = 6

    enum class Outcome { SUCCESS, RETRY, FAILURE }

    fun decide(
        result: SyncResult,
        attemptCount: Int,
    ): Outcome {
        val hasAuthError = result.errors.any { it.type == SyncErrorType.AUTHENTICATION_ERROR }
        val underCap = attemptCount < MAX_ATTEMPTS - 1
        return when {
            result.success && !result.hasMorePending -> Outcome.SUCCESS
            result.success -> if (underCap) Outcome.RETRY else Outcome.SUCCESS
            hasAuthError -> Outcome.FAILURE
            underCap -> Outcome.RETRY
            else -> Outcome.FAILURE
        }
    }
}
