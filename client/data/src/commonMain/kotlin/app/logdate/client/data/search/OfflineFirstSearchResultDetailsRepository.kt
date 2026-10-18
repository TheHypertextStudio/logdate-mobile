package app.logdate.client.data.search

import app.logdate.client.database.dao.SearchDetailsDao
import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacetLabels
import app.logdate.client.repository.search.SearchPersonLabel
import app.logdate.client.repository.search.SearchPreviewEntry
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultDetailsRepository
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.shared.model.Rewind
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Finds a rewind by id, or null when there is none. */
fun interface RewindLookup {
    suspend fun rewind(id: Uuid): Rewind?
}

/** Describes search results from the local stores that own their content. */
class OfflineFirstSearchResultDetailsRepository(
    private val detailsDao: SearchDetailsDao,
    private val personDao: PersonDao,
    private val rewinds: RewindLookup,
) : SearchResultDetailsRepository {
    override suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail> {
        if (results.isEmpty()) return emptyMap()
        val idsByType: Map<SearchContentType, List<Uuid>> =
            results.groupBy({ it.contentType }, { it.uid }).mapValues { (_, ids) -> ids.distinct() }

        return buildMap {
            idsByType[SearchContentType.MEDIA_CAPTION]?.let { putMedia(it) }
            idsByType[SearchContentType.TRANSCRIPTION]?.let { putAudio(SearchContentType.TRANSCRIPTION, it) }
            idsByType[SearchContentType.AMBIENT_SOUND]?.let { putAudio(SearchContentType.AMBIENT_SOUND, it) }
            idsByType[SearchContentType.JOURNAL]?.let { putJournals(it) }
            idsByType[SearchContentType.REWIND]?.let { putRewinds(it) }
            idsByType[SearchContentType.STICKER]?.let { putStickers(it) }
            idsByType[SearchContentType.POSTCARD]?.let { putPostcards(it) }
            idsByType[SearchContentType.PLACE]?.let { putPlaces(it) }
            idsByType[SearchContentType.PERSON]?.let { putPeople(it) }
        }
    }

    override suspend fun labelFacets(
        personIds: Collection<Uuid>,
        placeIds: Collection<Uuid>,
    ): SearchFacetLabels {
        if (personIds.isEmpty() && placeIds.isEmpty()) return SearchFacetLabels.Empty
        val places =
            if (placeIds.isEmpty()) {
                emptyMap()
            } else {
                detailsDao.placeNames(placeIds.map(Uuid::toString)).associate { Uuid.parse(it.id) to it.name }
            }
        val people =
            personIds
                .distinct()
                .mapNotNull { id ->
                    personDao.getById(id)?.let { id to SearchPersonLabel(it.name, it.photoUri) }
                }.toMap()
        return SearchFacetLabels(people = people, places = places)
    }

    override suspend fun preview(key: SearchResultKey): SearchResultPreview? =
        when (key.contentType) {
            SearchContentType.JOURNAL -> journalPreview(key.uid)
            SearchContentType.REWIND -> rewindPreview(key.uid)
            else -> null
        }

    private suspend fun journalPreview(id: Uuid): SearchResultPreview.Journal? {
        val journal = detailsDao.journalPreview(id.toString()) ?: return null
        val entries =
            detailsDao.latestJournalEntries(id.toString(), JOURNAL_PREVIEW_ENTRIES).map {
                SearchPreviewEntry(Uuid.parse(it.uid), it.content, Instant.fromEpochMilliseconds(it.created))
            }
        return SearchResultPreview.Journal(journal.description, Instant.fromEpochMilliseconds(journal.created), entries)
    }

    private suspend fun rewindPreview(id: Uuid): SearchResultPreview.Rewind? =
        try {
            rewinds.rewind(id)?.toSearchPreview()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w("Could not read rewind $id for its search preview", error)
            null
        }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putMedia(ids: List<Uuid>) {
        val strings = ids.map(Uuid::toString)
        detailsDao.imageDetails(strings).forEach {
            put(key(SearchContentType.MEDIA_CAPTION, it.uid), SearchResultDetail.Image(it.contentUri))
        }
        detailsDao.videoDetails(strings).forEach {
            put(key(SearchContentType.MEDIA_CAPTION, it.uid), SearchResultDetail.Video(it.contentUri, it.durationMs))
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putAudio(
        type: SearchContentType,
        ids: List<Uuid>,
    ) {
        detailsDao.audioDetails(ids.map(Uuid::toString)).forEach {
            put(key(type, it.uid), SearchResultDetail.Audio(it.durationMs))
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putJournals(ids: List<Uuid>) {
        detailsDao.journalDetails(ids.map(Uuid::toString)).forEach {
            put(key(SearchContentType.JOURNAL, it.id), SearchResultDetail.Journal(it.coverUri, it.entryCount))
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putRewinds(ids: List<Uuid>) {
        detailsDao.rewindDetails(ids.map(Uuid::toString)).forEach {
            put(
                key(SearchContentType.REWIND, it.uid),
                SearchResultDetail.Rewind(
                    start = Instant.fromEpochMilliseconds(it.startDate),
                    end = Instant.fromEpochMilliseconds(it.endDate),
                    coverUri = it.coverUri,
                ),
            )
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putStickers(ids: List<Uuid>) {
        detailsDao.stickerDetails(ids.map(Uuid::toString)).forEach {
            put(key(SearchContentType.STICKER, it.id), SearchResultDetail.Sticker(it.imageUri))
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putPostcards(ids: List<Uuid>) {
        detailsDao.postcardDetails(ids.map(Uuid::toString)).forEach {
            put(
                key(SearchContentType.POSTCARD, it.id),
                SearchResultDetail.Postcard(coverUri = it.coverUri, modifiedAt = Instant.fromEpochMilliseconds(it.modifiedAt)),
            )
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putPlaces(ids: List<Uuid>) {
        detailsDao.placeDetails(ids.map(Uuid::toString)).forEach {
            put(key(SearchContentType.PLACE, it.id), SearchResultDetail.Place(it.latitude, it.longitude))
        }
    }

    private suspend fun MutableMap<SearchResultKey, SearchResultDetail>.putPeople(ids: List<Uuid>) {
        ids.forEach { id ->
            val person = personDao.getById(id) ?: return@forEach
            put(
                SearchResultKey(SearchContentType.PERSON, id),
                SearchResultDetail.Person(person.photoUri, person.relationshipLabel),
            )
        }
    }

    private fun key(
        type: SearchContentType,
        id: String,
    ) = SearchResultKey(type, Uuid.parse(id))
}

private const val JOURNAL_PREVIEW_ENTRIES = 5
private const val REWIND_PREVIEW_HIGHLIGHTS = 3

/** What a search preview shows of a rewind: its quoted lines and milestones, people and main place. */
internal fun Rewind.toSearchPreview(): SearchResultPreview.Rewind {
    val metadata = metadata
    return SearchResultPreview.Rewind(
        highlights =
            (metadata?.highlightedQuotes.orEmpty().map { it.text } + metadata?.milestones.orEmpty())
                .filter { it.isNotBlank() }
                .take(REWIND_PREVIEW_HIGHLIGHTS),
        people = metadata?.peopleHighlighted.orEmpty().filter { it.isNotBlank() },
        place = metadata?.locationSummary?.primaryLocation?.takeIf { it.isNotBlank() },
    )
}
