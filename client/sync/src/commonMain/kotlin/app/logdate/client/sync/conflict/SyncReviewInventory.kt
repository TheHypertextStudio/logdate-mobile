package app.logdate.client.sync.conflict

import app.logdate.client.sync.metadata.UploadScope

/** Presentation only: retaining history never requires treating it as a current writing choice. */
data class SyncReviewInventory(
    val edits: List<SyncConflictRecord> = emptyList(),
    val earlierIssues: Int = 0,
)

fun syncReviewInventory(
    records: List<SyncConflictRecord>,
    scope: UploadScope?,
): SyncReviewInventory {
    if (scope == null) return SyncReviewInventory()
    val unresolved = records.distinct().filter { !it.recovered && (it.scope == scope || it.scope == null) }
    val edits = unresolved.filter { it.scope == scope && (it.deletedEdit != null || it.textVersions != null) }
    return SyncReviewInventory(edits, unresolved.size - edits.size)
}
