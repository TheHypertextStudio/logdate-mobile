package app.logdate.client.restore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class NativeMediaQuarantineResult {
    COMPLETE,
    HANDED_OFF,
    RETRY,
}

internal class NativeMediaQuarantinePendingException(
    cause: Throwable? = null,
) : IllegalStateException("Native media recovery is still pending", cause)

private const val MAX_FOREGROUND_ATTEMPTS = 3

/** Reconciles restored media before opening the journal, then leaves durable retries to WorkManager. */
internal suspend fun awaitNativeMediaQuarantine(
    pendingDelivery: () -> String?,
    quarantine: suspend (isCurrent: () -> Boolean) -> Boolean,
    acknowledge: (String) -> Boolean,
    enqueue: suspend (String) -> Unit,
) {
    var backoff = 1_000L
    var failedAttempts = 0
    while (true) {
        val delivery =
            try {
                pendingDelivery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failedAttempts++
                if (failedAttempts >= MAX_FOREGROUND_ATTEMPTS) throw NativeMediaQuarantinePendingException(error)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            } ?: return
        val recovery =
            NativeMediaQuarantineRecovery(
                pendingDelivery = pendingDelivery,
                quarantine = quarantine,
                acknowledge = acknowledge,
                enqueue = enqueue,
            )
        val result = recovery.run(delivery)
        when (result) {
            NativeMediaQuarantineResult.COMPLETE -> {
                return
            }

            NativeMediaQuarantineResult.HANDED_OFF -> {
                failedAttempts = 0
                backoff = 1_000L
            }

            NativeMediaQuarantineResult.RETRY -> {
                failedAttempts++
                if (failedAttempts >= MAX_FOREGROUND_ATTEMPTS) {
                    if (finishOrContinueAtHandoffBoundary(delivery, recovery, pendingDelivery, enqueue)) return
                    failedAttempts = 0
                    backoff = 1_000L
                    continue
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
}

private suspend fun finishOrContinueAtHandoffBoundary(
    delivery: String,
    recovery: NativeMediaQuarantineRecovery,
    pendingDelivery: () -> String?,
    enqueue: suspend (String) -> Unit,
): Boolean {
    val latestDelivery =
        try {
            pendingDelivery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw NativeMediaQuarantinePendingException(error)
        } ?: return true
    if (latestDelivery != delivery) {
        enqueueHandoff(latestDelivery, recovery, enqueue)
        return false
    }
    enqueueHandoff(latestDelivery, recovery, enqueue)
    val afterEnqueue =
        try {
            pendingDelivery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw NativeMediaQuarantinePendingException(error)
        } ?: return true
    if (afterEnqueue != latestDelivery) return false
    throw NativeMediaQuarantinePendingException(recovery.lastFailure)
}

private suspend fun enqueueHandoff(
    delivery: String,
    recovery: NativeMediaQuarantineRecovery,
    enqueue: suspend (String) -> Unit,
) {
    try {
        enqueue(delivery)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw NativeMediaQuarantinePendingException(error).also {
            recovery.lastFailure?.let(it::addSuppressed)
        }
    }
}

/** Reconciles one durable native-restore marker without ever acknowledging an incomplete pass. */
internal class NativeMediaQuarantineRecovery(
    private val pendingDelivery: () -> String?,
    private val quarantine: suspend (isCurrent: () -> Boolean) -> Boolean,
    private val acknowledge: (String) -> Boolean,
    private val enqueue: suspend (String) -> Unit,
) {
    var lastFailure: Throwable? = null
        private set

    suspend fun run(expectedDelivery: String): NativeMediaQuarantineResult =
        try {
            lastFailure = null
            val currentDelivery = pendingDelivery() ?: return NativeMediaQuarantineResult.COMPLETE
            if (currentDelivery != expectedDelivery) {
                enqueue(currentDelivery)
                return NativeMediaQuarantineResult.HANDED_OFF
            }
            if (!quarantine { pendingDelivery() == expectedDelivery }) return retryOrScheduleCurrent(expectedDelivery)
            if (acknowledge(expectedDelivery)) return NativeMediaQuarantineResult.COMPLETE
            retryOrScheduleCurrent(expectedDelivery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastFailure = error
            retryOrScheduleCurrent(expectedDelivery)
        }

    private suspend fun retryOrScheduleCurrent(expectedDelivery: String): NativeMediaQuarantineResult =
        try {
            val currentDelivery = pendingDelivery()
            if (currentDelivery != null && currentDelivery != expectedDelivery) {
                enqueue(currentDelivery)
                NativeMediaQuarantineResult.HANDED_OFF
            } else {
                NativeMediaQuarantineResult.RETRY
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastFailure = error
            NativeMediaQuarantineResult.RETRY
        }
}
