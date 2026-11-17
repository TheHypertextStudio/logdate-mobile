package app.logdate.client.sync

import app.logdate.client.sync.cloud.MediaTooLargeException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.space.SpaceMediaReference
import app.logdate.client.sync.space.mappers.SpaceJournal

/** Uploads a journal's cover to the personal space before the journal's record refers to it. */
internal class JournalCoverUpload(
    private val tokenRefresher: SyncTokenRefresher,
    private val mediaTransfer: SyncMediaTransfer,
    private val retryCoordinator: SyncRetryCoordinator,
) {
    /**
     * Starts the upload of [item]: records the attempt and uploads its cover. Returns the journal to
     * write, or null when it has to wait or failed.
     */
    suspend fun prepare(
        pending: PendingUpload,
        item: SpaceJournal,
        errors: MutableList<SyncError>,
    ): SpaceJournal? {
        if (!retryCoordinator.beginAttempt(EntityType.JOURNAL, pending, errors)) return null
        val uploaded =
            tokenRefresher.withFreshToken(
                { token -> mediaTransfer.uploadJournalCoverIfNeeded(token, item.journal) { SpaceMediaReference.parse(it) != null } },
                "uploadJournalCover(${item.journal.id})",
                expectedScope = pending.scope,
            )
        if (uploaded.isSuccess) return item.copy(journal = uploaded.getOrThrow())
        val error = uploaded.exceptionOrNull() ?: Exception("Unknown cover upload error")
        val movedToDeadLetter =
            retryCoordinator.handleRetryFailure(
                EntityType.JOURNAL,
                pending,
                error,
                permanent = error is MediaTooLargeException,
            )
        errors +=
            SyncError(
                mediaUploadSyncErrorType(error),
                "Failed to upload the cover of journal ${item.journal.id}: ${error.message}",
                error,
                retryable = !movedToDeadLetter,
            )
        return null
    }
}
