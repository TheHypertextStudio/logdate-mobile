@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)

package app.logdate.feature.editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.repository.moments.MarkedMomentRepository
import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import app.logdate.shared.model.location.VisitMemoryContext
import io.github.aakira.napier.Napier
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * The main screen for creating a new note or editing an existing entry.
 *
 * This is a wrapper around [EntryEditorContent] that handles navigation and initial setup.
 * It's the component that should be used in navigation destinations.
 *
 * When editing an existing entry, pass the entryId parameter to load and edit that entry.
 * When creating a new entry, leave entryId as null and optionally provide initial content
 * through initialTextContent and attachments parameters.
 *
 * Note: Initial content should only be populated when explicitly provided through parameters.
 * By default, a new entry is truly new (empty) with no auto-loaded content.
 *
 * @param onNavigateBack Callback invoked when the user navigates back from the editor (e.g., pressing back button)
 * @param onEntrySaved Callback invoked when the user successfully saves an entry
 * @param modifier Optional Compose modifier for customizing the screen's layout and appearance
 * @param entryId Optional unique identifier of an existing entry to load and edit. If provided, the entry
 *        is fetched and displayed for editing. If null, a new blank entry is created.
 * @param journalId Optional journal ID providing context for the entry. Used when editing an existing entry
 *        or to set the default journal for a new entry.
 * @param initialTextContent Optional initial text content to populate a new entry with. Only used when
 *        entryId is null (creating new entries). Ignored when editing existing entries.
 * @param attachments Optional list of attachment URIs (images, audio, video) to add to a new entry.
 *        Only used when entryId is null. Ignored when editing existing entries.
 * @param launchMode What a new entry opens ready to capture: text, a voice recording, or the camera
 *        for photos or video. Ignored when editing existing entries.
 * @param promptPhotoUri A photo to start a new entry with, followed by text or (in voice mode) a
 *        recording. Ignored when editing existing entries.
 * @param viewModel The ViewModel managing editor state and operations. Typically injected via Koin.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun NoteEditorScreen(
    onNavigateBack: () -> Unit,
    onEntrySaved: () -> Unit,
    modifier: Modifier = Modifier,
    entryId: Uuid? = null,
    draftId: Uuid? = null,
    firstEntry: Boolean = false,
    journalId: Uuid? = null,
    journalIds: List<Uuid> = emptyList(),
    initialTextContent: String? = null,
    attachments: List<String> = emptyList(),
    launchMode: EditorLaunchMode = EditorLaunchMode.Default,
    promptPhotoUri: String? = null,
    viewModel: EntryEditorViewModel = koinViewModel(),
    visitContext: VisitMemoryContext? = null,
    markId: Uuid? = null,
) {
    val recorder: AudioRecordingManager = koinInject()
    // Capture once per visit. A later stop must not turn a resumed draft into a new entry.
    val resolvedDraftId =
        remember(viewModel, entryId, draftId) {
            draftId ?: viewModel.recoverableDraftId ?: if (
                entryId == null &&
                initialTextContent.isNullOrBlank() &&
                attachments.isEmpty() &&
                promptPhotoUri == null &&
                markId == null &&
                visitContext == null &&
                journalId == null &&
                journalIds.isEmpty() &&
                (recorder.isRecording || recorder.isStartingRecording)
            ) {
                recorder.currentRecordingDraftId
            } else {
                null
            }
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val activeBlock = recorder.currentRecordingTargetNoteId
        if (recorder.isRecording &&
            viewModel.editorState.value.blocks
                .any { it.id == activeBlock }
        ) {
            viewModel.setExpandedBlockId(activeBlock)
        }
    }
    val marks: MarkedMomentRepository = koinInject()
    LaunchedEffect(markId, entryId, resolvedDraftId) {
        viewModel.initializeMarkContext(markId?.toString())
        if (markId != null && entryId == null && resolvedDraftId == null && initialTextContent.isNullOrBlank()) {
            try {
                viewModel.openMarkedMoment(markId.toString(), marks.getMark(markId)?.caption.orEmpty())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.w("Could not restore marked moment caption", failure)
            }
        }
    }

    // Load an existing draft or journal entry when provided
    LaunchedEffect(entryId, resolvedDraftId, firstEntry, visitContext) {
        if (resolvedDraftId != null) {
            try {
                Napier.d("NoteEditorScreen: Loading draft: $resolvedDraftId")
                val retained = viewModel.editorState.value
                if ((retained.draftState as? app.logdate.feature.editor.ui.editor.DraftState.Active)?.id != resolvedDraftId ||
                    retained.blocks.isEmpty()
                ) {
                    viewModel.loadDraft(resolvedDraftId)
                }
            } catch (e: Exception) {
                Napier.e("Failed to load draft: $resolvedDraftId", e)
            }
        } else if (entryId != null) {
            try {
                Napier.d("NoteEditorScreen: Loading existing entry: $entryId")
                viewModel.loadExistingEntry(entryId, journalId)
            } catch (e: Exception) {
                Napier.e("Failed to load existing entry: $entryId", e)
            }
        } else if (firstEntry) {
            viewModel.resumeFirstEntryDraft()
        } else {
            viewModel.initializeVisitContext(visitContext)
            if (journalIds.isNotEmpty()) viewModel.initializeSelectedJournals(journalIds)
        }
    }

    // Set initial content ONLY when creating a new entry (entryId is null)
    LaunchedEffect(initialTextContent, attachments, entryId) {
        // Only process initial content for new entries
        if (entryId == null && resolvedDraftId == null) {
            try {
                // Handle initial text content if explicitly provided
                if (!initialTextContent.isNullOrBlank()) {
                    Napier.d("NoteEditorScreen: Setting ${initialTextContent.length} characters of initial text")
                    viewModel.setInitialTextContent(initialTextContent)
                }

                // Handle initial attachments if explicitly provided
                if (attachments.isNotEmpty()) {
                    Napier.d("NoteEditorScreen: Setting ${attachments.size} initial attachments")
                    viewModel.setInitialAttachments(attachments)
                }
            } catch (e: Exception) {
                Napier.e("Failed to set initial content", e)
            }
        }
    }

    LaunchedEffect(
        launchMode,
        promptPhotoUri,
        entryId,
        resolvedDraftId,
        firstEntry,
        initialTextContent,
        attachments,
        markId,
    ) {
        if (entryId != null || resolvedDraftId != null) return@LaunchedEffect
        if (promptPhotoUri != null) {
            viewModel.initializePhotoPrompt(promptPhotoUri, record = launchMode == EditorLaunchMode.Voice)
        } else if (!firstEntry &&
            initialTextContent.isNullOrBlank() &&
            attachments.isEmpty() &&
            markId == null &&
            (launchMode == EditorLaunchMode.Default || launchMode == EditorLaunchMode.Text)
        ) {
            viewModel.initializePreferredEntryMode(if (launchMode == EditorLaunchMode.Text) EntryStartMode.TEXT else null)
        } else {
            viewModel.initializeLaunchMode(launchMode)
        }
    }

    // Render the EntryEditorScreen with our parameters
    // TODO: Figure out how to consolidate with screen this if needed
    EntryEditorContent(
        onNavigateBack = onNavigateBack,
        onEntrySaved = onEntrySaved,
        modifier = modifier,
        viewModel = viewModel,
        handwritingEnabled = true,
    )
}
