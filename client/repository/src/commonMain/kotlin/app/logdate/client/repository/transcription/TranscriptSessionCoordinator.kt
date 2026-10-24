package app.logdate.client.repository.transcription

/**
 * Session-scoped reducer that turns local/cloud recognition events into one
 * stable transcript document. Engines can emit drafts, final utterances, and
 * refinements independently; the coordinator applies source priority and keeps
 * the UI/repository observing a single document.
 */
class TranscriptSessionCoordinator(
    language: String = "en-US",
) {
    /** Current transcript document after applying all received updates. */
    var document: TranscriptDocument =
        TranscriptDocument(
            language = language,
            status = TranscriptDocumentStatus.LISTENING,
        )
        private set

    /**
     * Applies one recognition event and returns the updated transcript document.
     */
    fun apply(update: TranscriptSessionUpdate): TranscriptDocument {
        document =
            when (update) {
                is TranscriptSessionUpdate.UpsertSegment -> {
                    document
                        .upsertSegment(update.segment)
                        .copy(status = document.status.coerceActive())
                }

                is TranscriptSessionUpdate.ReplaceDocument -> {
                    update.document.copy(revision = maxOf(document.revision + 1, update.document.revision))
                }

                TranscriptSessionUpdate.MarkRefining -> {
                    document.copy(
                        revision = document.revision + 1,
                        status = TranscriptDocumentStatus.REFINING,
                    )
                }

                TranscriptSessionUpdate.MarkFinal -> {
                    document.copy(
                        revision = document.revision + 1,
                        status = TranscriptDocumentStatus.FINAL,
                        segments = document.segments.map { it.copy(isFinal = true) },
                    )
                }

                is TranscriptSessionUpdate.MarkFailed -> {
                    document.copy(
                        revision = document.revision + 1,
                        status = TranscriptDocumentStatus.FAILED,
                    )
                }
            }

        return document
    }

    private fun TranscriptDocumentStatus.coerceActive(): TranscriptDocumentStatus =
        when (this) {
            TranscriptDocumentStatus.FINAL,
            TranscriptDocumentStatus.FAILED,
            -> TranscriptDocumentStatus.LISTENING

            TranscriptDocumentStatus.LISTENING,
            TranscriptDocumentStatus.REFINING,
            -> this
        }
}

/** Events emitted by local and cloud recognition engines for one recording session. */
sealed interface TranscriptSessionUpdate {
    /** Inserts a new segment or replaces an existing segment with the same identity. */
    data class UpsertSegment(
        val segment: TranscriptSegment,
    ) : TranscriptSessionUpdate

    /** Replaces the full document, typically after a complete refinement pass. */
    data class ReplaceDocument(
        val document: TranscriptDocument,
    ) : TranscriptSessionUpdate

    /** Marks the transcript as undergoing a higher-accuracy pass. */
    data object MarkRefining : TranscriptSessionUpdate

    /** Marks every current segment final and closes the active session. */
    data object MarkFinal : TranscriptSessionUpdate

    /** Marks the recognition session failed while preserving any partial text for the user. */
    data class MarkFailed(
        val reason: String? = null,
    ) : TranscriptSessionUpdate
}
