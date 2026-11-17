package app.logdate.client.sync

import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.MediaTooLargeException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import io.github.aakira.napier.Napier

internal class NoteMediaUpload(
    private val dataUsagePolicy: DataUsagePolicy,
    private val tokenRefresher: SyncTokenRefresher,
    private val mediaTransfer: SyncMediaTransfer,
    private val retryCoordinator: SyncRetryCoordinator,
    private val setMediaDeferredForNetwork: (Boolean) -> Unit,
) {
    /** Uses the same receipt/policy decision as prepare, without starting an attempt or reading payload bytes. */
    suspend fun isEligible(
        pending: PendingUpload,
        note: JournalNote,
        reusable: (String) -> Boolean = { true },
    ): Boolean {
        val ready = mediaTransfer.resolveForUpload(note, pending.scope, reusable) ?: return false
        return policyAllows(ready)
    }

    private suspend fun policyAllows(note: JournalNote): Boolean {
        val reference = note.mediaRefOrNull()
        if (reference == null || !mediaTransfer.requiresUpload(reference)) return true
        return mediaSyncAllowed(dataUsagePolicy.currentMode(), tokenRefresher.currentUploadScope())
    }

    /** @param reusable whether an earlier upload, named by its remote reference, can stand in for uploading again. */
    suspend fun prepare(
        pending: PendingUpload,
        note: JournalNote,
        errors: MutableList<SyncError>,
        reusable: (String) -> Boolean = { true },
    ): JournalNote? {
        val remoteReady = mediaTransfer.resolveForUpload(note, pending.scope, reusable) ?: return null
        val mediaRef = remoteReady.mediaRefOrNull()
        val needsMediaUpload = mediaRef != null && mediaTransfer.requiresUpload(mediaRef)
        if (!policyAllows(remoteReady)) {
            setMediaDeferredForNetwork(true)
            Napier.d("Deferring media upload for note — data usage policy restricts media sync")
            return null
        }

        if (!retryCoordinator.beginAttempt(EntityType.NOTE, pending, errors)) return null
        return if (needsMediaUpload) {
            val mediaUpload =
                tokenRefresher.withFreshToken(
                    { token -> mediaTransfer.uploadIfNeeded(token, remoteReady, reusable) },
                    "uploadNoteMedia",
                    expectedScope = pending.scope,
                )
            if (mediaUpload.isFailure) {
                val error =
                    mediaUpload.exceptionOrNull()
                        ?: Exception("Unknown media upload error")
                if (error is CloudApiException && error.statusCode == 409 && error.errorCode == "DELETION_CONFLICT") {
                    errors += retryCoordinator.handleUploadConflict(EntityType.NOTE, pending, error, note.syncVersion, note.lastUpdated)
                    return null
                }
                // This used to `continue` without recording an attempt, so a
                // note whose media file no longer exists on disk retried for
                // ever and held the whole queue behind it - the upload can
                // never succeed, because the bytes are gone. Counting the
                // attempt lets it dead-letter into Sync Issues like any other
                // stuck upload.
                //
                // A single existence check isn't proof of that, though --
                // MediaManager.exists() answers false for a provider that
                // merely failed to answer, not only for bytes truly gone
                // (see its doc comment). Only treat this as permanent once
                // the *immediately preceding* attempt for this same note was
                // also a missing-media miss -- not merely once its generic,
                // shared retryCount happens to be >= 1, which an unrelated
                // failure (network, server, ...) could have put there -- so
                // one bad read doesn't wrongly bury a file that's still there.
                val movedToDeadLetter =
                    retryCoordinator.handleRetryFailure(
                        entityType = EntityType.NOTE,
                        pending = pending,
                        error = error,
                        permanent =
                            error is MediaTooLargeException ||
                                (
                                    error is MissingMediaException &&
                                        retryCoordinator.previousFailureWasMissingMedia(
                                            EntityType.NOTE,
                                            pending,
                                        )
                                ),
                    )
                errors.add(
                    SyncError(
                        // STORAGE_ERROR renders as "Cloud storage is full",
                        // which is a lie for a file missing from this device
                        // and sends the user to a billing page for a local
                        // problem. Only a real server-side storage refusal
                        // earns that message.
                        mediaUploadSyncErrorType(error),
                        "Failed to upload media for note ${note.uid}: ${error.message}",
                        error,
                        retryable = !movedToDeadLetter,
                    ),
                )
                Napier.w("Skipping note sync; media upload failed")
                return null
            }
            mediaUpload.getOrThrow()
        } else {
            remoteReady
        }
    }
}

internal fun mediaUploadSyncErrorType(error: Throwable): SyncErrorType =
    when {
        error is MissingMediaException -> SyncErrorType.UNKNOWN_ERROR
        error is MediaTooLargeException -> SyncErrorType.MEDIA_TOO_LARGE_ERROR
        error is CloudApiException && error.errorCode in CLOUD_QUOTA_ERROR_CODES -> SyncErrorType.STORAGE_ERROR
        else -> SyncErrorType.UNKNOWN_ERROR
    }

private val CLOUD_QUOTA_ERROR_CODES =
    setOf("QUOTA_EXCEEDED", "STORAGE_LIMIT_EXCEEDED", "STORAGE_QUOTA_EXCEEDED")
