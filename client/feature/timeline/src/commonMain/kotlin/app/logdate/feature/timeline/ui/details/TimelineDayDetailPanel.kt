@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.timeline.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.scrollToTop
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.profiles.PersonUiState
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.workspace.PanelHeader
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.timeline.generated.resources.Res
import logdate.client.feature.timeline.generated.resources.cd_back_to_your_days
import logdate.client.feature.timeline.generated.resources.decorate
import logdate.client.feature.timeline.generated.resources.rewind
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Composable
fun TimelineDayDetailPanel(
    uiState: TimelineDayUiState,
    onExit: () -> Unit,
    onOpenEvent: (eventId: String) -> Unit = {},
    onAttachNoteToEvent: (noteId: String, eventId: String) -> Unit = { _, _ -> },
    visitedLocations: List<DayLocation> = listOf(),
    onOpenLocations: (() -> Unit)? = null,
    onOpenRewind: (() -> Unit)? = null,
    onDecorate: (() -> Unit)? = null,
    onJournalClick: (Uuid) -> Unit = {},
    onNoteClick: (Uuid) -> Unit = {},
    scrollState: LazyListState = rememberLazyListState(),
    /**
     * Optional UUID of an entry within this day to scroll to on first composition. Used by the
     * search-result tap fallback (transcription, ambient sound, sticker, place) so the user lands
     * near the entry instead of at the top of the day. Falls through to scroll-to-top when the
     * id doesn't match any visible section.
     */
    scrollToEntryId: String? = null,
    modifier: Modifier = Modifier,
) {
    val timestamp = uiState.date
    val people = uiState.people
    val resolvedVisitedLocations =
        visitedLocations.ifEmpty {
            uiState.placesVisited.mapNotNull { place ->
                val latitude = place.latitude ?: return@mapNotNull null
                val longitude = place.longitude ?: return@mapNotNull null
                DayLocation(
                    locationId = place.id,
                    name = place.title,
                    latitude = latitude,
                    longitude = longitude,
                )
            }
        }

    var initialTargetConsumed by rememberSaveable(uiState.date, scrollToEntryId) { mutableStateOf(false) }
    LaunchedEffect(uiState.date, scrollToEntryId) {
        if (initialTargetConsumed) return@LaunchedEffect
        val targetIndex =
            scrollToEntryId?.let { id ->
                targetSectionIndex(id, uiState, resolvedVisitedLocations)
            }
        if (targetIndex != null) {
            scrollState.animateScrollToItem(targetIndex)
        } else {
            scrollState.scrollToTop()
        }
        initialTargetConsumed = true
    }

    WorkspacePanel(modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                PanelHeader(timestamp.toReadableDateShort(), actions = {
                    IconButton(onClick = onExit) { Icon(PlatformIcons.back(), stringResource(Res.string.cd_back_to_your_days)) }
                    onDecorate?.let { IconButton(onClick = it) { Icon(PlatformIcons.brush(), stringResource(Res.string.decorate)) } }
                    onOpenRewind?.let { IconButton(onClick = it) { Icon(PlatformIcons.history(), stringResource(Res.string.rewind)) } }
                })
                TimelineDayDetailList(
                    uiState,
                    people,
                    resolvedVisitedLocations,
                    onOpenEvent,
                    onAttachNoteToEvent,
                    onOpenLocations,
                    onJournalClick,
                    onNoteClick,
                    scrollState,
                    PaddingValues(Spacing.lg),
                )
            }
        }
    }
}

@Composable
private fun TimelineDayDetailList(
    uiState: TimelineDayUiState,
    people: List<PersonUiState>,
    resolvedVisitedLocations: List<DayLocation>,
    onOpenEvent: (eventId: String) -> Unit,
    onAttachNoteToEvent: (noteId: String, eventId: String) -> Unit,
    onOpenLocations: (() -> Unit)?,
    onJournalClick: (Uuid) -> Unit,
    onNoteClick: (Uuid) -> Unit,
    scrollState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
) {
    val summaryNotice = uiState.summaryNotice?.let { notice -> stringResource(notice) }
    val summary = uiState.summary.ifEmpty { summaryNotice.orEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = scrollState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (summary.isNotBlank()) {
            item(
                contentType = "tldr",
            ) {
                TldrSection(summary)
            }
        }
        if (people.isNotEmpty()) {
            item(
                contentType = "people",
            ) {
                PeopleEncounteredSection(
                    people = people,
                )
            }
        }
        if (uiState.notes.isNotEmpty()) {
            item(
                contentType = "notes",
            ) {
                NotesListSection(
                    notes = uiState.notes,
                    onJournalClick = onJournalClick,
                    onNoteClick = onNoteClick,
                )
            }
        }
        if (uiState.events.isNotEmpty()) {
            item(contentType = "events") {
                EventsSection(
                    events = uiState.events,
                    onOpenEvent = onOpenEvent,
                    onAttachNoteToEvent = onAttachNoteToEvent,
                )
            }
        }
        if (resolvedVisitedLocations.isNotEmpty()) {
            item(
                contentType = "locations",
            ) {
                LocationsSection(
                    locations = resolvedVisitedLocations,
                    onOpenLocations = onOpenLocations,
                )
            }
        }
    }
}

/**
 * Maps an entry UUID string to the LazyColumn item index of the section that displays it.
 *
 * The panel's LazyColumn order is: tldr (0), people (1), notes (2), events (3 — conditional),
 * locations (3 or 4 depending on whether events are present). The function only resolves
 * sections whose data the panel exposes; if the entryId belongs to a content type rendered
 * elsewhere (or not at all) it returns null and the caller falls back to scroll-to-top.
 */
internal fun targetSectionIndex(
    entryId: String,
    uiState: TimelineDayUiState,
    visitedLocations: List<DayLocation>,
): Int? {
    val sections =
        buildList {
            if (uiState.summary.isNotBlank() || uiState.summaryNotice != null) add("tldr")
            if (uiState.people.isNotEmpty()) add("people")
            if (uiState.notes.isNotEmpty()) add("notes")
            if (uiState.events.isNotEmpty()) add("events")
            if (visitedLocations.isNotEmpty()) add("locations")
        }

    if (uiState.notes.any { it.noteId.toString() == entryId }) return sections.indexOf("notes").takeIf { it >= 0 }
    if (visitedLocations.any { it.locationId == entryId }) {
        return sections.indexOf("locations").takeIf { it >= 0 }
    }
    return null
}

@Preview
@Composable
private fun TimelineDayDetailPanelPreview() {
    val uiState =
        TimelineDayUiState(
            summary = "I ate some cake downtown, went to a concert, and had a lot of fun with friends.",
            date =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date,
            people =
                listOf(
                    PersonUiState(
                        uid = Uuid.random(),
                        name = "Margaret Belford",
                    ),
                    PersonUiState(
                        uid = Uuid.random(),
                        name = "Charles Averill",
                    ),
                    PersonUiState(
                        uid = Uuid.random(),
                        name = "Lane Hughes",
                    ),
                    PersonUiState(
                        uid = Uuid.random(),
                        name = "Haley Wheatley",
                    ),
                ),
        )
    TimelineDayDetailPanel(
        uiState = uiState,
        onExit = {},
        onOpenEvent = {},
    )
}

@Preview
@Composable
private fun TimelineDayDetailPanelPreview_NoPeople() {
    val uiState =
        TimelineDayUiState(
            summary = "I ate some cake downtown, went to a concert, and had a lot of fun with friends.",
            date =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .date,
        )
    TimelineDayDetailPanel(
        uiState = uiState,
        onExit = {},
        onOpenEvent = {},
    )
}
