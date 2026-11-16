package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantSourceKind
import app.logdate.client.repository.assistants.AssistantSourceMetadata
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.first
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How many of the best matches in all of LogDate a search considers before dropping what isn't shared. */
const val DEVICE_ASSISTANT_SEARCH_CANDIDATES = 200

/** One search match an assistant on this device may receive. */
data class DeviceAssistantFinding(
    val id: Uuid,
    val kind: AssistantSourceKind,
    val content: String,
    val created: Instant,
) {
    override fun toString(): String = "DeviceAssistantFinding([redacted])"
}

/**
 * Reads LogDate for assistants on this device, limited to what the person allowed in
 * [DeviceAssistantAccess]. Only entries and voice-note transcripts in shared journals, those journals,
 * places, and people can be shared; rewinds, stickers, postcards, captions and sounds never are.
 * Nothing here writes or changes an entry.
 *
 * @param noteJournalLinks every (journal id, note id) membership, read once per search.
 */
class DeviceAssistantLibrary(
    private val search: SearchRepository,
    private val journals: JournalRepository,
    private val noteJournalLinks: suspend () -> List<Pair<Uuid, Uuid>>,
    private val eligibility: AssistantSourceEligibility = AssistantSourceEligibility(),
) {
    /**
     * The best matches for [query] that [access] allows, at most [limit]. Matches the person has not
     * shared are dropped before the limit, from the best [DEVICE_ASSISTANT_SEARCH_CANDIDATES] overall.
     */
    suspend fun search(
        access: DeviceAssistantAccess,
        query: String,
        limit: Int,
    ): List<DeviceAssistantFinding> {
        val candidates = search.searchRanked(query, DEVICE_ASSISTANT_SEARCH_CANDIDATES).first()
        if (candidates.isEmpty()) return emptyList()
        val memberships = noteJournalLinks().groupBy({ it.second }, { it.first })
        return candidates
            .asSequence()
            .filter { eligibility.allows(access.capabilities, access.contentScope, it.source(memberships)) }
            .take(limit)
            .map { DeviceAssistantFinding(it.uid, it.contentType.sourceKind, it.content, it.created) }
            .toList()
    }

    /** The journals whose entries [access] lets assistants read. */
    suspend fun journals(access: DeviceAssistantAccess): List<Journal> =
        journals.allJournalsObserved.first().filter { journal ->
            val source = AssistantSourceMetadata(journal.id, setOf(journal.id), AssistantSourceKind.JOURNAL, shared = false)
            eligibility.allows(access.capabilities, access.contentScope, source)
        }
}

private fun SearchResult.source(memberships: Map<Uuid, List<Uuid>>): AssistantSourceMetadata {
    val kind = contentType.sourceKind
    val journalIds =
        when (kind) {
            AssistantSourceKind.JOURNAL -> setOf(uid)
            AssistantSourceKind.TEXT, AssistantSourceKind.TRANSCRIPT -> memberships[uid].orEmpty().toSet()
            else -> emptySet()
        }
    return AssistantSourceMetadata(uid, journalIds, kind, shared = false)
}

private val SearchContentType.sourceKind: AssistantSourceKind
    get() =
        when (this) {
            SearchContentType.TEXT_NOTE -> AssistantSourceKind.TEXT

            SearchContentType.TRANSCRIPTION -> AssistantSourceKind.TRANSCRIPT

            SearchContentType.JOURNAL -> AssistantSourceKind.JOURNAL

            SearchContentType.PLACE -> AssistantSourceKind.PLACE

            SearchContentType.PERSON -> AssistantSourceKind.PERSON

            SearchContentType.MEDIA_CAPTION,
            SearchContentType.REWIND,
            SearchContentType.STICKER,
            SearchContentType.POSTCARD,
            SearchContentType.AMBIENT_SOUND,
            -> AssistantSourceKind.UNSUPPORTED
        }
