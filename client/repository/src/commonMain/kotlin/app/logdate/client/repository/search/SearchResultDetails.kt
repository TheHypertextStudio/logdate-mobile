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

/** Describes search results with the media, dates and covers their rows show. */
interface SearchResultDetailsRepository {
    /**
     * Describes a page of [results], looking each kind of result up together.
     *
     * Results that have no detail, or whose content has since been deleted, are left out of the map.
     */
    suspend fun describe(results: List<SearchResult>): Map<SearchResultKey, SearchResultDetail>
}
