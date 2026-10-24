@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.events.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.shared.model.Event
import app.logdate.shared.model.Place
import app.logdate.shared.model.displayLabel
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateTimeRangeShort
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.cd_event_detail_back
import logdate.client.feature.events.generated.resources.cd_event_detail_delete
import logdate.client.feature.events.generated.resources.event_detail_change_place
import logdate.client.feature.events.generated.resources.event_detail_choose_place
import logdate.client.feature.events.generated.resources.event_detail_description_label
import logdate.client.feature.events.generated.resources.event_detail_no_place
import logdate.client.feature.events.generated.resources.event_detail_not_found
import logdate.client.feature.events.generated.resources.event_detail_original_time
import logdate.client.feature.events.generated.resources.event_detail_place_heading
import logdate.client.feature.events.generated.resources.event_detail_save
import logdate.client.feature.events.generated.resources.event_detail_saving
import logdate.client.feature.events.generated.resources.event_detail_source_chip
import logdate.client.feature.events.generated.resources.event_detail_title
import logdate.client.feature.events.generated.resources.event_detail_title_label
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * Screen for viewing and editing a single event.
 *
 * Lets the user see and change the cover image, title, description, time bounds, place, and
 * attached captures, then save or delete. Layout follows Material 3 Expressive: a large
 * collapsing top app bar, sectioned scrollable body, sticky save bar that animates in once
 * the user has unsaved changes.
 */
@Composable
fun EventDetailScreen(
    eventId: Uuid,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EventDetailViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(eventId) {
        viewModel.loadEvent(eventId)
    }

    EventDetailContent(
        uiState = uiState,
        actions = viewModel,
        onGoBack = onGoBack,
        modifier = modifier,
    )
}

/**
 * Stateless body of the event detail / edit screen.
 *
 * Renders the large collapsing app bar, the sectioned editor body, the sticky save bar, and the
 * place / attach pickers from an immutable [EventDetailUiState] plus an [EventDetailActions]
 * handler. Hoisting all state and behavior out of [EventDetailScreen] keeps this composable
 * previewable and lets foldable screenshot audits drive it with fake state.
 *
 * Foldable behavior: on a real separating vertical hinge the editor splits into two panes via
 * [FoldableBookLayout] — the form/metadata fields on the start side and the attached captures on
 * the end side. The sticky save bar deliberately stays in the [Scaffold] bottom bar so it spans
 * beneath both panes rather than living inside one of them. On phones, tablets, and desktop the
 * single-pane [LazyColumn] is preserved byte-for-byte; the split only renders on a hinge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailContent(
    uiState: EventDetailUiState,
    actions: EventDetailActions,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.event_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(painter = PlatformIcons.back(), contentDescription = stringResource(Res.string.cd_event_detail_back))
                    }
                },
                actions = {
                    if (uiState is EventDetailUiState.Loaded) {
                        IconButton(onClick = { actions.delete(onGoBack) }) {
                            Icon(
                                painter = PlatformIcons.delete(),
                                contentDescription = stringResource(Res.string.cd_event_detail_delete),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            EventDetailSaveBar(state = uiState, onSave = actions::save)
        },
    ) { contentPadding ->
        when (val state = uiState) {
            EventDetailUiState.Loading -> {
                CenteredProgress(contentPadding)
            }

            EventDetailUiState.NotFound -> {
                CenteredEmptyState(contentPadding, stringResource(Res.string.event_detail_not_found))
            }

            is EventDetailUiState.Loaded -> {
                EventDetailLoadedBody(
                    state = state,
                    actions = actions,
                    contentPadding = contentPadding,
                )
            }
        }
    }

    // Pickers must live as siblings of the Scaffold body, not inside the scrollable LazyColumn,
    // so their internal scroll/gesture handling doesn't conflict with the body's scroll.
    (uiState as? EventDetailUiState.Loaded)?.let { loaded ->
        if (loaded.isPlacePickerOpen) {
            EventPlacePicker(
                availablePlaces = loaded.availablePlaces,
                selectedPlaceId = loaded.event.placeId,
                onPlaceSelected = actions::updatePlace,
                onDismiss = actions::dismissPlacePicker,
            )
        }
        if (loaded.isAttachSheetOpen) {
            AttachNoteToEventSheet(
                attachableNotes = loaded.attachableNotes,
                onAttach = actions::linkNote,
                onDismiss = actions::dismissAttachSheet,
            )
        }
    }
}

@Composable
private fun CenteredProgress(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredEmptyState(
    contentPadding: PaddingValues,
    text: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Editable body for an event in the [EventDetailUiState.Loaded] state.
 *
 * On a real separating vertical hinge this splits via [FoldableBookLayout]: the form/metadata
 * fields (cover, source chip, title, time, place, description, original-time line) sit in the
 * start pane and the attached captures plus any error message sit in the end pane. Off a hinge
 * the original single [LazyColumn] is preserved unchanged so phones, tablets, and desktop are
 * unaffected. Both panes and the single-pane list share [EventDetailItemsList], gated by include
 * flags, so the editor UI is defined in exactly one place.
 */
@Composable
private fun EventDetailLoadedBody(
    state: EventDetailUiState.Loaded,
    actions: EventDetailActions,
    contentPadding: PaddingValues,
) {
    val launchPicker =
        rememberCoverImageLauncher { picked ->
            if (picked != null) actions.updateCoverImage(picked)
        }

    FoldableBookLayout(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
        minPaneWidth = 320.dp,
        startPane = {
            EventDetailItemsList(
                state = state,
                actions = actions,
                onPickCoverImage = launchPicker,
                includeCaptures = false,
            )
        },
        endPane = {
            EventDetailItemsList(
                state = state,
                actions = actions,
                onPickCoverImage = launchPicker,
                includeForm = false,
            )
        },
        standardContent = {
            EventDetailItemsList(
                state = state,
                actions = actions,
                onPickCoverImage = launchPicker,
            )
        },
    )
}

/**
 * The event editor's scrollable item list, reused by both foldable panes and the single-pane
 * layout. Each section is a separate LazyColumn item so the editor stays cheap to scroll on long
 * events with many attachments.
 *
 * @param includeForm renders the cover, source chip, title, time, place, description, and the
 *   original-time line. Set to `false` in the captures-only end pane.
 * @param includeCaptures renders the attached-captures section and any error message. Set to
 *   `false` in the form-only start pane.
 * @param onPickCoverImage launches the platform photo picker; hoisted so both panes share one
 *   launcher instance.
 */
@Composable
private fun EventDetailItemsList(
    state: EventDetailUiState.Loaded,
    actions: EventDetailActions,
    onPickCoverImage: () -> Unit,
    includeForm: Boolean = true,
    includeCaptures: Boolean = true,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (includeForm) {
            item("cover") {
                EventCoverImageCard(
                    coverImageUri = state.event.coverImageUri,
                    onPickImage = onPickCoverImage,
                    onClearImage = { actions.updateCoverImage(null) },
                )
            }

            state.event.externalCalendarSource?.let { source ->
                item("source-chip") {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(Res.string.event_detail_source_chip, source.displayLabel())) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            item("title") {
                OutlinedTextField(
                    value = state.event.title,
                    onValueChange = actions::updateTitle,
                    label = { Text(stringResource(Res.string.event_detail_title_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item("when") {
                EventTimeRangeSection(
                    startTime = state.event.startTime,
                    endTime = state.event.endTime,
                    onStartTimeChange = actions::updateStartTime,
                    onEndTimeChange = actions::updateEndTime,
                    onTogglePointInTime = actions::togglePointInTime,
                )
            }

            item("where") {
                EventPlaceSection(
                    resolvedPlace = state.resolvedPlace,
                    hasPlace = state.event.placeId != null,
                    onChoosePlace = actions::openPlacePicker,
                )
            }

            item("description") {
                OutlinedTextField(
                    value = state.event.description.orEmpty(),
                    onValueChange = actions::updateDescription,
                    label = { Text(stringResource(Res.string.event_detail_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }

            item("original-time") {
                Text(
                    text = stringResource(Res.string.event_detail_original_time, state.event.originalTimeText()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (includeForm && includeCaptures) {
            item("divider") {
                HorizontalDivider()
            }
        }

        if (includeCaptures) {
            item("attached") {
                EventLinkedNotesSection(
                    notes = state.linkedNotes,
                    onUnlink = actions::unlinkNote,
                    onAddCapture = actions::openAttachSheet,
                )
            }

            state.errorMessage?.let { message ->
                item("error") {
                    Text(
                        text = stringResource(message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * The event's original time bounds as a short readable string, respecting its all-day flag.
 * All-day events render date-only; timed events render the date-time range.
 */
private fun Event.originalTimeText(): String = startTime.toReadableDateTimeRangeShort(end = endTime, isAllDay = isAllDay)

@Composable
private fun EventPlaceSection(
    resolvedPlace: Place.UserDefined?,
    hasPlace: Boolean,
    onChoosePlace: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Spacing.lg),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(Res.string.event_detail_place_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListItem(
                headlineContent = {
                    Text(resolvedPlace?.displayName ?: stringResource(Res.string.event_detail_no_place))
                },
                supportingContent =
                    resolvedPlace?.description?.let { description ->
                        { Text(description) }
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
            )
            val placeButtonLabel =
                if (hasPlace) Res.string.event_detail_change_place else Res.string.event_detail_choose_place
            FilledTonalButton(onClick = onChoosePlace) {
                Text(stringResource(placeButtonLabel))
            }
        }
    }
}

/**
 * Sticky save bar pinned to the bottom of the editor. Animates in once the screen is loaded.
 * The Save button is disabled while the title is blank or a save is in flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailSaveBar(
    state: EventDetailUiState,
    onSave: () -> Unit,
) {
    AnimatedVisibility(
        visible = state is EventDetailUiState.Loaded,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        val loaded = state as? EventDetailUiState.Loaded ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val saveButtonLabel =
                        if (loaded.isSaving) Res.string.event_detail_saving else Res.string.event_detail_save
                    Button(
                        onClick = onSave,
                        enabled = !loaded.isSaving && loaded.event.title.isNotBlank(),
                    ) {
                        Text(stringResource(saveButtonLabel))
                    }
                }
            }
        }
    }
}
