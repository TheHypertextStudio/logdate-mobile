package app.logdate.client.domain.restore

import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository

/** Repairs attachment references without replaying archive text, timestamps, or memberships. */
class RepairArchiveMediaUseCase(
    private val notes: JournalNotesRepository,
    private val journals: JournalRepository,
) {
    suspend fun repair(
        pending: List<UnavailableMediaAsset>,
        importer: MediaImporter,
        onPendingChanged: ((List<UnavailableMediaAsset>) -> Unit)? = null,
        onProgress: ((ArchiveMediaRepairProgress) -> Unit)? = null,
        expectedNoteScope: JournalMergeScope? = null,
    ): ArchiveMediaRepairResult {
        val noteScope = notes.getNoteRecoveryScope()
        check(expectedNoteScope == null || noteScope == expectedNoteScope) { "Archive media recovery account changed" }
        val draftScope = journals.getDraftRecoveryScope()
        check(
            expectedNoteScope == null ||
                draftScope == null ||
                (
                    draftScope.outboxOwnerId == expectedNoteScope.ownerId &&
                        draftScope.outboxServerOrigin == expectedNoteScope.serverOrigin
                ),
        ) { "Archive media recovery draft account changed" }
        val session = ArchiveMediaRepairSession(notes, journals, importer, noteScope, draftScope)
        val remaining = pending.toMutableList()
        var imported = 0
        var checked = 0
        var index = 0
        while (index < remaining.size) {
            session.requireScope()
            var asset = remaining[index]
            if (!session.stillPending(asset)) {
                remaining.removeAt(index)
                checked++
                publishProgress(remaining, imported, checked, pending.size, onPendingChanged, onProgress)
                continue
            }
            val reference = session.import(asset)
            if (reference == null) {
                index++
                checked++
                onProgress?.invoke(ArchiveMediaRepairProgress(remaining.toList(), imported, checked, pending.size))
                continue
            }
            asset = asset.copy(recoveredReference = reference)
            remaining[index] = asset
            publishProgress(remaining, imported, checked, pending.size, onPendingChanged, onProgress)
            val repaired = session.apply(asset, reference)
            if (repaired || !session.stillPending(asset)) {
                if (repaired) imported++
                remaining.removeAt(index)
            } else {
                index++
            }
            checked++
            publishProgress(remaining, imported, checked, pending.size, onPendingChanged, onProgress)
        }
        return ArchiveMediaRepairResult(remaining, imported)
    }

    private fun publishProgress(
        pending: List<UnavailableMediaAsset>,
        imported: Int,
        checked: Int,
        total: Int,
        onPendingChanged: ((List<UnavailableMediaAsset>) -> Unit)?,
        onProgress: ((ArchiveMediaRepairProgress) -> Unit)?,
    ) {
        val snapshot = pending.toList()
        onPendingChanged?.invoke(snapshot)
        onProgress?.invoke(ArchiveMediaRepairProgress(snapshot, imported, checked, total))
    }
}

data class ArchiveMediaRepairProgress(
    val pending: List<UnavailableMediaAsset>,
    val imported: Int,
    val checked: Int,
    val total: Int,
)

data class ArchiveMediaRepairResult(
    val pending: List<UnavailableMediaAsset>,
    val imported: Int,
)
