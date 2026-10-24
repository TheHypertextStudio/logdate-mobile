package app.logdate.client.sync.space.mappers

import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptEngineMetadata
import app.logdate.client.repository.transcription.TranscriptSource
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.sync.space.SpaceRecordMapper
import app.logdate.client.sync.space.SpaceRecordMappingException
import app.logdate.client.sync.space.decodeRecord
import app.logdate.client.sync.space.encodeRecord
import app.logdate.client.sync.space.fromThousandths
import app.logdate.client.sync.space.parseDatetime
import app.logdate.client.sync.space.parseUuidKey
import app.logdate.client.sync.space.recordRef
import app.logdate.client.sync.space.sameRecordKey
import app.logdate.client.sync.space.toDatetime
import app.logdate.client.sync.space.toThousandths
import app.logdate.lexicon.app.logdate.journal.Entry
import app.logdate.lexicon.app.logdate.journal.Transcript
import app.logdate.lexicon.app.logdate.journal.TranscriptEngine
import app.logdate.lexicon.app.logdate.journal.TranscriptSegment
import app.logdate.lexicon.app.logdate.journal.TranscriptSoundTag
import app.logdate.lexicon.app.logdate.journal.TranscriptSpeaker
import app.logdate.lexicon.app.logdate.journal.TranscriptWord
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid
import app.logdate.client.repository.transcription.TranscriptSegment as NoteTranscriptSegment
import app.logdate.client.repository.transcription.TranscriptSoundTag as NoteSoundTag
import app.logdate.client.repository.transcription.TranscriptSpeaker as NoteTranscriptSpeaker
import app.logdate.client.repository.transcription.TranscriptWord as NoteTranscriptWord

/**
 * The transcript of an audio note, with the id of the note it belongs to.
 *
 * @property noteId the audio note's id: the key of its entry and of the entry's single block.
 * @property job where the transcription job stands. A document doesn't say whether a job that has
 *   produced nothing yet is waiting or running, so without a job given, a listening document with
 *   no segments is a waiting one.
 */
data class NoteTranscript(
    val noteId: Uuid,
    val document: TranscriptDocument,
    val job: TranscriptionStatus = jobOf(document),
) {
    private companion object {
        fun jobOf(document: TranscriptDocument): TranscriptionStatus =
            when (document.status) {
                TranscriptDocumentStatus.LISTENING -> {
                    if (document.segments.isEmpty()) TranscriptionStatus.PENDING else TranscriptionStatus.IN_PROGRESS
                }

                TranscriptDocumentStatus.REFINING -> {
                    TranscriptionStatus.IN_PROGRESS
                }

                TranscriptDocumentStatus.FINAL -> {
                    TranscriptionStatus.COMPLETED
                }

                TranscriptDocumentStatus.FAILED -> {
                    TranscriptionStatus.FAILED
                }
            }
    }
}

/**
 * Converts audio notes' transcripts to and from `app.logdate.journal.transcript` records.
 *
 * A transcript's record key is `<entry key>:<block id>`, which [transcriptRkeyOf] builds. A note
 * is a single-block entry keyed by the note's id, so a note's transcript is keyed
 * `<note id>:<note id>`. Each recording therefore has at most one transcript, and the key can be
 * worked out from the note alone.
 *
 * The record's `status` covers the transcript and its job together: `pending` is a job that hasn't
 * started, with no segments yet; `inProgress` and `refining` are a running job, the second pass
 * improving a transcript that exists; `complete` and `failed` are a finished one.
 *
 * What a record can't hold:
 * - Speaker colors aren't stored, so [fromRecord] returns speakers without one.
 * - Confidences are stored in thousandths and rounded to the nearest one.
 */
class NoteTranscriptMapper : SpaceRecordMapper<NoteTranscript> {
    override val collection: String = Transcript.TYPE

    override fun rkeyOf(value: NoteTranscript): String = transcriptRkeyOf(value.noteId)

    override fun toRecord(value: NoteTranscript): JsonObject {
        val owner = "$collection/${rkeyOf(value)}"
        val document = value.document
        val transcript =
            Transcript(
                entry = recordRef(Entry.TYPE, value.noteId),
                blockId = value.noteId.toString(),
                language = document.language,
                segments = document.segments.map { segmentOf(it, owner) },
                speakers = document.speakers.map { TranscriptSpeaker(id = it.speakerId, label = it.label) },
                engine = document.engine?.let { TranscriptEngine(name = it.name, modelId = it.modelId, version = it.version) },
                soundTags = document.soundTags.takeIf { it.isNotEmpty() }?.map { soundTagOf(it, owner) },
                revision = document.revision.toLong(),
                status = statusValue(value),
                createdAt = document.createdAt?.toDatetime("$owner createdAt"),
                updatedAt = document.updatedAt?.toDatetime("$owner updatedAt"),
            )
        return encodeRecord(Transcript.serializer(), transcript)
    }

    override fun fromRecord(
        rkey: String,
        record: JsonObject,
    ): NoteTranscript {
        val owner = "$collection/$rkey"
        val transcript = decodeRecord(Transcript.serializer(), collection, rkey, record)
        val noteId = parseUuidKey(Entry.TYPE, transcript.entry.rkey)
        if (transcript.entry.collection != Entry.TYPE) {
            throw SpaceRecordMappingException("$owner: it belongs to a ${transcript.entry.collection}, not a ${Entry.TYPE}")
        }
        if (!sameRecordKey(transcript.blockId, transcript.entry.rkey)) {
            throw SpaceRecordMappingException("$owner: its block ${transcript.blockId} isn't the note's own block")
        }
        if (!sameRecordKey(rkey, transcriptRkeyOf(noteId))) {
            throw SpaceRecordMappingException("$owner: a transcript of note $noteId is keyed ${transcriptRkeyOf(noteId)}")
        }
        if (transcript.revision > Int.MAX_VALUE) {
            throw SpaceRecordMappingException("$owner: revision ${transcript.revision} is larger than LogDate counts")
        }
        val segments = transcript.segments.orEmpty()
        val document =
            mapping(owner) {
                TranscriptDocument(
                    revision = transcript.revision.toInt(),
                    status = documentStatusOf(transcript.status, owner),
                    language = transcript.language ?: DEFAULT_LANGUAGE,
                    segments = segments.map { segmentOf(it, owner) },
                    speakers = transcript.speakers.orEmpty().map { NoteTranscriptSpeaker(speakerId = it.id, label = it.label) },
                    engine =
                        transcript.engine?.let {
                            TranscriptEngineMetadata(
                                name = it.name,
                                modelId = it.modelId,
                                version = it.version,
                            )
                        },
                    soundTags = transcript.soundTags.orEmpty().map { soundTagOf(it) },
                    createdAt = transcript.createdAt?.let { parseDatetime("$owner createdAt", it) },
                    updatedAt = transcript.updatedAt?.let { parseDatetime("$owner updatedAt", it) },
                )
            }
        return NoteTranscript(noteId = noteId, document = document, job = jobOf(transcript.status, segments.isEmpty()))
    }

    private fun segmentOf(
        segment: NoteTranscriptSegment,
        owner: String,
    ): TranscriptSegment {
        val segmentOwner = "$owner segment ${segment.segmentId}"
        return TranscriptSegment(
            id = segment.segmentId,
            startMs = segment.startMs,
            endMs = segment.endMs,
            text = segment.text,
            words = segment.words.takeIf { it.isNotEmpty() }?.map { wordOf(it, segmentOwner) },
            speaker = segment.speakerId,
            confidence = segment.confidence?.toThousandths(segmentOwner),
            source = sourceValue(segment.source),
            final = segment.isFinal,
        )
    }

    private fun wordOf(
        word: NoteTranscriptWord,
        owner: String,
    ): TranscriptWord =
        TranscriptWord(
            text = word.text,
            normalizedText = word.normalizedText,
            startMs = word.startMs,
            endMs = word.endMs,
            confidence = word.confidence?.toThousandths("$owner word at ${word.startMs} ms"),
            speaker = word.speakerId,
        )

    private fun segmentOf(
        segment: TranscriptSegment,
        owner: String,
    ): NoteTranscriptSegment =
        NoteTranscriptSegment(
            segmentId = segment.id,
            text = segment.text,
            startMs = segment.startMs,
            endMs = segment.endMs,
            words = segment.words.orEmpty().map(::wordOf),
            speakerId = segment.speaker,
            confidence = segment.confidence?.fromThousandths(),
            source = segment.source?.let { sourceOf(it, "$owner segment ${segment.id}") } ?: TranscriptSource.IMPORTED,
            isFinal = segment.final ?: false,
        )

    private fun soundTagOf(
        tag: NoteSoundTag,
        owner: String,
    ): TranscriptSoundTag =
        TranscriptSoundTag(
            name = tag.name,
            startMs = tag.startMs,
            durationMs = tag.durationMs,
            confidence = tag.confidence?.toThousandths("$owner sound tag at ${tag.startMs} ms"),
        )

    private fun soundTagOf(tag: TranscriptSoundTag): NoteSoundTag =
        NoteSoundTag(
            name = tag.name,
            startMs = tag.startMs,
            durationMs = tag.durationMs,
            confidence = tag.confidence?.fromThousandths(),
        )

    private fun wordOf(word: TranscriptWord): NoteTranscriptWord =
        NoteTranscriptWord(
            text = word.text,
            normalizedText = word.normalizedText ?: word.text,
            startMs = word.startMs,
            endMs = word.endMs,
            confidence = word.confidence?.fromThousandths(),
            speakerId = word.speaker,
        )

    private inline fun <T> mapping(
        owner: String,
        build: () -> T,
    ): T =
        try {
            build()
        } catch (error: SpaceRecordMappingException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SpaceRecordMappingException("$owner: the record doesn't make a valid transcript", error)
        }

    companion object {
        /** The record key of the transcript of the audio note [noteId]. */
        fun transcriptRkeyOf(noteId: Uuid): String = "$noteId:$noteId"

        // A record that names no language gets the language LogDate transcribes in by default.
        private const val DEFAULT_LANGUAGE = "en-US"

        // A job that hasn't started has no segments, so a pending job with segments is already running.
        private fun statusValue(transcript: NoteTranscript): String =
            when (transcript.document.status) {
                TranscriptDocumentStatus.LISTENING -> {
                    if (transcript.job == TranscriptionStatus.PENDING && transcript.document.segments.isEmpty()) "pending" else "inProgress"
                }

                TranscriptDocumentStatus.REFINING -> {
                    "refining"
                }

                TranscriptDocumentStatus.FINAL -> {
                    "complete"
                }

                TranscriptDocumentStatus.FAILED -> {
                    "failed"
                }
            }

        private fun jobOf(
            status: String,
            noSegments: Boolean,
        ): TranscriptionStatus =
            when (status) {
                "pending" -> if (noSegments) TranscriptionStatus.PENDING else TranscriptionStatus.IN_PROGRESS
                "complete" -> TranscriptionStatus.COMPLETED
                "failed" -> TranscriptionStatus.FAILED
                else -> TranscriptionStatus.IN_PROGRESS
            }

        private fun documentStatusOf(
            value: String,
            owner: String,
        ): TranscriptDocumentStatus =
            when (value) {
                "pending", "inProgress" -> TranscriptDocumentStatus.LISTENING
                "refining" -> TranscriptDocumentStatus.REFINING
                "complete" -> TranscriptDocumentStatus.FINAL
                "failed" -> TranscriptDocumentStatus.FAILED
                else -> throw SpaceRecordMappingException("$owner: this version of LogDate doesn't know the status $value")
            }

        private fun sourceValue(source: TranscriptSource): String =
            when (source) {
                TranscriptSource.IMPORTED -> "imported"
                TranscriptSource.LOCAL_LIVE -> "localLive"
                TranscriptSource.CLOUD_LIVE -> "cloudLive"
                TranscriptSource.LOCAL_REFINEMENT -> "localRefinement"
                TranscriptSource.CLOUD_REFINEMENT -> "cloudRefinement"
            }

        private fun sourceOf(
            value: String,
            owner: String,
        ): TranscriptSource =
            when (value) {
                "imported" -> TranscriptSource.IMPORTED
                "localLive" -> TranscriptSource.LOCAL_LIVE
                "cloudLive" -> TranscriptSource.CLOUD_LIVE
                "localRefinement" -> TranscriptSource.LOCAL_REFINEMENT
                "cloudRefinement" -> TranscriptSource.CLOUD_REFINEMENT
                else -> throw SpaceRecordMappingException("$owner: this version of LogDate doesn't know the source $value")
            }
    }
}
