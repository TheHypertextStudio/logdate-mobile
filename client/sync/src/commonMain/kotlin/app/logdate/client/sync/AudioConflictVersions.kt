package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.recovery.DownloadScope
import kotlinx.serialization.Serializable

/** Complete recording candidates, including caption, transcript and original media references. */
@Serializable
data class AudioConflictVersions(
    val local: JournalNote.Audio,
    val remote: JournalNote.Audio,
    val localTimestamp: String,
    val remoteTimestamp: String,
)

internal fun retainedAudioVersions(
    local: Any,
    remote: Any,
    scope: DownloadScope?,
): AudioConflictVersions? {
    // Unscoped legacy metadata must not be promoted into another account's current work.
    if (local !is JournalNote.Audio || remote !is JournalNote.Audio || scope == null) return null
    require(local.uid == remote.uid) { "Conflict identity changed" }
    require(scope.owner.isNotBlank() && scope.origin.isNotBlank()) { "Conflict scope unavailable" }
    val (localTimestamp, remoteTimestamp) =
        conflictTimestamps(local.syncVersion, local.lastUpdated, remote.syncVersion, remote.lastUpdated)
    return AudioConflictVersions(local, remote, localTimestamp.toString(), remoteTimestamp.toString())
}
