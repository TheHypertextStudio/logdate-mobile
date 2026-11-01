package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.recovery.DownloadScope
import kotlinx.serialization.Serializable

/**
 * The exact reviewed candidates, kept outside canonical note content. Comparison timestamps follow
 * the existing resolver policy; each original also retains its actual creation and update instants.
 */
@Serializable
data class TextConflictVersions(
    val local: JournalNote.Text,
    val remote: JournalNote.Text,
    val localTimestamp: String,
    val remoteTimestamp: String,
)

internal fun retainedTextVersions(
    local: Any,
    remote: Any,
    scope: DownloadScope?,
): TextConflictVersions? {
    if (local !is JournalNote.Text || remote !is JournalNote.Text) return null
    require(local.uid == remote.uid) { "Conflict identity changed" }
    require(scope != null && scope.owner.isNotBlank() && scope.origin.isNotBlank()) { "Conflict scope unavailable" }
    val (localTimestamp, remoteTimestamp) =
        conflictTimestamps(local.syncVersion, local.lastUpdated, remote.syncVersion, remote.lastUpdated)
    return TextConflictVersions(local, remote, localTimestamp.toString(), remoteTimestamp.toString())
}
