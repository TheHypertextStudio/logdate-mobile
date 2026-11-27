@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.logdate.feature.editor.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.logdate.feature.editor.ui.NoteEditorScreen
import app.logdate.feature.editor.ui.editor.DraftState
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import app.logdate.shared.model.location.VisitMemoryContext
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.creation.EntryCreationShape
import app.logdate.ui.creation.LocalEntryCreationOpening
import app.logdate.ui.creation.LocalEntryCreationProgress
import app.logdate.ui.creation.entryCreationBounds
import app.logdate.ui.creation.rememberEntryCreationVisibilityProgress
import app.logdate.ui.navigation.ViewModelsPerVisit
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid
import app.logdate.feature.editor.ui.LocalAnimatedVisibilityScope as EditorLocalAnimatedVisibilityScope
import app.logdate.feature.editor.ui.LocalSharedTransitionScope as EditorLocalSharedTransitionScope

/**
 * Typed route for the entry editor. Carries optional context so the screen can resume an
 * existing entry or draft, or pre-select journals for a brand-new entry.
 *
 * Strings rather than `Uuid`s because Navigation 3's saved-state serialization on iOS does
 * not handle `Uuid` natively yet.
 *
 * @property launchMode What a new entry opens ready to capture. Ignored when resuming [entryId].
 * @property promptPhotoUri A photo to start the entry with, followed by text or, in
 *   [EditorLaunchMode.Voice], a recording. The New entry widget uses it for its photo prompts.
 * @property initialText Text to start a new entry with, such as a notification reply that could not
 *   be saved on its own.
 */
@Serializable
data class EntryEditorRoute(
    val entryId: String? = null,
    val draftId: String? = null,
    val journalIds: List<String> = emptyList(),
    val firstEntry: Boolean = false,
    val visitContext: VisitMemoryContext? = null,
    val launchMode: EditorLaunchMode = EditorLaunchMode.Default,
    val promptPhotoUri: String? = null,
    val initialText: String? = null,
    val markId: String? = null,
    val resumeRecording: Boolean = false,
    val creationTransitionId: String? = null,
    val creationTransitionShape: EntryCreationShape = EntryCreationShape.Large,
) : NavKey

/**
 * Registers the editor entry. The hosting graph supplies callbacks for back / save so the
 * editor module never has to know about the surrounding back stack shape.
 */
fun EntryProviderScope<NavKey>.editorEntry(
    onNavigateBack: () -> Unit,
    onEntrySaved: (EntryEditorRoute) -> Unit,
    metadata: Map<String, Any> = emptyMap(),
) {
    taggedEntry<EntryEditorRoute>(metadata = metadata + ViewModelsPerVisit) { route ->
        val editorViewModel: EntryEditorViewModel = koinViewModel()
        val state by editorViewModel.editorState.collectAsState()
        LaunchedEffect(route, state.draftState, state.isRecordingAudio) {
            val draft = state.draftState as? DraftState.Active
            if (state.isRecordingAudio && draft != null) {
                RecordingEditorDestination.bind(draft.id.toString(), route)
            }
        }
        val navigationScope = LocalSharedTransitionScope.current
        val animatedContentScope = LocalNavAnimatedContentScope.current
        val creationProgress = if (route.creationTransitionId == null) 1f else rememberEntryCreationVisibilityProgress()
        val currentCreationProgress = rememberUpdatedState(creationProgress)
        val opening = remember { { currentCreationProgress.value < 1f } }
        val sharedBoundsModifier =
            Modifier.entryCreationBounds(
                route.creationTransitionId,
                destination = true,
                sourceShape = route.creationTransitionShape,
            )

        CompositionLocalProvider(
            LocalEntryCreationProgress provides creationProgress,
            LocalEntryCreationOpening provides opening,
            EditorLocalSharedTransitionScope provides navigationScope,
            EditorLocalAnimatedVisibilityScope provides animatedContentScope,
        ) {
            NoteEditorScreen(
                viewModel = editorViewModel,
                onNavigateBack = onNavigateBack,
                onEntrySaved = { onEntrySaved(route) },
                entryId = route.entryId?.let(Uuid::parse),
                draftId = route.draftId?.let(Uuid::parse),
                firstEntry = route.firstEntry,
                journalIds = route.journalIds.map(Uuid::parse),
                visitContext = route.visitContext,
                launchMode = route.launchMode,
                promptPhotoUri = route.promptPhotoUri,
                initialTextContent = route.initialText,
                markId = route.markId?.let(Uuid::parse),
                modifier = sharedBoundsModifier,
            )
        }
    }
}
