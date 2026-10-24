@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.library.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.systemControlledSelection
import app.logdate.feature.editor.ui.video.VideoPlayerContent
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.media.MediaDeviceSelector
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.PanelHeader
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.ui.workspace.WorkspaceSupportingSheet
import app.logdate.util.toReadableDateTimeShort
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import logdate.client.feature.library.generated.resources.Res
import logdate.client.feature.library.generated.resources.cd_back_to_library
import logdate.client.feature.library.generated.resources.cd_library_photo_full
import logdate.client.feature.library.generated.resources.cd_pause_audio
import logdate.client.feature.library.generated.resources.cd_play_audio
import logdate.client.feature.library.generated.resources.cd_present
import logdate.client.feature.library.generated.resources.cd_share_memory
import logdate.client.feature.library.generated.resources.cd_stop_presenting
import logdate.client.feature.library.generated.resources.cd_view_full_screen
import logdate.client.feature.library.generated.resources.library_appears_in
import logdate.client.feature.library.generated.resources.library_audio_output
import logdate.client.feature.library.generated.resources.library_audio_recording
import logdate.client.feature.library.generated.resources.library_details_title
import logdate.client.feature.library.generated.resources.library_external_display_audio
import logdate.client.feature.library.generated.resources.library_managed_by_android
import logdate.client.feature.library.generated.resources.library_memory_details
import logdate.client.feature.library.generated.resources.library_memory_title
import logdate.client.feature.library.generated.resources.library_metadata_camera
import logdate.client.feature.library.generated.resources.library_metadata_captured
import logdate.client.feature.library.generated.resources.library_metadata_location
import logdate.client.feature.library.generated.resources.library_metadata_type
import logdate.client.feature.library.generated.resources.library_metadata_type_photo
import logdate.client.feature.library.generated.resources.library_metadata_type_video
import logdate.client.feature.library.generated.resources.library_recording_playback_title
import logdate.client.feature.library.generated.resources.library_stop_presenting
import logdate.client.ui.generated.resources.common_back
import logdate.client.ui.generated.resources.common_go_back
import logdate.client.ui.generated.resources.common_share
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Instant
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Stateful media detail screen that loads indexed media by ID and displays its content with metadata.
 */
@Composable
fun MediaDetailScreen(
    mediaId: Uuid,
    onBack: () -> Unit,
    onNavigateToJournal: (Uuid) -> Unit = {},
    onShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MediaDetailViewModel =
        koinViewModel(
            parameters = { parametersOf(mediaId) },
        ),
) {
    val audioRouteRepository: AudioRouteRepository = koinInject()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewerState by viewModel.viewerState.collectAsStateWithLifecycle()
    val presenterState by viewModel.presenterState.collectAsStateWithLifecycle()
    val outputSelection by audioRouteRepository.outputDevices.collectAsStateWithLifecycle()
    val audioPlaybackManager: AudioPlaybackManager = koinInject()
    var audioProgress by remember { mutableFloatStateOf(0f) }
    var playingAudioUri by remember { mutableStateOf<String?>(null) }
    val recordingPlaybackTitle = stringResource(Res.string.library_recording_playback_title)

    fun toggleAudio(
        uri: String,
        durationMs: Long,
        noteId: Uuid,
    ) {
        if (playingAudioUri == uri) {
            audioPlaybackManager.pausePlayback()
            playingAudioUri = null
        } else {
            audioPlaybackManager.startPlayback(
                uri = uri,
                metadata = AudioPlaybackMetadata(title = recordingPlaybackTitle, noteId = noteId),
                onProgressUpdated = { audioProgress = it },
                onPlaybackCompleted = {
                    audioProgress = 0f
                    playingAudioUri = null
                },
            )
            playingAudioUri = uri
        }
    }

    BoxWithConstraints(modifier) {
        val isExpanded = maxWidth >= 840.dp

        MediaDetailContent(
            state = uiState,
            viewerState = viewerState,
            presenterState = presenterState,
            outputSelection = outputSelection,
            onOutputDeviceSelected = audioRouteRepository::selectOutputDevice,
            isExpanded = isExpanded,
            onBack = {
                viewModel.stopPresenting()
                onBack()
            },
            onSelectMedia = viewModel::selectMedia,
            onNavigateToJournal = onNavigateToJournal,
            onShare = onShare,
            onStartPresenting = viewModel::startPresenting,
            onStopPresenting = viewModel::stopPresenting,
            onPresentItem = viewModel::presentItem,
            audioProgress = audioProgress,
            isAudioPlaying = { it == playingAudioUri },
            onToggleAudio = ::toggleAudio,
            onSeekAudio = { uri, position, duration ->
                if (playingAudioUri == uri && duration > 0L) audioPlaybackManager.seekTo(position)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Stateless media detail layout.
 *
 * On compact screens, the image fills the screen and metadata is in a bottom sheet.
 * On expanded screens, image and metadata sit side-by-side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailContent(
    state: MediaDetailUiState,
    viewerState: MediaViewerState = MediaViewerState(),
    presenterState: PresenterState = PresenterState(),
    outputSelection: MediaDeviceSelectionUiState = defaultLibraryOutputSelection(),
    onOutputDeviceSelected: (String) -> Unit = {},
    isExpanded: Boolean,
    onBack: () -> Unit,
    onSelectMedia: (Int) -> Unit = {},
    onNavigateToJournal: (Uuid) -> Unit = {},
    onShare: (String) -> Unit = {},
    onStartPresenting: () -> Unit = {},
    onStopPresenting: () -> Unit = {},
    onPresentItem: (Int) -> Unit = {},
    audioProgress: Float = 0f,
    isAudioPlaying: (String) -> Boolean = { false },
    onToggleAudio: (String, Long, Uuid) -> Unit = { _, _, _ -> },
    onSeekAudio: (String, Float, Long) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (LocalWorkspaceEnabled.current && state !is MediaDetailUiState.ImageContent && state !is MediaDetailUiState.VideoContent) {
        WorkspacePanel(modifier) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(
                    stringResource(Res.string.library_memory_title),
                    actions = {
                        IconButton(onClick = onBack) { Icon(PlatformIcons.back(), stringResource(Res.string.cd_back_to_library)) }
                    },
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (state) {
                        MediaDetailUiState.Loading -> {
                            CircularProgressIndicator()
                        }

                        is MediaDetailUiState.Error -> {
                            Text(stringResource(state.message), Modifier.padding(Spacing.lg))
                        }

                        is MediaDetailUiState.AudioContent -> {
                            AudioMediaDetailContent(
                                state,
                                audioProgress,
                                isAudioPlaying(state.mediaRef),
                                { onToggleAudio(state.mediaRef, state.durationMs, state.mediaId) },
                                { onSeekAudio(state.mediaRef, it, state.durationMs) },
                                onBack,
                            )
                        }
                    }
                }
            }
        }
        return
    }
    when (state) {
        is MediaDetailUiState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is MediaDetailUiState.Error -> {
            Column(
                modifier = modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(state.message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                TextButton(onClick = onBack) {
                    Text(stringResource(UiRes.string.common_go_back))
                }
            }
        }

        is MediaDetailUiState.ImageContent -> {
            MediaDetailLayout(
                mediaRef = state.mediaRef,
                isVideo = false,
                createdAt = state.createdAt,
                locationDisplayName = state.locationDisplayName,
                journals = state.journals,
                exif = state.exif,
                viewerState = viewerState,
                isExpanded = isExpanded,
                onBack = onBack,
                onSelectMedia = onSelectMedia,
                onNavigateToJournal = onNavigateToJournal,
                onShare = { onShare(state.mediaRef) },
                presenterState = presenterState,
                outputSelection = outputSelection,
                onOutputDeviceSelected = onOutputDeviceSelected,
                onStartPresenting = onStartPresenting,
                onStopPresenting = onStopPresenting,
                onPresentItem = onPresentItem,
                modifier = modifier,
            )
        }

        is MediaDetailUiState.VideoContent -> {
            MediaDetailLayout(
                mediaRef = state.mediaRef,
                isVideo = true,
                createdAt = state.createdAt,
                locationDisplayName = state.locationDisplayName,
                journals = state.journals,
                viewerState = viewerState,
                isExpanded = isExpanded,
                onBack = onBack,
                onSelectMedia = onSelectMedia,
                onNavigateToJournal = onNavigateToJournal,
                onShare = { onShare(state.mediaRef) },
                presenterState = presenterState,
                outputSelection = outputSelection,
                onOutputDeviceSelected = onOutputDeviceSelected,
                onStartPresenting = onStartPresenting,
                onStopPresenting = onStopPresenting,
                onPresentItem = onPresentItem,
                modifier = modifier,
            )
        }

        is MediaDetailUiState.AudioContent -> {
            AudioMediaDetailContent(
                state = state,
                progress = audioProgress,
                isPlaying = isAudioPlaying(state.mediaRef),
                onToggle = { onToggleAudio(state.mediaRef, state.durationMs, state.mediaId) },
                onSeek = { onSeekAudio(state.mediaRef, it, state.durationMs) },
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AudioMediaDetailContent(
    state: MediaDetailUiState.AudioContent,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier.fillMaxSize().then(
                if (LocalWorkspaceEnabled.current) {
                    Modifier.verticalScroll(
                        rememberScrollState(),
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!LocalWorkspaceEnabled.current) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start).padding(Spacing.md),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiRes.string.common_back))
            }
        }
        Icon(
            painter = PlatformIcons.audioFile(),
            contentDescription = stringResource(Res.string.library_audio_recording),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(stringResource(Res.string.library_audio_recording), style = MaterialTheme.typography.headlineSmall)
        Text(
            text =
                state.createdAt
                    .toString()
                    .replace('T', ' ')
                    .substringBefore('.'),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        androidx.compose.material3.Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth(0.82f),
            enabled = state.durationMs > 0,
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(0.82f),
        )
        Spacer(Modifier.height(Spacing.md))
        FilledTonalIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
            Icon(
                painter =
                    if (isPlaying) {
                        PlatformIcons.pause()
                    } else {
                        PlatformIcons.play()
                    },
                contentDescription =
                    if (isPlaying) {
                        stringResource(Res.string.cd_pause_audio)
                    } else {
                        stringResource(Res.string.cd_play_audio)
                    },
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDetailLayout(
    mediaRef: String,
    isVideo: Boolean,
    createdAt: Instant,
    locationDisplayName: String? = null,
    journals: List<JournalReference>,
    exif: ExifDisplayData? = null,
    viewerState: MediaViewerState = MediaViewerState(),
    isExpanded: Boolean,
    onBack: () -> Unit,
    onSelectMedia: (Int) -> Unit = {},
    onNavigateToJournal: (Uuid) -> Unit = {},
    onShare: () -> Unit = {},
    presenterState: PresenterState = PresenterState(),
    outputSelection: MediaDeviceSelectionUiState = defaultLibraryOutputSelection(),
    onOutputDeviceSelected: (String) -> Unit = {},
    onStartPresenting: () -> Unit = {},
    onStopPresenting: () -> Unit = {},
    onPresentItem: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (LocalWorkspaceEnabled.current) {
        var immersive by rememberSaveable { mutableStateOf(false) }
        val stateHolder =
            androidx.compose.runtime.saveable
                .rememberSaveableStateHolder()
        if (immersive) {
            stateHolder.SaveableStateProvider("immersive") {
                CompactMediaDetailViewer(
                    mediaRef,
                    isVideo,
                    createdAt,
                    locationDisplayName,
                    journals,
                    exif,
                    viewerState,
                    presenterState,
                    outputSelection,
                    onOutputDeviceSelected,
                    onBack = { immersive = false },
                    onSelectMedia = onSelectMedia,
                    onNavigateToJournal = onNavigateToJournal,
                    onShare = onShare,
                    onStartPresenting = onStartPresenting,
                    onStopPresenting = onStopPresenting,
                    onPresentItem = onPresentItem,
                    modifier = modifier,
                )
            }
            return
        }
        stateHolder.SaveableStateProvider("workspace") {
            WorkspaceSupportingSheet(
                stringResource(Res.string.library_memory_details),
                modifier,
                focus = {
                    Column(Modifier.fillMaxSize()) {
                        PanelHeader(stringResource(Res.string.library_memory_title), actions = {
                            IconButton(onClick = onBack) { Icon(PlatformIcons.back(), stringResource(Res.string.cd_back_to_library)) }
                            IconButton(onClick = onShare) { Icon(PlatformIcons.share(), stringResource(Res.string.cd_share_memory)) }
                            IconButton(onClick = { immersive = true }) {
                                Icon(Icons.Default.Fullscreen, stringResource(Res.string.cd_view_full_screen))
                            }
                        })
                        WorkspaceMediaViewer(
                            mediaRef,
                            isVideo,
                            viewerState,
                            onSelectMedia,
                            onBack,
                            { immersive = true },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                },
                supporting = {
                    MediaDetailTabletopControls(
                        createdAt = createdAt,
                        locationDisplayName = locationDisplayName,
                        isVideo = isVideo,
                        journals = journals,
                        exif = exif,
                        presenterState = presenterState,
                        outputSelection = outputSelection,
                        onBack = onBack,
                        onShare = onShare,
                        onNavigateToJournal = onNavigateToJournal,
                        onOutputDeviceSelected = onOutputDeviceSelected,
                        onStartPresenting = onStartPresenting,
                        onStopPresenting = onStopPresenting,
                        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                    )
                },
            )
        }
        return
    }
    FoldableTabletopLayout(
        modifier = modifier,
        minPaneHeight = 240.dp,
        topPane = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                MediaContent(
                    mediaRef = mediaRef,
                    isVideo = isVideo,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        bottomPane = {
            MediaDetailTabletopControls(
                createdAt = createdAt,
                locationDisplayName = locationDisplayName,
                isVideo = isVideo,
                journals = journals,
                exif = exif,
                presenterState = presenterState,
                outputSelection = outputSelection,
                onBack = onBack,
                onShare = onShare,
                onNavigateToJournal = onNavigateToJournal,
                onOutputDeviceSelected = onOutputDeviceSelected,
                onStartPresenting = onStartPresenting,
                onStopPresenting = onStopPresenting,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
            )
        },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        MediaContent(
                            mediaRef = mediaRef,
                            isVideo = isVideo,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                endPane = {
                    MediaDetailTabletopControls(
                        createdAt = createdAt,
                        locationDisplayName = locationDisplayName,
                        isVideo = isVideo,
                        journals = journals,
                        exif = exif,
                        presenterState = presenterState,
                        outputSelection = outputSelection,
                        onBack = onBack,
                        onShare = onShare,
                        onNavigateToJournal = onNavigateToJournal,
                        onOutputDeviceSelected = onOutputDeviceSelected,
                        onStartPresenting = onStartPresenting,
                        onStopPresenting = onStopPresenting,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                    )
                },
                standardContent = {
                    if (isExpanded) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier.weight(2f).fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MediaContent(mediaRef = mediaRef, isVideo = isVideo)
                            }
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .padding(Spacing.lg),
                            ) {
                                MediaDetailTabletopControls(
                                    createdAt = createdAt,
                                    locationDisplayName = locationDisplayName,
                                    isVideo = isVideo,
                                    journals = journals,
                                    exif = exif,
                                    presenterState = presenterState,
                                    outputSelection = outputSelection,
                                    onBack = onBack,
                                    onShare = onShare,
                                    onNavigateToJournal = onNavigateToJournal,
                                    onOutputDeviceSelected = onOutputDeviceSelected,
                                    onStartPresenting = onStartPresenting,
                                    onStopPresenting = onStopPresenting,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        CompactMediaDetailViewer(
                            currentMediaRef = mediaRef,
                            currentIsVideo = isVideo,
                            createdAt = createdAt,
                            locationDisplayName = locationDisplayName,
                            journals = journals,
                            exif = exif,
                            viewerState = viewerState,
                            presenterState = presenterState,
                            outputSelection = outputSelection,
                            onOutputDeviceSelected = onOutputDeviceSelected,
                            onBack = onBack,
                            onSelectMedia = onSelectMedia,
                            onNavigateToJournal = onNavigateToJournal,
                            onShare = onShare,
                            onStartPresenting = onStartPresenting,
                            onStopPresenting = onStopPresenting,
                            onPresentItem = onPresentItem,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
            )
        },
    )
}

@Composable
private fun MediaDetailTabletopControls(
    createdAt: Instant,
    locationDisplayName: String?,
    isVideo: Boolean,
    journals: List<JournalReference>,
    exif: ExifDisplayData?,
    presenterState: PresenterState,
    outputSelection: MediaDeviceSelectionUiState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onNavigateToJournal: (Uuid) -> Unit,
    onOutputDeviceSelected: (String) -> Unit,
    onStartPresenting: () -> Unit,
    onStopPresenting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(UiRes.string.common_back),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (presenterState.isExternalDisplayAvailable) {
                FilledTonalIconButton(
                    onClick = {
                        if (presenterState.isPresenting) {
                            onStopPresenting()
                        } else {
                            onStartPresenting()
                        }
                    },
                ) {
                    Icon(
                        imageVector =
                            if (presenterState.isPresenting) {
                                Icons.AutoMirrored.Filled.StopScreenShare
                            } else {
                                Icons.AutoMirrored.Filled.ScreenShare
                            },
                        contentDescription =
                            if (presenterState.isPresenting) {
                                stringResource(Res.string.cd_stop_presenting)
                            } else {
                                stringResource(Res.string.cd_present)
                            },
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onShare,
                modifier = Modifier.testTag("media_detail_share_action"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(UiRes.string.common_share),
                )
            }
        }

        if (presenterState.isPresenting && isVideo) {
            ExternalDisplayAudioRouteControl(
                outputSelection = outputSelection,
                onOutputDeviceSelected = onOutputDeviceSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MetadataContent(
            createdAt = createdAt,
            locationDisplayName = locationDisplayName,
            isVideo = isVideo,
            journals = journals,
            exif = exif,
            onNavigateToJournal = onNavigateToJournal,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactMediaDetailViewer(
    currentMediaRef: String,
    currentIsVideo: Boolean,
    createdAt: Instant,
    locationDisplayName: String?,
    journals: List<JournalReference>,
    exif: ExifDisplayData?,
    viewerState: MediaViewerState,
    presenterState: PresenterState,
    outputSelection: MediaDeviceSelectionUiState,
    onOutputDeviceSelected: (String) -> Unit,
    onBack: () -> Unit,
    onSelectMedia: (Int) -> Unit,
    onNavigateToJournal: (Uuid) -> Unit,
    onShare: () -> Unit,
    onStartPresenting: () -> Unit,
    onStopPresenting: () -> Unit,
    onPresentItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isChromeVisible by rememberSaveable { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    val focusRequester = FocusRequester()
    val pagerState =
        rememberPagerState(
            initialPage = viewerState.currentIndex,
            pageCount = { viewerState.mediaItems.size.coerceAtLeast(1) },
        )
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var sheetOffsetPx by remember { mutableFloatStateOf(0f) }
    var isSheetOffsetInitialized by remember { mutableStateOf(false) }
    val isSheetVisible = sheetHeightPx > 0f && sheetOffsetPx < sheetHeightPx

    if (presenterState.isPresenting) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    LaunchedEffect(viewerState.currentIndex, viewerState.totalItems) {
        if (viewerState.totalItems > 0 && pagerState.currentPage != viewerState.currentIndex) {
            pagerState.scrollToPage(viewerState.currentIndex)
        }
    }

    LaunchedEffect(pagerState, viewerState.totalItems) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page in viewerState.mediaItems.indices && page != viewerState.currentIndex) {
                isCurrentPageZoomed = false
                onSelectMedia(page)
            }
        }
    }

    fun updateSheetOffset(dragAmount: Float): Boolean {
        if (sheetHeightPx <= 0f) return false
        val newOffset = (sheetOffsetPx + dragAmount).coerceIn(0f, sheetHeightPx)
        if (newOffset == sheetOffsetPx) return false
        sheetOffsetPx = newOffset
        return true
    }

    fun settleSheet() {
        if (sheetHeightPx <= 0f) return
        val shouldExpand = sheetOffsetPx < sheetHeightPx * 0.7f
        sheetOffsetPx = if (shouldExpand) 0f else sheetHeightPx
    }

    val viewerItems =
        if (viewerState.mediaItems.isNotEmpty()) {
            viewerState.mediaItems
        } else {
            listOf(
                MediaViewerItem(
                    uid = Uuid.random(),
                    uri = currentMediaRef,
                    isVideo = currentIsVideo,
                ),
            )
        }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (presenterState.isPresenting) {
                            Modifier
                                .focusRequester(focusRequester)
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (event.key) {
                                        Key.DirectionRight -> {
                                            val next =
                                                (presenterState.currentIndex + 1)
                                                    .coerceAtMost(presenterState.mediaItems.size - 1)
                                            onPresentItem(next)
                                            true
                                        }

                                        Key.DirectionLeft -> {
                                            val prev =
                                                (presenterState.currentIndex - 1)
                                                    .coerceAtLeast(0)
                                            onPresentItem(prev)
                                            true
                                        }

                                        Key.Escape -> {
                                            onStopPresenting()
                                            true
                                        }

                                        else -> {
                                            false
                                        }
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
        ) {
            if (presenterState.isPresenting && presenterState.mediaItems.size > 1) {
                PresenterNavigationStrip(
                    items = presenterState.mediaItems,
                    currentIndex = presenterState.currentIndex,
                    onSelectItem = onPresentItem,
                )
            }

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = viewerItems.size > 1 && !isCurrentPageZoomed,
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) { page ->
                val item = viewerItems[page]
                MediaViewerPage(
                    item = item,
                    enableSwipeToDismiss = !isCurrentPageZoomed && !isSheetVisible,
                    onToggleChrome = { isChromeVisible = !isChromeVisible },
                    onZoomChanged = { zoomed ->
                        if (page == pagerState.currentPage) {
                            isCurrentPageZoomed = zoomed
                        }
                    },
                    onSheetDrag = { dragAmount ->
                        if (isCurrentPageZoomed) {
                            false
                        } else {
                            updateSheetOffset(dragAmount)
                        }
                    },
                    onSheetDragEnd = { settleSheet() },
                    onDismiss = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (presenterState.isPresenting) {
                if (currentIsVideo) {
                    ExternalDisplayAudioRouteControl(
                        outputSelection = outputSelection,
                        onOutputDeviceSelected = onOutputDeviceSelected,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg)
                                .padding(top = Spacing.sm),
                    )
                }
                FilledTonalButton(
                    onClick = onStopPresenting,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                ) {
                    Icon(Icons.AutoMirrored.Filled.StopScreenShare, contentDescription = null)
                    Spacer(
                        modifier = Modifier.size(Spacing.sm),
                    )
                    Text(stringResource(Res.string.library_stop_presenting))
                }
            }
        }

        if (isChromeVisible) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(UiRes.string.common_back),
                        )
                    }
                },
                actions = {
                    if (presenterState.isExternalDisplayAvailable) {
                        FilledTonalIconButton(
                            onClick = {
                                if (presenterState.isPresenting) {
                                    onStopPresenting()
                                } else {
                                    onStartPresenting()
                                }
                            },
                        ) {
                            Icon(
                                imageVector =
                                    if (presenterState.isPresenting) {
                                        Icons.AutoMirrored.Filled.StopScreenShare
                                    } else {
                                        Icons.AutoMirrored.Filled.ScreenShare
                                    },
                                contentDescription =
                                    if (presenterState.isPresenting) {
                                        stringResource(Res.string.cd_stop_presenting)
                                    } else {
                                        stringResource(Res.string.cd_present)
                                    },
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = onShare,
                        modifier = Modifier.testTag("media_detail_share_action"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(UiRes.string.common_share),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val previousHeight = sheetHeightPx
                        sheetHeightPx = size.height.toFloat()
                        if (!isSheetOffsetInitialized) {
                            sheetOffsetPx = sheetHeightPx
                            isSheetOffsetInitialized = true
                        } else if (previousHeight != sheetHeightPx) {
                            sheetOffsetPx =
                                if (isSheetVisible) {
                                    0f
                                } else {
                                    sheetHeightPx
                                }
                        }
                    }.graphicsLayer {
                        translationY = sheetOffsetPx
                    }.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg)
                        .padding(bottom = Spacing.xl),
            ) {
                SheetHandle()
                MetadataContent(
                    createdAt = createdAt,
                    locationDisplayName = locationDisplayName,
                    isVideo = currentIsVideo,
                    journals = journals,
                    exif = exif,
                    onNavigateToJournal = onNavigateToJournal,
                )
            }
        }
    }
}

@Composable
private fun ExternalDisplayAudioRouteControl(
    outputSelection: MediaDeviceSelectionUiState,
    onOutputDeviceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.library_external_display_audio),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MediaDeviceSelector(
            selection = outputSelection,
            onDeviceSelected = onOutputDeviceSelected,
            label = stringResource(Res.string.library_audio_output),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!outputSelection.isSelectionControllable) {
            Text(
                text = stringResource(Res.string.library_managed_by_android),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultLibraryOutputSelection(): MediaDeviceSelectionUiState =
    systemControlledSelection(
        MediaDeviceKind.AUDIO_OUTPUT,
    )

@Composable
private fun MediaViewerPage(
    item: MediaViewerItem,
    enableSwipeToDismiss: Boolean,
    onToggleChrome: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onSheetDrag: (Float) -> Boolean,
    onSheetDragEnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DismissibleMediaSurface(
        enabled = enableSwipeToDismiss,
        onSheetDrag = onSheetDrag,
        onSheetDragEnd = onSheetDragEnd,
        onDismiss = onDismiss,
        modifier = modifier,
    ) { surfaceModifier ->
        Box(
            modifier = surfaceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (item.isVideo) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(item.uid) {
                                detectTapGestures(onTap = { onToggleChrome() })
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    MediaContent(
                        mediaRef = item.uri,
                        isVideo = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                LaunchedEffect(item.uid) {
                    onZoomChanged(false)
                }
            } else {
                ZoomableMediaImage(
                    mediaRef = item.uri,
                    onTap = onToggleChrome,
                    onZoomChanged = onZoomChanged,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DismissibleMediaSurface(
    enabled: Boolean,
    onSheetDrag: (Float) -> Boolean,
    onSheetDragEnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var containerHeight by remember { mutableIntStateOf(1) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    content(
        modifier
            .onSizeChanged { containerHeight = it.height.coerceAtLeast(1) }
            .graphicsLayer {
                translationY = offsetY
                val progress = (offsetY / containerHeight.toFloat()).coerceIn(0f, 1f)
                alpha = 1f - (progress * 0.35f)
                val scale = 1f - (progress * 0.08f)
                scaleX = scale
                scaleY = scale
            }.pointerInput(enabled) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        onSheetDragEnd()
                        scope.launch {
                            if (enabled && offsetY > containerHeight * 0.18f) {
                                onDismiss()
                            } else {
                                offsetY = 0f
                            }
                        }
                    },
                    onDragCancel = {
                        onSheetDragEnd()
                        scope.launch { offsetY = 0f }
                    },
                ) { change, dragAmount ->
                    if (onSheetDrag(dragAmount)) {
                        change.consume()
                        return@detectVerticalDragGestures
                    }
                    if (!enabled) return@detectVerticalDragGestures
                    val newOffset = (offsetY + dragAmount).coerceAtLeast(0f)
                    if (newOffset != offsetY) {
                        change.consume()
                        offsetY = newOffset
                    }
                }
            },
    )
}

@Composable
private fun ZoomableMediaImage(
    mediaRef: String,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember(mediaRef) { mutableFloatStateOf(1f) }
    var offset by remember(mediaRef) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(mediaRef) { mutableStateOf(IntSize.Zero) }
    val transformableState =
        rememberTransformableState { _, zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(1f, 4f)
            scale = newScale
            offset =
                if (newScale <= 1f) {
                    Offset.Zero
                } else {
                    clampImageOffset(
                        offset = offset + panChange,
                        containerSize = containerSize,
                        scale = newScale,
                    )
                }
            onZoomChanged(newScale > 1f)
        }

    AsyncImage(
        model = mediaRef,
        contentDescription = stringResource(Res.string.cd_library_photo_full),
        contentScale = ContentScale.Fit,
        modifier =
            modifier
                .onSizeChanged { containerSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }.transformable(
                    state = transformableState,
                    canPan = { scale > 1f },
                ).pointerInput(mediaRef) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tapOffset ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset =
                                    doubleTapImageOffset(
                                        tapOffset = tapOffset,
                                        containerSize = containerSize,
                                        scale = scale,
                                    )
                            }
                            onZoomChanged(scale > 1f)
                        },
                    )
                },
    )

    LaunchedEffect(mediaRef) {
        onZoomChanged(false)
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier =
            Modifier
                .padding(top = Spacing.sm, bottom = Spacing.md)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
    )
}

@Composable
private fun PresenterNavigationStrip(
    items: List<PresenterMediaItem>,
    currentIndex: Int,
    onSelectItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = Spacing.md),
        ) {
            val thumbnailShape = RoundedCornerShape(6.dp)
            items.forEachIndexed { index, item ->
                item(key = item.uid) {
                    val isSelected = index == currentIndex
                    Box(
                        modifier =
                            Modifier
                                .size(if (isSelected) 56.dp else 48.dp)
                                .clip(thumbnailShape)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            thumbnailShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).clickable { onSelectItem(index) },
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceMediaViewer(
    mediaRef: String,
    isVideo: Boolean,
    state: MediaViewerState,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onImmersive: () -> Unit,
    modifier: Modifier,
) {
    val items = state.mediaItems.ifEmpty { listOf(MediaViewerItem(Uuid.parse("00000000-0000-0000-0000-000000000000"), mediaRef, isVideo)) }
    val pager = rememberPagerState(initialPage = state.currentIndex.coerceIn(items.indices), pageCount = { items.size })
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex in items.indices &&
            state.currentIndex != pager.currentPage
        ) {
            pager.scrollToPage(state.currentIndex)
        }
    }
    val currentIndex by rememberUpdatedState(state.currentIndex)
    val selectCurrent by rememberUpdatedState(onSelect)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { if (it != currentIndex) selectCurrent(it) } }
    HorizontalPager(pager, modifier, userScrollEnabled = !zoomed) { page ->
        MediaViewerPage(
            items[page],
            enableSwipeToDismiss = false,
            onToggleChrome = onImmersive,
            onZoomChanged = { zoomed = it },
            onSheetDrag = { false },
            onSheetDragEnd = {},
            onDismiss = onBack,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MediaContent(
    mediaRef: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isVideo) {
        VideoPlayerContent(
            uri = mediaRef,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        AsyncImage(
            model = mediaRef,
            contentDescription = stringResource(Res.string.cd_library_photo_full),
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MetadataContent(
    createdAt: Instant,
    locationDisplayName: String? = null,
    isVideo: Boolean,
    journals: List<JournalReference> = emptyList(),
    exif: ExifDisplayData? = null,
    onNavigateToJournal: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = stringResource(Res.string.library_details_title),
            style = MaterialTheme.typography.titleLarge,
        )

        // Date
        MetadataRow(
            icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            label = stringResource(Res.string.library_metadata_captured),
            value = createdAt.toReadableDateTimeShort(),
        )

        // Type
        MetadataRow(
            icon = {
                Icon(
                    if (isVideo) Icons.Filled.Videocam else Icons.Filled.Image,
                    contentDescription = null,
                )
            },
            label = stringResource(Res.string.library_metadata_type),
            value =
                if (isVideo) {
                    stringResource(Res.string.library_metadata_type_video)
                } else {
                    stringResource(Res.string.library_metadata_type_photo)
                },
        )

        // Location
        locationDisplayName?.let { name ->
            MetadataRow(
                icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                label = stringResource(Res.string.library_metadata_location),
                value = name,
            )
        }

        // Camera info
        exif?.let { data ->
            val cameraName =
                listOfNotNull(data.cameraMake, data.cameraModel)
                    .joinToString(" ")
                    .ifEmpty { null }
            val settings =
                listOfNotNull(
                    data.aperture?.let { "f/$it" },
                    data.shutterSpeed,
                    data.iso?.let { "ISO $it" },
                    data.focalLength?.let { "${it}mm" },
                ).joinToString("  ·  ")

            if (cameraName != null || settings.isNotEmpty()) {
                MetadataRow(
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    label = stringResource(Res.string.library_metadata_camera),
                    value = listOfNotNull(cameraName, settings.ifEmpty { null }).joinToString("\n"),
                )
            }
        }

        // Appears in journals
        if (journals.isNotEmpty()) {
            AppearsInSection(
                journals = journals,
                onNavigateToJournal = onNavigateToJournal,
            )
        }
    }
}

@Composable
private fun AppearsInSection(
    journals: List<JournalReference>,
    onNavigateToJournal: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(Res.string.library_appears_in),
            style = MaterialTheme.typography.titleMedium,
        )
        journals.forEach { journal ->
            TextButton(onClick = { onNavigateToJournal(journal.id) }) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = journal.title)
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun clampImageOffset(
    offset: Offset,
    containerSize: IntSize,
    scale: Float,
): Offset {
    if (containerSize == IntSize.Zero || scale <= 1f) return Offset.Zero

    val maxX = (containerSize.width * (scale - 1f)) / 2f
    val maxY = (containerSize.height * (scale - 1f)) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private fun doubleTapImageOffset(
    tapOffset: Offset,
    containerSize: IntSize,
    scale: Float,
): Offset {
    if (containerSize == IntSize.Zero || scale <= 1f) return Offset.Zero

    val center =
        Offset(
            x = containerSize.width / 2f,
            y = containerSize.height / 2f,
        )
    val targetOffset = (center - tapOffset) * (scale - 1f)
    return clampImageOffset(
        offset = targetOffset,
        containerSize = containerSize,
        scale = scale,
    )
}
