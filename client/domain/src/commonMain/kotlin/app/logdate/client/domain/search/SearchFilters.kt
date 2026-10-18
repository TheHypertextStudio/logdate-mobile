package app.logdate.client.domain.search

import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchScope
import app.logdate.util.getLocaleFirstDayOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Filters that narrow universal search results.
 *
 * @property contentTypes Restrict results to specific entity types. Null means all types.
 * @property dateRange Restrict results to a relative window. Defaults to [DateRangeFilter.AllTime].
 * @property personId Restrict results to those linked to this person, plus the person.
 * @property placeId Restrict results to those captured at this place, plus the place.
 * @property year Restrict results to this calendar year in the local time zone.
 * @property maxResults Maximum number of results to return.
 */
data class SearchFilters(
    val contentTypes: Set<SearchContentType>? = null,
    val dateRange: DateRangeFilter = DateRangeFilter.AllTime,
    val personId: Uuid? = null,
    val placeId: Uuid? = null,
    val year: Int? = null,
    val maxResults: Int = 50,
) {
    companion object {
        /** Filters with no restrictions — returns all content types, up to 50 results. */
        val Default = SearchFilters()
    }
}

/**
 * Converts these filters to the [SearchScope] a repository applies inside its query.
 *
 * The relative [SearchFilters.dateRange] and the calendar [SearchFilters.year] are resolved
 * against [now] and [timeZone] and combined to their overlap. When they do not overlap the
 * resulting window is empty (`createdFrom >= createdBefore`), so no result qualifies.
 */
fun SearchFilters.toScope(
    now: Instant,
    timeZone: TimeZone,
): SearchScope {
    val range = dateRange.takeIf { it != DateRangeFilter.AllTime }?.window(now, timeZone)
    val yearRange =
        year?.let { year ->
            InstantRange(
                from = LocalDate(year, 1, 1).atStartOfDayIn(timeZone),
                toExclusive = LocalDate(year + 1, 1, 1).atStartOfDayIn(timeZone),
            )
        }
    val windows = listOfNotNull(range, yearRange)
    return SearchScope(
        contentTypes = contentTypes,
        personId = personId,
        placeId = placeId,
        createdFrom = windows.maxOfOrNull { it.from },
        createdBefore = windows.minOfOrNull { it.toExclusive },
    )
}

/**
 * A relative date window applied to search results. Each value resolves to a concrete
 * `[from, to)` [Instant] range at query time via [window].
 */
enum class DateRangeFilter {
    AllTime,
    Today,
    ThisWeek,
    ThisMonth,
    ThisYear,
}

/** Inclusive-from, exclusive-to instant window. */
data class InstantRange(
    val from: Instant,
    val toExclusive: Instant,
)

/**
 * Resolves this filter to a concrete time window using [now] as the reference point.
 *
 * "This week" starts on [firstDayOfWeek], which defaults to the platform locale's first day so
 * search's "this week" matches every other "this week" in the app (e.g. rewinds). [AllTime]
 * returns a sentinel range from [Instant.DISTANT_PAST] to [Instant.DISTANT_FUTURE].
 */
fun DateRangeFilter.window(
    now: Instant,
    timeZone: TimeZone,
    firstDayOfWeek: DayOfWeek = getLocaleFirstDayOfWeek(),
): InstantRange {
    if (this == DateRangeFilter.AllTime) {
        return InstantRange(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
    }
    val today: LocalDate = now.toLocalDateTime(timeZone).date
    val (fromDate: LocalDate, toExclusiveDate: LocalDate) =
        when (this) {
            DateRangeFilter.Today -> {
                today to today.plus(1, DateTimeUnit.DAY)
            }

            DateRangeFilter.ThisWeek -> {
                val offset = (today.dayOfWeek.ordinal - firstDayOfWeek.ordinal + 7) % 7
                val startOfWeek = today.minus(offset, DateTimeUnit.DAY)
                startOfWeek to startOfWeek.plus(7, DateTimeUnit.DAY)
            }

            DateRangeFilter.ThisMonth -> {
                val startOfMonth = LocalDate(today.year, today.month, 1)
                startOfMonth to startOfMonth.plus(1, DateTimeUnit.MONTH)
            }

            DateRangeFilter.ThisYear -> {
                val startOfYear = LocalDate(today.year, 1, 1)
                startOfYear to startOfYear.plus(1, DateTimeUnit.YEAR)
            }

            DateRangeFilter.AllTime -> {
                error("handled above")
            }
        }
    return InstantRange(
        from = fromDate.atStartOfDayIn(timeZone),
        toExclusive = toExclusiveDate.atStartOfDayIn(timeZone),
    )
}
