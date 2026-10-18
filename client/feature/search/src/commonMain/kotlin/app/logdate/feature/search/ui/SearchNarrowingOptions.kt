package app.logdate.feature.search.ui

import app.logdate.client.domain.search.MonthBucket
import app.logdate.client.repository.search.SearchContentType
import kotlin.uuid.Uuid

/**
 * One choice in the narrowing options.
 *
 * @property value What choosing it narrows to.
 * @property label How to name it, or null when it stands for something that no longer has a name.
 * @property count How many results would remain if it were chosen.
 * @property selected Whether it is part of the narrowing already chosen.
 */
data class NarrowingOption<T>(
    val value: T,
    val label: String?,
    val count: Int,
    val selected: Boolean,
)

/**
 * One year on the time strip.
 *
 * @property months Result counts for January to December, in that order.
 */
data class NarrowingYear(
    val year: Int,
    val months: List<Int>,
    val selected: Boolean,
) {
    /** The number of results in the year. */
    val total: Int get() = months.sum()
}

/** Everything the narrowing panel and row show, ready to draw. */
data class SearchNarrowingOptions(
    val types: List<NarrowingOption<SearchContentType>> = emptyList(),
    val people: List<NarrowingOption<Uuid>> = emptyList(),
    val places: List<NarrowingOption<Uuid>> = emptyList(),
    val years: List<NarrowingYear> = emptyList(),
) {
    /** True when there is anything to choose. */
    val hasChoices: Boolean
        get() = types.isNotEmpty() || people.isNotEmpty() || places.isNotEmpty() || years.isNotEmpty()

    /** True when any narrowing is chosen. */
    val anyChosen: Boolean
        get() = types.any { it.selected } || people.any { it.selected } || places.any { it.selected } || years.any { it.selected }
}

private val typeOrder = SearchContentType.entries

/**
 * Arranges the counts into the options the narrowing panel and row show.
 *
 * An option that would leave no results is hidden unless it is already chosen, so no choice leads
 * to an empty list. People and places that no longer have a name are hidden for the same reason a
 * nameless option cannot be read, again unless chosen. People are left out unless [showPeople].
 */
fun buildNarrowingOptions(
    state: SearchNarrowingState,
    showPeople: Boolean,
): SearchNarrowingOptions {
    val counts = state.counts
    val filters = state.filters
    val chosenTypes = filters.contentTypes.orEmpty()

    val types =
        (counts.byType.keys + chosenTypes)
            .sortedBy { typeOrder.indexOf(it) }
            .map { type -> NarrowingOption(type, null, counts.byType[type] ?: 0, type in chosenTypes) }
            .filter { it.count > 0 || it.selected }

    val people =
        if (showPeople) {
            options(counts.byPerson, filters.personId) { state.labels.people[it]?.name }
        } else {
            emptyList()
        }
    val places = options(counts.byPlace, filters.placeId) { state.labels.places[it] }

    val monthYears = counts.byMonth.keys.map { it.year }
    val chosenYear = filters.year
    val allYears = monthYears + listOfNotNull(chosenYear)
    val years =
        if (allYears.isEmpty()) {
            emptyList()
        } else {
            (allYears.min()..allYears.max()).map { year ->
                NarrowingYear(
                    year = year,
                    months = (1..12).map { month -> counts.byMonth[MonthBucket(year, month)] ?: 0 },
                    selected = year == chosenYear,
                )
            }
        }

    return SearchNarrowingOptions(types = types, people = people, places = places, years = years)
}

private fun options(
    counts: Map<Uuid, Int>,
    chosen: Uuid?,
    nameOf: (Uuid) -> String?,
): List<NarrowingOption<Uuid>> =
    (counts.keys + listOfNotNull(chosen))
        .map { id -> NarrowingOption(id, nameOf(id), counts[id] ?: 0, id == chosen) }
        .filter { it.selected || (it.count > 0 && it.label != null) }
        .sortedWith(compareByDescending<NarrowingOption<Uuid>> { it.count }.thenBy { it.label.orEmpty() })
