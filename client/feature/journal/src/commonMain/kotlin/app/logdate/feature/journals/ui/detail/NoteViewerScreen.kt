@file:Suppress(
    "ktlint:standard:function-naming",
)
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package app.logdate.feature.journals.ui.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.client.text.asString
import app.logdate.feature.editor.ui.video.VideoPlayerContent
import app.logdate.feature.journals.ui.AddToJournalPicker
import app.logdate.feature.journals.ui.deriveCoverColor
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.handwriting.HandwritingPreview
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.util.toReadableDateTimeShort
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.error
import logdate.client.feature.journal.generated.resources.image_note
import logdate.client.feature.journal.generated.resources.note_viewer_share_chooser_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * Viewer screen that renders notes with type-specific presentation.
 *
 * When [journalId] is provided, the viewer connects to the journal with
 * an accent color spine, the journal title in the toolbar, and prev/next navigation.
 */
@Composable
fun NoteViewerScreen(
    noteId: Uuid,
    onGoBack: () -> Unit,
    onEdit: ((Uuid) -> Unit)? = null,
    journalId: Uuid? = null,
    enableSharedBounds: Boolean = journalId != null,
    onOpenLocationTimeline: () -> Unit = {},
    onNavigateToNote: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: NoteViewerViewModel =
        koinViewModel(
            parameters = { parametersOf(noteId, journalId) },
        ),
) {
    val uiState by viewModel.uiState.collectAsState()
    val allJournals by viewModel.allJournals.collectAsState()
    val memberJournalIds by viewModel.memberJournalIds.collectAsState()
    val sharedBoundsModifier =
        rememberNoteViewerSharedBoundsModifier(
            noteId = noteId,
            enableSharedBounds = enableSharedBounds,
        )
    var showAddToJournal by remember { mutableStateOf(false) }
    val shareChooserTitle = stringResource(Res.string.note_viewer_share_chooser_title)
    val shareCurrentNote = { viewModel.shareCurrentNote(shareChooserTitle) }

    when (val state = uiState) {
        NoteViewerUiState.Loading -> {
            NoteViewerLoadingContent(modifier = modifier.then(sharedBoundsModifier))
        }

        is NoteViewerUiState.Error -> {
            NoteViewerErrorContent(
                message = state.message.asString(),
                modifier = modifier.then(sharedBoundsModifier),
            )
        }

        is NoteViewerUiState.AudioContent -> {
            AudioNoteViewerEntry(
                noteId = state.shared.noteId,
                onGoBack = onGoBack,
                modifier = modifier.then(sharedBoundsModifier),
            )
        }

        is NoteViewerUiState.HandwritingContent -> {
            NoteViewerScaffoldContent(
                shared = state.shared,
                onGoBack = onGoBack,
                onOpenLocationTimeline = onOpenLocationTimeline,
                onNavigateToNote = onNavigateToNote,
                onShowAddToJournal = { showAddToJournal = true },
                onShare = shareCurrentNote,
                modifier = modifier.then(sharedBoundsModifier),
            ) {
                HandwritingPreview(state.document, Modifier.fillMaxWidth().height(480.dp), unreadable = state.unreadable)
            }
        }

        is NoteViewerUiState.TextContent -> {
            NoteViewerScaffoldContent(
                shared = state.shared,
                onGoBack = onGoBack,
                onEdit = onEdit?.let { edit -> { edit(state.shared.noteId) } },
                onOpenLocationTimeline = onOpenLocationTimeline,
                onNavigateToNote = onNavigateToNote,
                onShowAddToJournal = { showAddToJournal = true },
                onShare = shareCurrentNote,
                modifier = modifier.then(sharedBoundsModifier),
            ) {
                TextNoteViewerContent(
                    text = state.text,
                    shared = state.shared,
                )
            }
        }

        is NoteViewerUiState.ImageContent -> {
            NoteViewerScaffoldContent(
                shared = state.shared,
                onGoBack = onGoBack,
                onOpenLocationTimeline = onOpenLocationTimeline,
                onNavigateToNote = onNavigateToNote,
                onShowAddToJournal = { showAddToJournal = true },
                onShare = shareCurrentNote,
                modifier = modifier.then(sharedBoundsModifier),
            ) {
                ImageNoteViewer(
                    mediaRef = state.mediaRef,
                    shared = state.shared,
                )
            }
        }

        is NoteViewerUiState.VideoContent -> {
            NoteViewerScaffoldContent(
                shared = state.shared,
                onGoBack = onGoBack,
                onOpenLocationTimeline = onOpenLocationTimeline,
                onNavigateToNote = onNavigateToNote,
                onShowAddToJournal = { showAddToJournal = true },
                onShare = shareCurrentNote,
                modifier = modifier.then(sharedBoundsModifier),
            ) {
                VideoNoteViewerContent(mediaRef = state.mediaRef)
            }
        }
    }

    if (showAddToJournal) {
        AddToJournalPicker(
            noteId = noteId,
            currentJournalId = journalId,
            journals = allJournals,
            memberJournalIds = memberJournalIds,
            onToggleMembership = viewModel::toggleJournalMembership,
            onDismiss = { showAddToJournal = false },
        )
    }
}

// region Type-specific viewers

/**
 * Text note rendered as a page with generous typography and a journal spine accent.
 */
@Composable
fun TextNoteViewerContent(
    text: String,
    shared: NoteViewerShared,
    modifier: Modifier = Modifier,
) {
    val journalContext = shared.journalContext
    val accentColor =
        journalContext?.let {
            remember(it.journalId) { deriveCoverColor(it.journalId) }
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row {
            if (accentColor != null) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(accentColor),
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = if (LocalWorkspaceEnabled.current) 720.dp else 840.dp)
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    MarkdownText(
                        content = text,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(Spacing.xl))

                    Text(
                        text = shared.createdAt.toReadableDateTimeShort(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * Image note rendered edge-to-edge with shadow and a journal accent bar.
 */
@Composable
private fun ImageNoteViewer(
    mediaRef: String,
    shared: NoteViewerShared,
    modifier: Modifier = Modifier,
) {
    val journalContext = shared.journalContext
    val accentColor =
        journalContext?.let {
            remember(it.journalId) { deriveCoverColor(it.journalId) }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = mediaRef,
            contentDescription = stringResource(Res.string.image_note),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, MaterialTheme.shapes.large)
                    .clip(MaterialTheme.shapes.large),
            contentScale = ContentScale.Fit,
        )

        if (accentColor != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(accentColor),
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text(
            text = shared.createdAt.toReadableDateTimeShort(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * Video note rendered as a player filling the width.
 */
@Composable
fun VideoNoteViewerContent(
    mediaRef: String,
    modifier: Modifier = Modifier,
) {
    VideoPlayerContent(
        uri = mediaRef,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
    )
}

// endregion

// region Audio viewer

/**
 * Audio note presentation delegated to the immersive audio screen.
 */
@Composable
private fun AudioNoteViewerEntry(
    noteId: Uuid,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioNoteViewerViewModel =
        koinViewModel(
            parameters = { parametersOf(noteId) },
        ),
) {
    val uiState by viewModel.uiState.collectAsState()
    AudioNoteViewerContent(
        uiState = uiState,
        onGoBack = onGoBack,
        onPlayPause = viewModel::togglePlayback,
        onSeek = viewModel::seekTo,
        onSkipBack = { viewModel.skipByMillis(-10_000L) },
        onSkipForward = { viewModel.skipByMillis(10_000L) },
        modifier = modifier,
    )
}

// endregion

// region Shared transition

@Composable
private fun rememberNoteViewerSharedBoundsModifier(
    noteId: Uuid,
    enableSharedBounds: Boolean,
): Modifier {
    if (!enableSharedBounds) {
        return Modifier
    }

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current

    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(TransitionKeys.noteViewerTransition(noteId)),
                animatedVisibilityScope = animatedVisibilityScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    } else {
        Modifier
    }
}

// endregion

// region Supporting components

@Composable
fun NoteViewerLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun NoteViewerErrorContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.error),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion
