@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalLayoutApi::class)

package app.logdate.feature.search.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.PanelGroup
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.search_narrowing_clear
import logdate.client.feature.search.generated.resources.search_narrowing_people
import logdate.client.feature.search.generated.resources.search_narrowing_places
import logdate.client.feature.search.generated.resources.search_narrowing_types
import logdate.client.feature.search.generated.resources.search_narrowing_unnamed
import logdate.client.feature.search.generated.resources.search_narrowing_when
import logdate.client.feature.search.generated.resources.search_year_result_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

const val SEARCH_NARROWING_TAG = "search_narrowing"
const val SEARCH_TIME_STRIP_TAG = "search_time_strip"
const val SEARCH_NARROWING_CLEAR_TAG = "search_narrowing_clear"

/** How the narrowing options are arranged. */
enum class SearchNarrowingLayout {
    /** A scrolling column of tonal groups, for windows with room beside the results. */
    Panel,

    /** The time strip over one scrolling row of chips, for the space above the results. */
    Row,
}

/**
 * The narrowing options of a search: when, who, where and what type, each with how many results
 * choosing it would leave.
 *
 * Options are Material 3 filter chips. Choosing one that is already chosen clears it.
 */
@Composable
fun SearchNarrowing(
    options: SearchNarrowingOptions,
    layout: SearchNarrowingLayout,
    onToggleType: (SearchContentType) -> Unit,
    onTogglePerson: (Uuid) -> Unit,
    onTogglePlace: (Uuid) -> Unit,
    onToggleYear: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!options.hasChoices) return
    val unnamed = stringResource(Res.string.search_narrowing_unnamed)
    val people =
        options.people.map { option ->
            Choice("person_${option.value}", option.label ?: unnamed, option.count, option.selected) { onTogglePerson(option.value) }
        }
    val places =
        options.places.map { option ->
            Choice("place_${option.value}", option.label ?: unnamed, option.count, option.selected) { onTogglePlace(option.value) }
        }
    val types =
        options.types.map { option ->
            Choice("type_${option.value.ftsValue}", stringResource(option.value.labelKey()), option.count, option.selected) {
                onToggleType(option.value)
            }
        }
    when (layout) {
        SearchNarrowingLayout.Panel -> NarrowingPanel(options, people, places, types, onToggleYear, onClear, modifier)
        SearchNarrowingLayout.Row -> NarrowingRow(options, people + places + types, onToggleYear, onClear, modifier)
    }
}

private class Choice(
    val key: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun NarrowingPanel(
    options: SearchNarrowingOptions,
    people: List<Choice>,
    places: List<Choice>,
    types: List<Choice>,
    onToggleYear: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier =
            modifier
                .testTag(SEARCH_NARROWING_TAG)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
    ) {
        if (options.years.isNotEmpty()) {
            PanelGroup(Modifier.fillMaxWidth()) {
                GroupHeading(stringResource(Res.string.search_narrowing_when))
                SearchTimeStrip(options.years, onToggleYear)
            }
        }
        ChipGroup(stringResource(Res.string.search_narrowing_people), people)
        ChipGroup(stringResource(Res.string.search_narrowing_places), places)
        ChipGroup(stringResource(Res.string.search_narrowing_types), types)
        if (options.anyChosen) {
            TextButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_NARROWING_CLEAR_TAG)) {
                Text(stringResource(Res.string.search_narrowing_clear))
            }
        }
    }
}

@Composable
private fun GroupHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChipGroup(
    title: String,
    choices: List<Choice>,
) {
    if (choices.isEmpty()) return
    PanelGroup(Modifier.fillMaxWidth()) {
        GroupHeading(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            choices.forEach { CountChip(it) }
        }
    }
}

@Composable
private fun NarrowingRow(
    options: SearchNarrowingOptions,
    choices: List<Choice>,
    onToggleYear: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = modifier.testTag(SEARCH_NARROWING_TAG)) {
        if (options.years.isNotEmpty()) {
            PanelGroup(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                SearchTimeStrip(options.years, onToggleYear)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (options.anyChosen) {
                item(key = "clear") {
                    TextButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_NARROWING_CLEAR_TAG)) {
                        Text(stringResource(Res.string.search_narrowing_clear))
                    }
                }
            }
            items(choices, key = { it.key }) { CountChip(it) }
        }
    }
}

/** A Material 3 filter chip that names an option and shows how many results choosing it would leave. */
@Composable
private fun CountChip(choice: Choice) {
    FilterChip(
        selected = choice.selected,
        onClick = choice.onClick,
        label = { Text(choice.label) },
        leadingIcon =
            if (choice.selected) {
                { Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        trailingIcon = { Text(choice.count.toString(), style = MaterialTheme.typography.labelSmall) },
    )
}

/**
 * A strip of months grouped by year, taller where more results fall. Choosing a year narrows the
 * search to it.
 */
@Composable
fun SearchTimeStrip(
    years: List<NarrowingYear>,
    onToggleYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anySelected = years.any { it.selected }
    val busiest = years.maxOfOrNull { year -> year.months.max() }?.coerceAtLeast(1) ?: 1
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = modifier.fillMaxWidth().testTag(SEARCH_TIME_STRIP_TAG),
    ) {
        years.forEach { year ->
            val description = pluralStringResource(Res.plurals.search_year_result_count, year.total, year.year, year.total)
            YearColumn(
                year,
                busiest,
                faded = anySelected && !year.selected,
                description,
                onToggle = { onToggleYear(year.year) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun YearColumn(
    year: NarrowingYear,
    busiest: Int,
    faded: Boolean,
    description: String,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bar = scheme.primary.copy(alpha = if (faded) 0.38f else 1f)
    val track = scheme.outlineVariant
    Surface(
        selected = year.selected,
        onClick = onToggle,
        shape = MaterialTheme.shapes.small,
        color = if (year.selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (year.selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        modifier = modifier.focusableWithRing().semantics { contentDescription = description },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                val slot = size.width / year.months.size
                val width = slot * 0.62f
                val corner = CornerRadius(width / 2)
                year.months.forEachIndexed { index, count ->
                    val left = index * slot + (slot - width) / 2
                    val height = if (count > 0) size.height * (0.3f + 0.7f * count / busiest) else 3.dp.toPx()
                    drawRoundRect(
                        color = if (count > 0) bar else track,
                        topLeft = Offset(left, size.height - height),
                        size = Size(width, height),
                        cornerRadius = corner,
                    )
                }
            }
            Text(text = year.year.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
