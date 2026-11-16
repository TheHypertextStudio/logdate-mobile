package app.logdate.feature.search.ui

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.key
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

/** What the preview beside the search results is showing. */
sealed interface SearchSelection {
    /** A person, place, journal, rewind or postcard, shown as itself. */
    data class Result(
        val key: SearchResultKey,
    ) : SearchSelection

    /** A day, with the entry that matched marked when one was chosen. */
    data class Day(
        val date: LocalDate,
        val highlight: SearchResultKey?,
    ) : SearchSelection
}

/**
 * The selection that choosing [result] makes: entries select the day they belong to, with the entry
 * marked, and everything else selects itself.
 */
fun searchSelectionFor(
    result: SearchResult,
    timeZone: TimeZone,
): SearchSelection =
    when (result.contentType.shape) {
        SearchResultShape.DayItem -> SearchSelection.Day(result.created.toLocalDateTime(timeZone).date, result.key)
        SearchResultShape.Entity, SearchResultShape.Collection -> SearchSelection.Result(result.key)
    }

/** The selection to show when nothing is chosen yet: the first top match, collection or newest day. */
fun SearchResultGroups.firstSelection(timeZone: TimeZone): SearchSelection? =
    (topMatches.firstOrNull() ?: collections.firstOrNull())?.let { searchSelectionFor(it, timeZone) }
        ?: days.firstOrNull()?.let { SearchSelection.Day(it.date, null) }

/** Whether the result or day [selection] names is among these results. */
fun SearchResultGroups.contains(selection: SearchSelection): Boolean =
    when (selection) {
        is SearchSelection.Result -> {
            (topMatches + collections).any { it.key == selection.key }
        }

        is SearchSelection.Day -> {
            days.firstOrNull { it.date == selection.date }?.let { day ->
                selection.highlight == null || day.results.any { it.key == selection.highlight }
            } ?: false
        }
    }

private const val SEPARATOR = "|"

/** Writes [selection] as text so it can be saved with the screen's state; nothing selected is empty text. */
fun encodeSearchSelection(selection: SearchSelection?): String =
    when (selection) {
        null -> {
            ""
        }

        is SearchSelection.Result -> {
            listOf("r", selection.key.contentType.ftsValue, selection.key.uid.toString()).joinToString(SEPARATOR)
        }

        is SearchSelection.Day -> {
            listOf(
                "d",
                selection.date.toString(),
                selection.highlight
                    ?.contentType
                    ?.ftsValue
                    .orEmpty(),
                selection.highlight
                    ?.uid
                    ?.toString()
                    .orEmpty(),
            ).joinToString(SEPARATOR)
        }
    }

/** Reads text written by [encodeSearchSelection]; anything else reads as nothing selected. */
fun decodeSearchSelection(text: String): SearchSelection? {
    val parts = text.split(SEPARATOR)
    return runCatching {
        when (parts.firstOrNull()) {
            "r" -> {
                SearchSelection.Result(keyOf(parts[1], parts[2]) ?: return null)
            }

            "d" -> {
                SearchSelection.Day(
                    date = LocalDate.parse(parts[1]),
                    highlight = if (parts[2].isEmpty() && parts[3].isEmpty()) null else keyOf(parts[2], parts[3]) ?: return null,
                )
            }

            else -> {
                null
            }
        }
    }.getOrNull()
}

private fun keyOf(
    type: String,
    uid: String,
): SearchResultKey? {
    val contentType = SearchContentType.entries.firstOrNull { it.ftsValue == type } ?: return null
    return SearchResultKey(contentType, Uuid.parse(uid))
}
