@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.client.data.people

import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.shared.model.InferredPersonClusterStatus
import app.logdate.shared.model.InferredPersonEvidenceSourceType
import app.logdate.shared.model.PersonLinkProvenance
import app.logdate.shared.model.PersonLinkStatus
import app.logdate.shared.model.PersonLinkTargetType
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The suggestions and machine-made links one refresh stores in place of the previous ones. */
internal data class PeopleInferencePlan(
    val clusters: List<InferredPersonClusterEntity>,
    val evidence: List<InferredPersonEvidenceEntity>,
    val links: List<PersonLinkEntity>,
)

/**
 * Works out what a refresh should store, without touching the database.
 *
 * A name that is not in the directory becomes a suggestion once [MIN_SUGGESTION_MENTIONS] entries,
 * transcripts or events mention it; nothing is ever added to the directory here. Several mentions
 * in one entry count once. A name in [suppressedNames] is never suggested. A suggestion that is already open keeps its identifier so
 * that a review started before the refresh still applies to it.
 */
internal fun planPeopleInference(
    people: List<PersonEntity>,
    suppressedNames: Set<String>,
    openClusters: List<InferredPersonClusterEntity>,
    artifacts: List<InferenceArtifact>,
    now: Instant,
): PeopleInferencePlan {
    val knownPeople = KnownPeopleMatcher(people)
    val knownNames = people.flatMap { it.aliases + it.name }.mapTo(mutableSetOf(), ::normalizePersonKey)
    val artifactsByPerson = mutableMapOf<Uuid, MutableList<InferenceArtifact>>()
    val mentionsByName = linkedMapOf<String, MutableList<ArtifactMention>>()

    val artifactWords = artifacts.map { artifact -> artifact to tokenizeWords(artifact.text) }
    val capitalizedInJournal = artifactWords.flatMapTo(mutableSetOf()) { (_, words) -> wordsCapitalizedInsideSentence(words) }

    artifactWords.forEach { (artifact, words) ->
        knownPeople.matchingPersonIds(words).forEach { personId ->
            artifactsByPerson.getOrPut(personId) { mutableListOf() }.add(artifact)
        }
        findPotentialPersonMentions(words, capitalizedInJournal).forEach { mention ->
            val normalizedName = normalizePersonKey(mention.name)
            if (normalizedName in suppressedNames || normalizedName in knownNames) return@forEach
            mentionsByName.getOrPut(normalizedName) { mutableListOf() }.add(ArtifactMention(artifact, mention))
        }
    }

    val openClusterByName = openClusters.associateBy(InferredPersonClusterEntity::normalizedName)
    val suggestions =
        mentionsByName
            .mapValues { (_, mentions) -> mentions.distinctBy { it.artifact.sourceId } }
            .filterValues { it.size >= MIN_SUGGESTION_MENTIONS }
            .map { (normalizedName, mentions) -> suggestion(normalizedName, mentions, openClusterByName[normalizedName], now) }

    return PeopleInferencePlan(
        clusters = suggestions.map(Suggestion::cluster),
        evidence = suggestions.flatMap(Suggestion::evidence),
        links = artifactsByPerson.flatMap { (personId, matched) -> machineLinks(personId, matched, now) },
    )
}

/** Builds the link a person keeps to the entry or event behind one piece of [evidence]. */
internal fun confirmedLink(
    personId: Uuid,
    evidence: InferredPersonEvidenceEntity,
    now: Instant,
): PersonLinkEntity? {
    val sourceType = runCatching { InferredPersonEvidenceSourceType.valueOf(evidence.sourceType) }.getOrNull()
    val targetType = sourceType?.linkTargetType() ?: return null
    return PersonLinkEntity(
        id = Uuid.random(),
        personId = personId,
        targetType = targetType.name,
        targetId = evidence.sourceId,
        provenance = PersonLinkProvenance.EXPLICIT.name,
        confidence = CONFIRMED_LINK_CONFIDENCE,
        status = PersonLinkStatus.ACTIVE.name,
        created = now,
        lastUpdated = now,
    )
}

/**
 * Finds people already in the directory in the words of a text.
 *
 * A name or alias matches whole words in any letter case, except that its first word must not be
 * written in lowercase: "will" in a sentence is not the person named Will. A one-word name that is
 * also an ordinary word does not match at the start of a sentence, where "Will we go?" is
 * capitalized whatever it means. A one-word name that is also a month or weekday matches only
 * right after a word such as "with" or "met", so "in April" is not the person named April.
 */
internal class KnownPeopleMatcher(
    people: List<PersonEntity>,
) {
    private val termsByFirstWord: Map<String, List<PersonTerm>> =
        people
            .flatMap { person -> (person.aliases + person.name).mapNotNull { name -> personTerm(person.id, name) } }
            .groupBy { it.words.first() }

    fun matchingPersonIds(words: List<TextWord>): Set<Uuid> {
        val matches = mutableSetOf<Uuid>()
        words.forEachIndexed { index, word ->
            if (word.text.first().isLowerCase()) return@forEachIndexed
            val terms = termsByFirstWord[word.text.lowercase()] ?: return@forEachIndexed
            terms.filter { it.matchesAt(words, index) }.mapTo(matches, PersonTerm::personId)
        }
        return matches
    }
}

private class PersonTerm(
    val personId: Uuid,
    val words: List<String>,
) {
    fun matchesAt(
        textWords: List<TextWord>,
        index: Int,
    ): Boolean {
        if (index + words.size > textWords.size) return false
        if (words.size == 1 && !oneWordNameCountsAt(textWords, index)) return false
        return words.indices.all { offset -> textWords[index + offset].text.lowercase() == words[offset] }
    }

    private fun oneWordNameCountsAt(
        textWords: List<TextWord>,
        index: Int,
    ): Boolean {
        val name = words.single()
        if (isCalendarWord(name)) return followsPersonCue(textWords, index)
        return !(textWords[index].startsSentence && isOrdinaryWordName(name))
    }
}

private class ArtifactMention(
    val artifact: InferenceArtifact,
    val mention: NameMention,
)

private class Suggestion(
    val cluster: InferredPersonClusterEntity,
    val evidence: List<InferredPersonEvidenceEntity>,
)

private fun personTerm(
    personId: Uuid,
    name: String,
): PersonTerm? {
    val words = tokenizeWords(name).map { it.text.lowercase() }
    if (words.isEmpty()) return null
    return PersonTerm(personId, words)
}

private fun suggestion(
    normalizedName: String,
    mentions: List<ArtifactMention>,
    openCluster: InferredPersonClusterEntity?,
    now: Instant,
): Suggestion {
    val clusterId = openCluster?.id ?: Uuid.random()
    return Suggestion(
        cluster =
            InferredPersonClusterEntity(
                id = clusterId,
                displayNameHint = mentions.first().mention.name,
                normalizedName = normalizedName,
                status = InferredPersonClusterStatus.OPEN.name,
                created = openCluster?.created ?: now,
                lastUpdated = mentions.maxOf { it.artifact.createdAt },
            ),
        evidence =
            mentions.map { mention ->
                InferredPersonEvidenceEntity(
                    id = Uuid.random(),
                    clusterId = clusterId,
                    sourceType = mention.artifact.sourceType.name,
                    sourceId = mention.artifact.sourceId,
                    label = previewEvidenceLabel(mention.artifact.text, mention.mention),
                    confidence = SUGGESTION_EVIDENCE_CONFIDENCE,
                    created = mention.artifact.createdAt,
                )
            },
    )
}

private fun machineLinks(
    personId: Uuid,
    artifacts: List<InferenceArtifact>,
    now: Instant,
): List<PersonLinkEntity> {
    val targets =
        artifacts
            .mapNotNull { artifact -> artifact.sourceType.linkTargetType()?.let { targetType -> targetType to artifact.sourceId } }
            .distinct()
    if (targets.size < MIN_MACHINE_LINK_TARGETS) return emptyList()
    return targets.map { (targetType, targetId) ->
        PersonLinkEntity(
            id = Uuid.random(),
            personId = personId,
            targetType = targetType.name,
            targetId = targetId,
            provenance = PersonLinkProvenance.INFERRED.name,
            confidence = MACHINE_LINK_CONFIDENCE,
            status = PersonLinkStatus.ACTIVE.name,
            created = now,
            lastUpdated = now,
        )
    }
}

private fun InferredPersonEvidenceSourceType.linkTargetType(): PersonLinkTargetType? =
    when (this) {
        InferredPersonEvidenceSourceType.ENTRY_TEXT,
        InferredPersonEvidenceSourceType.TRANSCRIPT,
        -> PersonLinkTargetType.ENTRY

        InferredPersonEvidenceSourceType.EVENT_TEXT -> PersonLinkTargetType.EVENT

        InferredPersonEvidenceSourceType.CONTACT,
        InferredPersonEvidenceSourceType.CO_OCCURRENCE,
        -> null
    }

private const val CONFIRMED_LINK_CONFIDENCE = 1.0
private const val MACHINE_LINK_CONFIDENCE = 0.94
private const val MIN_MACHINE_LINK_TARGETS = 2
private const val MIN_SUGGESTION_MENTIONS = 2
private const val SUGGESTION_EVIDENCE_CONFIDENCE = 0.72
