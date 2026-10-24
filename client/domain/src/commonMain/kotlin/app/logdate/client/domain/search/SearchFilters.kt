package app.logdate.client.domain.search

import app.logdate.client.repository.search.SearchContentType
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

/**
 * Filters that narrow universal search results.
 *
 * @property contentTypes Restrict results to specific entity types. Null means all types.
 * @property dateRange Restrict results to a relative window. Defaults to [DateRangeFilter.AllTime].
 * @property maxResults Maximum number of results to return.
 */
data class SearchFilters(
    val contentTypes: Set<SearchContentType>? = null,
    val dateRange: DateRangeFilter = DateRangeFilter.AllTime,
    val maxResults: Int = 50,
) {
    companion object {
        /** Filters with no restrictions — returns all content types, up to 50 results. */
        val Default = SearchFilters()
    }
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
