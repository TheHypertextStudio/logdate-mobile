package app.logdate.client.repository.search

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Identifies one search result. A result id alone is not enough: the transcript and the sound tags
 * of one recording are separate results that share the recording's id.
 */
data class SearchResultKey(
    val contentType: SearchContentType,
    val uid: Uuid,
)

/** The key that identifies this result in a [SearchResultDetailsRepository.describe] map. */
val SearchResult.key: SearchResultKey
    get() = SearchResultKey(contentType, uid)

/**
 * The media, dates and covers a search result row shows beyond its text.
 *
 * The search index stores only text and a date, so these come from the stores that own each kind
 * of content. Notes have no detail.
 */
sealed interface SearchResultDetail {
    /** A photo. */
    data class Image(
        val uri: String,
    ) : SearchResultDetail

    /** A video and its length in milliseconds, if known. */
    data class Video(
        val uri: String,
        val durationMs: Long?,
    ) : SearchResultDetail

    /** A recording, for a transcript or a sound tag, and its length in milliseconds. */
    data class Audio(
        val durationMs: Long,
    ) : SearchResultDetail

    /** A journal's cover, if it has one, and how many entries it holds. */
    data class Journal(
        val coverUri: String?,
        val entryCount: Int,
    ) : SearchResultDetail

    /** The period a rewind covers and its first image, if it has one. */
    data class Rewind(
        val start: Instant,
        val end: Instant,
        val coverUri: String?,
    ) : SearchResultDetail

    /** A sticker's art. */
    data class Sticker(
        val imageUri: String,
    ) : SearchResultDetail

    /** A postcard's last edit and the photo it was made from, if it has one. */
    data class Postcard(
        val coverUri: String?,
        val modifiedAt: Instant,
    ) : SearchResultDetail

    /** Where a place is. */
    data class Place(
        val latitude: Double,
        val longitude: Double,
    ) : SearchResultDetail

    /** A person's photo, if they have one, and how they are related to the person searching. */
    data class Person(
        val photoUri: String?,
        val relationshipLabel: String?,
    ) : SearchResultDetail
}

/** One of a journal's latest entries: its text and when it was written. */
data class SearchPreviewEntry(
    val uid: Uuid,
    val text: String,
    val created: Instant,
)

/**
 * How much has been written at a place or with a person: entries, the days they fall on, the first
 * and latest, and entries per local calendar year, oldest year first.
 */
data class SearchEntryActivity(
    val entryCount: Int,
    val dayCount: Int,
    val first: Instant?,
    val latest: Instant?,
    val byYear: Map<Int, Int>,
)

/**
 * What the preview beside the results shows of a result beyond its row.
 *
 * Loaded when a result is chosen rather than with every row, since it reads the content itself.
 */
sealed interface SearchResultPreview {
    /** A journal's description, when it was started and the entries written most recently. */
    data class Journal(
        val description: String,
        val created: Instant,
        val latestEntries: List<SearchPreviewEntry>,
    ) : SearchResultPreview

    /** A rewind's highlights and the people and place it covers. */
    data class Rewind(
        val highlights: List<String>,
        val people: List<String>,
        val place: String?,
    ) : SearchResultPreview

    /** How much has been written at a place and the newest entries there. */
    data class Place(
        val activity: SearchEntryActivity,
        val recentEntries: List<SearchPreviewEntry>,
    ) : SearchResultPreview

    /** How much has been written with a person and the newest entries that mention them. */
    data class Person(
        val activity: SearchEntryActivity,
        val recentMentions: List<SearchPreviewEntry>,
    ) : SearchResultPreview
}

/** How a person is named in a narrowing option. */
data class SearchPersonLabel(
    val name: String,
    val photoUri: String?,
)

/**
 * The names of the people and places that narrowing options stand for, since the counts that
 * back the options identify them only by id.
 */
data class SearchFacetLabels(
    val people: Map<Uuid, SearchPersonLabel> = emptyMap(),
    val places: Map<Uuid, String> = emptyMap(),
) {
    companion object {
        /** Labels for no one and nowhere. */
        val Empty = SearchFacetLabels()
    }
}

/** Describes search results with the media, dates and covers their rows show. */
interface SearchResultDetailsRepository {
    /**
     * Describes a page of [results], looking each kind of result up together.
     *
     * Results that have no detail, or whose content has since been deleted, are left out of the map.
     */
    suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail>

    /**
     * Names the given people and places. Ids that no longer exist are left out. The default names
     * no one, for implementations that cannot look names up.
     */
    suspend fun labelFacets(
        personIds: Collection<Uuid>,
        placeIds: Collection<Uuid>,
    ): SearchFacetLabels = SearchFacetLabels.Empty

    /**
     * Reads what the preview of [key] shows beyond its row, or null when that kind of result has
     * no preview of its own or its content has since been deleted. The default has no previews.
     */
    suspend fun preview(key: SearchResultKey): SearchResultPreview? = null
}
