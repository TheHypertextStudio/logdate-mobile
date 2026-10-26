@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/**
 * Wraps the existing `TimelineDayDetailPanel` (which lives in client/feature/timeline) so the
 * NavDisplay entry stays small. Mirrors the legacy `TimelineDetailScreen` from the
 * androidx.navigation.compose graph.
 */
@Composable
internal fun TimelineDetailEntry(
    date: LocalDate,
    scrollToEntryId: String?,
    onClose: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenEvent: (eventId: String) -> Unit,
    onOpenNote: (Uuid) -> Unit,
) {
    val viewModel: app.logdate.feature.core.main.HomeViewModel =
        org.koin.compose.viewmodel
            .koinViewModel()
    LaunchedEffect(date) { viewModel.selectDay(date) }
    val uiState by viewModel.uiState.collectAsState()
    val transcriptionState by viewModel.transcriptionState.collectAsState()
    uiState.selectedDay?.let { selected ->
        LaunchedEffect(selected) {
            viewModel.updateVisibleAudioNoteIds(
                selected.notes
                    .filterIsInstance<app.logdate.ui.timeline.AudioNoteUiState>()
                    .map { note -> note.noteId }
                    .toSet(),
            )
        }
        app.logdate.ui.audio.TranscriptionProvider(transcriptionState) {
            app.logdate.feature.timeline.ui.details.TimelineDayDetailPanel(
                uiState = selected,
                scrollToEntryId = scrollToEntryId,
                onExit = onClose,
                onOpenEvent = onOpenEvent,
                onAttachNoteToEvent = viewModel::attachNoteToEvent,
                onOpenLocations = onOpenLocations,
                onNoteClick = onOpenNote,
            )
        }
    }
}
