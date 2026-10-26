package app.logdate.client.domain.export.archive

import app.logdate.client.domain.export.ExportError
import app.logdate.client.domain.export.ExportStage
import kotlin.time.Instant

/** What a person chose to include in an export. */
data class ArchiveExportOptions(
    val includeJournals: Boolean = true,
    val includeNotes: Boolean = true,
    val includeDrafts: Boolean = true,
    val includeMedia: Boolean = true,
    /** Only entries, drafts and location history created at or after this instant. */
    val from: Instant? = null,
    /** Only entries, drafts and location history created at or before this instant. */
    val to: Instant? = null,
) {
    val isFiltered: Boolean get() = from != null || to != null

    fun includes(createdAt: Instant): Boolean = (from == null || createdAt >= from) && (to == null || createdAt <= to)
}

sealed class ArchiveExportProgress {
    data object Starting : ArchiveExportProgress()

    data class InProgress(
        val fraction: Float,
        val stage: ExportStage,
    ) : ArchiveExportProgress()

    data class Completed(
        val summary: ArchiveExportSummary,
    ) : ArchiveExportProgress()

    data class Failed(
        val error: ExportError,
    ) : ArchiveExportProgress()
}

/** What an export wrote, for the completion message. */
data class ArchiveExportSummary(
    val counts: ArchiveCounts,
    val scope: ArchiveScope,
    /** Content identity, excluding export-time metadata, for validating an unfinished backup. */
    val sourceFingerprint: String? = null,
)
