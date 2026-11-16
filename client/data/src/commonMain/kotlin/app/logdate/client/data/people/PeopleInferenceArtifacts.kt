@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.client.data.people

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.EventDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.shared.model.InferredPersonEvidenceSourceType
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Reads the texts a refresh looks for names in: text entries that are not deleted, completed
 * transcripts of voice notes that are not deleted, and the titles and descriptions of events when
 * [eventDao] is given.
 */
internal suspend fun loadPeopleInferenceArtifacts(
    textNoteDao: TextNoteDao,
    audioNoteDao: AudioNoteDao,
    transcriptionDao: TranscriptionDao,
    eventDao: EventDao?,
): List<InferenceArtifact> {
    val textArtifacts =
        textNoteDao
            .getAll()
            .filter { it.contentFormat == "text" && it.deletedAt == null }
            .map { note ->
                InferenceArtifact(
                    sourceType = InferredPersonEvidenceSourceType.ENTRY_TEXT,
                    sourceId = note.uid,
                    text = note.content,
                    createdAt = note.created,
                )
            }
    val voiceNoteIds =
        audioNoteDao
            .getAll()
            .filter { it.deletedAt == null }
            .mapTo(mutableSetOf()) { it.uid }
    val transcriptArtifacts =
        transcriptionDao
            .getAllTranscriptions()
            .filter { it.noteId in voiceNoteIds }
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
        eventDao?.getAll().orEmpty().map { event ->
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

/** A possible name and where it first appears in a text. */
internal data class NameMention(
    val name: String,
    val start: Int,
    val endExclusive: Int,
)

/**
 * The words of a text that are written like a name somewhere other than the start of a sentence.
 */
internal fun wordsCapitalizedInsideSentence(words: List<TextWord>): Set<String> =
    words
        .filter { it.isNameLike && !it.startsSentence }
        .mapTo(mutableSetOf(), TextWord::text)

/**
 * Finds runs of up to [MAX_NAME_WORDS] capitalized words that could be a person's name, reporting
 * each distinct name once.
 *
 * A word that starts a sentence is capitalized whatever it means, so it only counts when the same
 * word is in [capitalizedInsideSentence]. A refresh passes the words capitalized inside a sentence
 * anywhere in the journal, so "Sarah Chen called." counts once another entry says "lunch with
 * Sarah". Months, weekdays, pronouns, holidays, nationalities and common sentence openers never
 * count, and they end a run instead of joining the name beside them. The one exception is a month
 * that is also a given name, which starts a name right after a word such as "with" or "met".
 */
internal fun findPotentialPersonMentions(
    words: List<TextWord>,
    capitalizedInsideSentence: Set<String> = wordsCapitalizedInsideSentence(words),
): List<NameMention> {
    val mentions = linkedMapOf<String, NameMention>()
    val currentRun = mutableListOf<TextWord>()

    fun endRun() {
        val mention = currentRun.toNameMention()
        currentRun.clear()
        if (mention == null || mention.name in mentions) return
        mentions[mention.name] = mention
    }

    words.forEachIndexed { index, word ->
        if (!word.continuesPreviousWord) endRun()
        val startsNameAfterCue = currentRun.isEmpty() && isMonthNamingPerson(words, index)
        if (!startsNameAfterCue && !word.canBePartOfName(capitalizedInsideSentence)) {
            endRun()
            return@forEachIndexed
        }
        currentRun += word
    }
    endRun()
    return mentions.values.toList()
}

/**
 * Whether the word at [index] follows a person cue directly, with nothing but spaces between, and
 * is not followed by a day of the month. "Lunch with April" passes; "in April" and "met April 5"
 * do not.
 */
internal fun followsPersonCue(
    words: List<TextWord>,
    index: Int,
): Boolean {
    val word = words[index]
    val previous = words.getOrNull(index - 1) ?: return false
    if (!word.continuesPreviousWord || !isPersonCue(previous.text)) return false
    val next = words.getOrNull(index + 1) ?: return true
    return !(next.continuesPreviousWord && next.text.first().isDigit())
}

private fun isMonthNamingPerson(
    words: List<TextWord>,
    index: Int,
): Boolean {
    val word = words[index]
    if (!word.isNameLike || !isMonthUsedAsName(word.text)) return false
    return followsPersonCue(words, index)
}

/**
 * Builds a one-line excerpt of [text] of at most [MAX_EVIDENCE_LABEL_LENGTH] characters that
 * contains [mention], cut at word boundaries and marked with an ellipsis where text was left out.
 */
internal fun previewEvidenceLabel(
    text: String,
    mention: NameMention,
): String {
    val context = (MAX_EVIDENCE_LABEL_LENGTH - (mention.endExclusive - mention.start)).coerceAtLeast(0)
    val roughStart = (mention.start - context / 2).coerceAtLeast(0)
    val roughEnd = (mention.endExclusive + context - (mention.start - roughStart)).coerceAtMost(text.length)
    val start = wordStartAtOrAfter(text, roughStart, limit = mention.start)
    val end = wordEndAtOrBefore(text, roughEnd, limit = mention.endExclusive)
    val excerpt = text.substring(start, end).trim().replace(whitespaceRun, " ")
    return buildString {
        if (text.substring(0, start).isNotBlank()) append(ELLIPSIS)
        append(excerpt)
        if (text.substring(end).isNotBlank()) append(ELLIPSIS)
    }
}

private fun TextWord.canBePartOfName(capitalizedInsideSentence: Set<String>): Boolean {
    if (!isNameLike) return false
    if (isIgnoredNameWord(text)) return false
    if (!startsSentence) return true
    return text in capitalizedInsideSentence && !isOrdinaryWordName(text)
}

private fun List<TextWord>.toNameMention(): NameMention? {
    if (isEmpty() || size > MAX_NAME_WORDS) return null
    return NameMention(
        name = joinToString(" ", transform = TextWord::text),
        start = first().start,
        endExclusive = last().endExclusive,
    )
}

private fun wordStartAtOrAfter(
    text: String,
    index: Int,
    limit: Int,
): Int {
    var start = index
    while (start in 1 until limit && !text[start - 1].isWhitespace()) start += 1
    return start
}

private fun wordEndAtOrBefore(
    text: String,
    index: Int,
    limit: Int,
): Int {
    var end = index
    while (end in (limit + 1) until text.length && !text[end].isWhitespace()) end -= 1
    return end
}

private const val ELLIPSIS = '…'
private const val MAX_EVIDENCE_LABEL_LENGTH = 120
private const val MAX_NAME_WORDS = 3

private val whitespaceRun = Regex("\\s+")
