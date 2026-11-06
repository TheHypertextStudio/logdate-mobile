package app.logdate.server.identity

import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcDirectoryException

internal enum class PlcPublicationOutcome { Pending, Confirmed, Conflict, Inactive }

/** Advances one claimed review by one operation. The caller must serialize work for the account. */
internal class PlcPublicationWorker(
    private val directory: PlcDirectoryClient,
) {
    /**
     * [markAttempt] must durably record that a write may occur and recheck the claim and current
     * account. [confirm] atomically records the verified result and exposes the exact DID.
     */
    suspend fun advance(
        review: PlcPublicationReview,
        markAttempt: suspend () -> Boolean,
        confirm: suspend () -> Boolean,
    ): PlcPublicationOutcome {
        val log =
            directory.getAuditLog(review.did).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                if (failure is PlcDirectoryException && failure.isNotFound) {
                    emptyList()
                } else {
                    return PlcPublicationOutcome.Pending
                }
            }
        return when (val progress = review.reconcile(log)) {
            PlcPublicationProgress.Conflict -> {
                PlcPublicationOutcome.Conflict
            }

            PlcPublicationProgress.Confirmed -> {
                val document =
                    directory.getDocument(review.did).getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        return PlcPublicationOutcome.Pending
                    }
                when {
                    !review.matchesDocument(document) -> PlcPublicationOutcome.Conflict
                    confirm() -> PlcPublicationOutcome.Confirmed
                    else -> PlcPublicationOutcome.Inactive
                }
            }

            is PlcPublicationProgress.Next -> {
                if (!markAttempt()) return PlcPublicationOutcome.Inactive
                // Success and uncertainty both require read-back before another write.
                directory.submit(review.did, progress.operation).exceptionOrNull()?.let {
                    if (it is CancellationException) throw it
                }
                PlcPublicationOutcome.Pending
            }
        }
    }
}
