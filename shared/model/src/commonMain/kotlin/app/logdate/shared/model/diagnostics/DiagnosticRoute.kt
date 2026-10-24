package app.logdate.shared.model.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticRoute {
    UNKNOWN,
    NOTES,
    NOTE,
    NOTE_CHANGES,
    JOURNALS,
    JOURNAL,
    JOURNAL_CHANGES,
    DRAFT,
    DRAFT_CHANGES,
    ASSOCIATIONS,
    ASSOCIATION_CHANGES,
    MEDIA,
    MEDIA_ITEM,
    MEDIA_BINARY,
    BACKUPS,
    BACKUP,
    BACKUP_BINARY,
    DIAGNOSTIC_REPORTS,
    DIAGNOSTIC_REPORT,
}

/** Classify only a finite API template; the supplied path is never retained. */
fun diagnosticRoute(path: String): DiagnosticRoute {
    if (!path.startsWith("/api/v1/") || path.length > 8192) return DiagnosticRoute.UNKNOWN
    val segments = path.removePrefix("/api/v1/").split('/')
    val count = segments.size
    val tail = segments.lastOrNull()
    return when (segments.firstOrNull()) {
        "contents" -> {
            diagnosticContentsRoute(count, tail)
        }

        "journals" -> {
            when {
                count == 1 -> DiagnosticRoute.JOURNALS
                count == 2 && tail == "changes" -> DiagnosticRoute.JOURNAL_CHANGES
                count == 2 -> DiagnosticRoute.JOURNAL
                else -> DiagnosticRoute.UNKNOWN
            }
        }

        "drafts" -> {
            when {
                count == 2 && tail == "changes" -> DiagnosticRoute.DRAFT_CHANGES
                count == 2 -> DiagnosticRoute.DRAFT
                else -> DiagnosticRoute.UNKNOWN
            }
        }

        "associations" -> {
            when {
                count == 1 -> DiagnosticRoute.ASSOCIATIONS
                count == 2 && tail == "changes" -> DiagnosticRoute.ASSOCIATION_CHANGES
                else -> DiagnosticRoute.UNKNOWN
            }
        }

        "media" -> {
            when {
                count == 1 -> DiagnosticRoute.MEDIA
                count == 2 -> DiagnosticRoute.MEDIA_ITEM
                count == 3 && tail == "binary" -> DiagnosticRoute.MEDIA_BINARY
                else -> DiagnosticRoute.UNKNOWN
            }
        }

        "backups" -> {
            when {
                count == 1 -> DiagnosticRoute.BACKUPS
                count == 2 -> DiagnosticRoute.BACKUP
                count == 3 && tail == "binary" -> DiagnosticRoute.BACKUP_BINARY
                else -> DiagnosticRoute.UNKNOWN
            }
        }

        "diagnostics" -> {
            diagnosticReportsRoute(segments)
        }

        else -> {
            DiagnosticRoute.UNKNOWN
        }
    }
}

private fun diagnosticContentsRoute(
    count: Int,
    tail: String?,
): DiagnosticRoute =
    when {
        count == 1 -> DiagnosticRoute.NOTES
        count == 2 && tail == "changes" -> DiagnosticRoute.NOTE_CHANGES
        count == 2 -> DiagnosticRoute.NOTE
        else -> DiagnosticRoute.UNKNOWN
    }

private fun diagnosticReportsRoute(segments: List<String>): DiagnosticRoute =
    when {
        segments.size == 2 && segments.lastOrNull() == "reports" -> DiagnosticRoute.DIAGNOSTIC_REPORTS
        segments.size == 3 && segments[1] == "reports" -> DiagnosticRoute.DIAGNOSTIC_REPORT
        else -> DiagnosticRoute.UNKNOWN
    }

fun diagnosticRequestPhase(
    route: DiagnosticRoute,
    read: Boolean,
): DiagnosticPhase =
    when (route) {
        DiagnosticRoute.MEDIA, DiagnosticRoute.MEDIA_ITEM, DiagnosticRoute.MEDIA_BINARY -> DiagnosticPhase.MEDIA
        DiagnosticRoute.BACKUPS, DiagnosticRoute.BACKUP, DiagnosticRoute.BACKUP_BINARY -> DiagnosticPhase.ARCHIVE
        else -> if (read) DiagnosticPhase.FETCH else DiagnosticPhase.UPLOAD
    }
