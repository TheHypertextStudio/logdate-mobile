package app.logdate.client.repository.transcription

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Durable transcript shape used by live recognition, refinement, search, and sync.
 *
 * Text-only transcriptions still map cleanly into a single-segment document, while
 * Recorder-quality sessions can keep timestamps, speakers, confidence, and source
 * revisions without changing the public repository surface again.
 */
@Serializable
data class TranscriptDocument(
    /** Monotonic version incremented whenever the transcript document changes. */
    val revision: Int = 0,
    /** Lifecycle state for the whole recording transcript. */
    val status: TranscriptDocumentStatus = TranscriptDocumentStatus.LISTENING,
    /** BCP-47 language tag requested or detected for this transcript. */
    val language: String = "en-US",
    /** Ordered utterances with timestamps and source metadata. */
    val segments: List<TranscriptSegment> = emptyList(),
    /** Optional speaker catalog used by [TranscriptSegment.speakerId]. */
    val speakers: List<TranscriptSpeaker> = emptyList(),
    /** Recognition engine metadata, when reported by the local or cloud recognizer. */
    val engine: TranscriptEngineMetadata? = null,
    /** Non-speech sounds detected in the recording, such as laughter or rain. */
    val soundTags: List<TranscriptSoundTag> = emptyList(),
    /** When the transcript was first produced, when known. */
    val createdAt: Instant? = null,
    /** When the transcript last changed, when known. */
    val updatedAt: Instant? = null,
) {
    /** User-visible text assembled from timestamp order. */
    val plainText: String
        get() =
            segments
                .sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.segmentId })
                .joinToString(" ") { it.text.trim() }
                .trim()

    /** True when the document and all of its segments are safe to treat as final. */
    val isFinal: Boolean
        get() = status == TranscriptDocumentStatus.FINAL && segments.all { it.isFinal }

    companion object {
        /**
         * Builds a timestamp-compatible document around legacy plain-text transcription data.
         */
        fun fromPlainText(
            text: String,
            status: TranscriptDocumentStatus = TranscriptDocumentStatus.FINAL,
            source: TranscriptSource = TranscriptSource.IMPORTED,
        ): TranscriptDocument =
            TranscriptDocument(
                status = status,
                segments =
                    if (text.isBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            TranscriptSegment(
                                segmentId = "legacy-0",
                                text = text.trim(),
                                startMs = 0,
                                endMs = 0,
                                source = source,
                                isFinal = status == TranscriptDocumentStatus.FINAL,
                            ),
                        )
                    },
            )
    }
}

/** Lifecycle state for a transcript document. */
@Serializable
enum class TranscriptDocumentStatus {
    /** Audio is still being recognized and draft segments can change. */
    LISTENING,

    /** A higher-accuracy pass is improving an existing local transcript. */
    REFINING,

    /** Recognition is complete and all segments should be treated as stable. */
    FINAL,

    /** Recognition failed after the session started. */
    FAILED,
}

/**
 * Origin of a transcript hypothesis, ordered by how authoritative it should be during merges.
 */
@Serializable
enum class TranscriptSource(
    internal val priority: Int,
) {
    /** Text imported from older storage or external files. */
    IMPORTED(0),

    /** Low-latency on-device live recognition. */
    LOCAL_LIVE(10),

    /** Low-latency cloud live recognition. */
    CLOUD_LIVE(20),

    /** On-device second pass over captured audio. */
    LOCAL_REFINEMENT(30),

    /** Cloud second pass over captured audio. */
    CLOUD_REFINEMENT(40),
}

/** Speaker label metadata shared by transcript segments and words. */
@Serializable
data class TranscriptSpeaker(
    /** Stable identifier referenced by segment and word speaker fields. */
    val speakerId: String,
    /** Human-readable display label, such as "Speaker 1". */
    val label: String,
    /** Optional design token used to keep speaker colors stable across UI surfaces. */
    val colorToken: String? = null,
)

/** A contiguous speech span with text, timing, source, and optional speaker metadata. */
@Serializable
data class TranscriptSegment(
    /** Stable segment identity used when replacing drafts with refined hypotheses. */
    val segmentId: String,
    /** Display text for this segment. */
    val text: String,
    /** Segment start timestamp relative to the beginning of the recording. */
    val startMs: Long,
    /** Segment end timestamp relative to the beginning of the recording. */
    val endMs: Long,
    /** Optional word-level timing for search result highlighting and playback scrubbing. */
    val words: List<TranscriptWord> = emptyList(),
    /** Optional speaker identity matching [TranscriptSpeaker.speakerId]. */
    val speakerId: String? = null,
    /** Engine confidence from 0.0 to 1.0 when available. */
    val confidence: Float? = null,
    /** Recognition source used to decide merge priority. */
    val source: TranscriptSource = TranscriptSource.LOCAL_LIVE,
    /** Whether this segment is stable enough for final transcript display. */
    val isFinal: Boolean = false,
) {
    init {
        require(segmentId.isNotBlank()) { "segmentId must not be blank" }
        require(endMs >= startMs) { "Transcript segment endMs must be >= startMs" }
    }
}

/** Word-level text and timing within a [TranscriptSegment]. */
@Serializable
data class TranscriptWord(
    /** Original recognized token text. */
    val text: String,
    /** Search-friendly normalized form of [text]. */
    val normalizedText: String,
    /** Word start timestamp relative to the beginning of the recording. */
    val startMs: Long,
    /** Word end timestamp relative to the beginning of the recording. */
    val endMs: Long,
    /** Engine confidence from 0.0 to 1.0 when available. */
    val confidence: Float? = null,
    /** Optional speaker identity matching [TranscriptSpeaker.speakerId]. */
    val speakerId: String? = null,
) {
    init {
        require(endMs >= startMs) { "Transcript word endMs must be >= startMs" }
    }
}

/** Recognition engine identity retained with exported and synced transcripts. */
@Serializable
data class TranscriptEngineMetadata(
    val name: String,
    val modelId: String? = null,
    val version: String? = null,
)

/** A non-speech sound detected in a recording, such as laughter or rain. */
@Serializable
data class TranscriptSoundTag(
    /** What the sound is. */
    val name: String,
    /** When the sound starts, relative to the beginning of the recording. */
    val startMs: Long,
    /** How long the sound lasts, when known. */
    val durationMs: Long? = null,
    /** Detection confidence from 0.0 to 1.0 when available. */
    val confidence: Float? = null,
)
