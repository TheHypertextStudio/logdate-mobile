@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.client.data.people

import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.shared.model.InferredPersonEvidenceSourceType
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal suspend fun loadPeopleInferenceArtifacts(
    textNoteDao: TextNoteDao,
    transcriptionDao: TranscriptionDao,
    eventDao: EventDao,
): List<InferenceArtifact> {
    val textArtifacts =
        textNoteDao.getAll().filter { it.contentFormat == "text" }.map { note ->
            InferenceArtifact(
                sourceType = InferredPersonEvidenceSourceType.ENTRY_TEXT,
                sourceId = note.uid,
                text = note.content,
                createdAt = note.created,
            )
        }
    val transcriptArtifacts =
        transcriptionDao
            .getAllTranscriptions()
            .filter { it.status == TranscriptionStatus.COMPLETED && !it.text.isNullOrBlank() }
            .map { transcription ->
                InferenceArtifact(
                    sourceType = InferredPersonEvidenceSourceType.TRANSCRIPT,
                    sourceId = transcription.noteId,
                    text = requireNotNull(transcription.text),
                    createdAt = transcription.lastUpdated,
                )
            }
    val eventArtifacts =
        eventDao.getAll().map { event ->
            val eventText =
                buildString {
                    append(event.title)
                    event.description?.takeIf(String::isNotBlank)?.let {
                        append(". ")
                        append(it)
                    }
                }
            InferenceArtifact(
                sourceType = InferredPersonEvidenceSourceType.EVENT_TEXT,
                sourceId = event.id,
                text = eventText,
                createdAt = event.startTime,
            )
        }
    return textArtifacts + transcriptArtifacts + eventArtifacts
}

internal data class InferenceArtifact(
    val sourceType: InferredPersonEvidenceSourceType,
    val sourceId: Uuid,
    val text: String,
    val createdAt: Instant,
)

internal fun extractPotentialPersonNames(text: String): List<String> =
    candidateNameRegex
        .findAll(text)
        .map { it.value.trim() }
        .filterNot { candidate ->
            candidate.contains('\n') ||
                (candidate.indexOf(' ') == -1 && candidate in ignoredSingleWordCandidates)
        }.distinct()
        .toList()

/**
 * Finds a person's name or any alias as a whole word in lowercased text. The pattern is compiled
 * once per person for a whole refresh rather than once per entry, person and term.
 */
internal class CanonicalPersonMatcher(
    val person: PersonEntity,
) {
    private val pattern: Regex? =
        (listOf(person.name) + person.aliases)
            .map(::normalizePersonKey)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "|") { Regex.escape(it) }
            ?.let { terms -> Regex("""(?<!\p{L})(?:$terms)(?!\p{L})""") }

    fun matches(normalizedText: String): Boolean = pattern?.containsMatchIn(normalizedText) == true
}

internal fun previewEvidenceLabel(text: String): String =
    text
        .trim()
        .replace(whitespaceRun, " ")
        .take(MAX_EVIDENCE_LABEL_LENGTH)

private val whitespaceRun = Regex("\\s+")

private const val MAX_EVIDENCE_LABEL_LENGTH = 120

private val candidateNameRegex = Regex("""\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2}\b""")
private val ignoredSingleWordCandidates =
    setOf(
        "After",
        "Before",
        "April",
        "August",
        "Friday",
        "January",
        "July",
        "June",
        "LogDate",
        "March",
        "May",
        "Monday",
        "Saturday",
        "Sunday",
        "Thursday",
        "Tuesday",
        "Wednesday",
    )
