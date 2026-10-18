@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.theme.Spacing
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
    /** A scrolling column with a section for each kind of option, for windows with room beside the results. */
    Panel,

    /** The time strip over one scrolling row of options, for the space above the results. */
    Row,
}

/**
 * The narrowing options of a search: when, who, where and what type, each with how many results
 * choosing it would leave.
 *
 * Choosing an option that is already chosen clears it.
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
    when (layout) {
        SearchNarrowingLayout.Panel ->
            NarrowingPanel(options, onToggleType, onTogglePerson, onTogglePlace, onToggleYear, onClear, modifier)
        SearchNarrowingLayout.Row ->
            NarrowingRow(options, onToggleType, onTogglePerson, onTogglePlace, onToggleYear, onClear, modifier)
    }
}

@Composable
private fun NarrowingPanel(
    options: SearchNarrowingOptions,
    onToggleType: (SearchContentType) -> Unit,
    onTogglePerson: (Uuid) -> Unit,
    onTogglePlace: (Uuid) -> Unit,
    onToggleYear: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val unnamed = stringResource(Res.string.search_narrowing_unnamed)
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier =
            modifier
                .testTag(SEARCH_NARROWING_TAG)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
    ) {
        if (options.years.isNotEmpty()) {
            PanelHeading(stringResource(Res.string.search_narrowing_when))
            SearchTimeStrip(options.years, onToggleYear)
        }
        if (options.people.isNotEmpty()) {
            PanelHeading(stringResource(Res.string.search_narrowing_people))
            options.people.forEach { PanelOption(it.label ?: unnamed, it.count, it.selected) { onTogglePerson(it.value) } }
        }
        if (options.places.isNotEmpty()) {
            PanelHeading(stringResource(Res.string.search_narrowing_places))
            options.places.forEach { PanelOption(it.label ?: unnamed, it.count, it.selected) { onTogglePlace(it.value) } }
        }
        if (options.types.isNotEmpty()) {
            PanelHeading(stringResource(Res.string.search_narrowing_types))
            options.types.forEach { PanelOption(stringResource(it.value.labelKey()), it.count, it.selected) { onToggleType(it.value) } }
        }
        if (options.anyChosen) {
            TextButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_NARROWING_CLEAR_TAG)) {
                Text(stringResource(Res.string.search_narrowing_clear))
            }
        }
    }
}

@Composable
private fun PanelHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

@Composable
private fun PanelOption(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .focusableWithRing()
                .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = content, modifier = Modifier.weight(1f))
        Text(text = count.toString(), style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun NarrowingRow(
    options: SearchNarrowingOptions,
    onToggleType: (SearchContentType) -> Unit,
    onTogglePerson: (Uuid) -> Unit,
    onTogglePlace: (Uuid) -> Unit,
    onToggleYear: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val unnamed = stringResource(Res.string.search_narrowing_unnamed)
    val choices =
        buildList {
            options.people.forEach {
                add(
                    RowChoice("person_${it.value}", it.label ?: unnamed, it.count, it.selected) { onTogglePerson(it.value) },
                )
            }
            options.places.forEach {
                add(
                    RowChoice("place_${it.value}", it.label ?: unnamed, it.count, it.selected) { onTogglePlace(it.value) },
                )
            }
            options.types.forEach {
                add(
                    RowChoice(
                        "type_${it.value.ftsValue}",
                        stringResource(it.value.labelKey()),
                        it.count,
                        it.selected,
                    ) { onToggleType(it.value) },
                )
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = modifier.testTag(SEARCH_NARROWING_TAG)) {
        if (options.years.isNotEmpty()) {
            SearchTimeStrip(options.years, onToggleYear, modifier = Modifier.padding(horizontal = Spacing.lg))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (options.anyChosen) {
                item(key = "clear") {
                    TextButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_NARROWING_CLEAR_TAG)) {
                        Text(stringResource(Res.string.search_narrowing_clear))
                    }
                }
            }
            items(choices, key = { it.key }) { choice ->
                FilterChip(
                    selected = choice.selected,
                    onClick = choice.onClick,
                    label = { Text("${choice.label} ${choice.count}") },
                )
            }
        }
    }
}

private class RowChoice(
    val key: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
)

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
            val barColor = MaterialTheme.colorScheme.primary
            val container =
                if (year.selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            val faded = anySelected && !year.selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(container)
                        .focusableWithRing()
                        .selectable(selected = year.selected, role = Role.Checkbox, onClick = { onToggleYear(year.year) })
                        .semantics { contentDescription = description }
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    val slot = size.width / year.months.size
                    year.months.forEachIndexed { index, count ->
                        if (count <= 0) return@forEachIndexed
                        val barHeight = size.height * (0.25f + 0.75f * count / busiest)
                        drawRoundRect(
                            color = barColor.copy(alpha = if (faded) 0.4f else 1f),
                            topLeft = Offset(index * slot + slot * 0.15f, size.height - barHeight),
                            size = Size(slot * 0.7f, barHeight),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }
                Text(
                    text = year.year.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
