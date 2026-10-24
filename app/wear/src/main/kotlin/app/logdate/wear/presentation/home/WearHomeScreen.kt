package app.logdate.wear.presentation.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.wear.R
import app.logdate.wear.presentation.audio.components.AudioWaveform
import app.logdate.wear.presentation.common.SaveFeedback
import app.logdate.wear.presentation.recording.RecordingError
import app.logdate.wear.presentation.recording.RecordingPhase
import app.logdate.wear.presentation.recording.RecordingUiState
import app.logdate.wear.presentation.recording.WearRecordingViewModel
import app.logdate.wear.presentation.recording.formatDuration
import io.github.aakira.napier.Napier
import org.koin.compose.viewmodel.koinViewModel

private val STATUS_SLOT_HEIGHT = 52.dp
private val FOLLOW_UP_SLOT_HEIGHT = 34.dp
private val STATUS_HORIZONTAL_PADDING = 20.dp
private val SIDE_CONTROL_SIZE = 36.dp
private val RECORD_SURFACE_SIZE = 72.dp
private val READY_CORNER_RADIUS = 24.dp
private val STOP_CORNER_RADIUS = 16.dp
private const val PRESSED_SCALE = 0.94f

@Composable
fun WearHomeScreen(
    onNavigateToMoodCheckIn: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onNavigateToMore: () -> Unit,
    homeViewModel: WearHomeViewModel = koinViewModel(),
    recordingViewModel: WearRecordingViewModel = koinViewModel(),
) {
    val homeState by homeViewModel.uiState.collectAsState()
    val recordingState by recordingViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val microphonePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // A tap, so the recording is latched and keeps Pause and Discard; a bare press would never be released.
                recordingViewModel.onPress()
                recordingViewModel.onRelease()
            }
        }

    WearHomeContent(
        homeState = homeState,
        recordingState = recordingState,
        onNavigateToMoodCheckIn = onNavigateToMoodCheckIn,
        onNavigateToMemories = onNavigateToMemories,
        onNavigateToMore = onNavigateToMore,
        onPress = recordingViewModel::onPress,
        onRelease = recordingViewModel::onRelease,
        onPauseToggle = recordingViewModel::onPauseToggle,
        onDiscard = recordingViewModel::onDiscard,
        onUndo = recordingViewModel::onUndo,
        onAllowMicrophone = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenStorageSettings = { openStorageSettings(context) },
    )
}

/** Opens the system storage screen, or the main settings when this watch has no storage screen. */
private fun openStorageSettings(context: Context) {
    val screens = listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)
    for (action in screens) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Napier.w("No screen for $action on this watch", e)
        }
    }
}

@Composable
fun WearHomeContent(
    homeState: WearHomeUiState,
    modifier: Modifier = Modifier,
    onNavigateToMoodCheckIn: () -> Unit = {},
    onNavigateToMemories: () -> Unit = {},
    onNavigateToMore: () -> Unit = {},
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onPauseToggle: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onUndo: () -> Unit = {},
    onAllowMicrophone: () -> Unit = {},
    onOpenStorageSettings: () -> Unit = {},
    recordingState: RecordingUiState = RecordingUiState(),
) {
    ScreenScaffold(
        timeText = { TimeText() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RecorderStatus(homeState = homeState, recordingState = recordingState)
            RecorderControls(
                recordingState = recordingState,
                onPress = onPress,
                onRelease = onRelease,
                onPauseToggle = onPauseToggle,
                onDiscard = onDiscard,
                onNavigateToMemories = onNavigateToMemories,
                onNavigateToMoodCheckIn = onNavigateToMoodCheckIn,
            )
            RecorderFollowUp(
                recordingState = recordingState,
                onUndo = onUndo,
                onAllowMicrophone = onAllowMicrophone,
                onOpenStorageSettings = onOpenStorageSettings,
                onNavigateToMore = onNavigateToMore,
            )
        }
    }
}

/** The text above the record surface: what the recorder is doing or what to do next. */
@Composable
private fun RecorderStatus(
    homeState: WearHomeUiState,
    recordingState: RecordingUiState,
) {
    Box(
        modifier = Modifier.height(STATUS_SLOT_HEIGHT).fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        RecorderStatusContent(homeState, recordingState)
    }
}

@Composable
private fun RecorderStatusContent(
    homeState: WearHomeUiState,
    recordingState: RecordingUiState,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    when (recordingState.phase) {
        RecordingPhase.READY -> {
            ReadyStatus(homeState, recordingState.showGestureHint)
        }

        RecordingPhase.STARTING -> {
            StatusText(stringResource(R.string.wear_recorder_starting), secondary)
        }

        RecordingPhase.RECORDING -> {
            RecordingStatus(recordingState)
        }

        RecordingPhase.PAUSED -> {
            val message =
                if (recordingState.pausedByInterruption) {
                    R.string.wear_recorder_paused_interrupted
                } else {
                    R.string.wear_recorder_paused
                }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatDuration(recordingState.recordingDurationMs),
                    style = MaterialTheme.typography.numeralExtraSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (recordingState.confirmingDiscard) {
                    DiscardQuestion()
                } else {
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        RecordingPhase.SAVING -> {
            StatusText(stringResource(R.string.wear_recording_saving), secondary)
        }

        RecordingPhase.SAVED -> {
            SavedStatus(recordingState.saveFeedback)
        }

        RecordingPhase.TOO_SHORT -> {
            StatusText(stringResource(R.string.wear_recording_too_short), secondary)
        }

        RecordingPhase.ERROR -> {
            StatusText(errorText(recordingState.error), MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReadyStatus(
    homeState: WearHomeUiState,
    showGestureHint: Boolean,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    if (showGestureHint) {
        StatusText(stringResource(R.string.wear_recorder_hint), secondary)
        return
    }
    StatusText(homeState.greeting?.let { stringResource(it) }.orEmpty(), secondary)
    if (homeState.syncBadge == SyncBadge.NONE) return
    val (text, color) =
        when (homeState.syncBadge) {
            SyncBadge.SYNCING -> stringResource(R.string.wear_home_syncing) to secondary
            SyncBadge.ERROR -> stringResource(R.string.wear_home_sync_issue) to MaterialTheme.colorScheme.error
            SyncBadge.NONE -> "" to secondary
        }
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun RecordingStatus(recordingState: RecordingUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDuration(recordingState.recordingDurationMs),
            style = MaterialTheme.typography.numeralSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (recordingState.confirmingDiscard) {
            DiscardQuestion()
        } else {
            AudioWaveform(
                audioLevels = recordingState.audioLevels,
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

/** The discard question under the timer. A live region, so a screen reader speaks it when it appears. */
@Composable
private fun DiscardQuestion() {
    Text(
        text = stringResource(R.string.wear_recorder_discard_confirm),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StatusText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.padding(horizontal = STATUS_HORIZONTAL_PADDING),
    )
}

@Composable
private fun SavedStatus(feedback: SaveFeedback?) {
    val detail =
        when (feedback) {
            SaveFeedback.SYNCING_TO_PHONE -> R.string.wear_saved_detail_syncing
            SaveFeedback.SAVED_LOCALLY -> R.string.wear_saved_detail_watch_only
            null -> null
        }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.wear_recording_saved),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (detail != null) {
            Text(
                text = stringResource(detail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun errorText(error: RecordingError?): String =
    stringResource(
        when (error) {
            RecordingError.MICROPHONE_PERMISSION_DENIED -> R.string.wear_recorder_error_microphone
            RecordingError.NOT_ENOUGH_STORAGE -> R.string.wear_recorder_error_storage
            RecordingError.RECORDER_UNAVAILABLE -> R.string.wear_recorder_error_unavailable
            RecordingError.SAVE_FAILED -> R.string.wear_recorder_error_save
            RecordingError.RECORDING_LOST -> R.string.wear_recorder_error_lost
            null -> R.string.wear_recording_error
        },
    )

/** The record surface, with pause and discard on either side while a tap-started recording runs. */
@Composable
private fun RecorderControls(
    recordingState: RecordingUiState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onPauseToggle: () -> Unit,
    onDiscard: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onNavigateToMoodCheckIn: () -> Unit,
) {
    val phase = recordingState.phase
    val isIdle = phase == RecordingPhase.READY
    val showRecordingControls =
        recordingState.isLatched && (phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED)
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            showRecordingControls -> {
                SideControl(
                    icon = if (phase == RecordingPhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                    description =
                        stringResource(
                            if (phase == RecordingPhase.PAUSED) R.string.wear_recording_resume else R.string.wear_recording_pause,
                        ),
                    onClick = onPauseToggle,
                )
            }

            isIdle -> {
                SideControl(Icons.Default.Headphones, stringResource(R.string.wear_home_memories), onNavigateToMemories)
            }

            else -> {
                Spacer(Modifier.size(SIDE_CONTROL_SIZE))
            }
        }
        RecordSurface(phase = phase, isLatched = recordingState.isLatched, onPress = onPress, onRelease = onRelease)
        when {
            showRecordingControls && recordingState.confirmingDiscard -> {
                SideControl(
                    icon = Icons.Default.DeleteForever,
                    description = stringResource(R.string.wear_recorder_discard_confirm),
                    onClick = onDiscard,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                )
            }

            showRecordingControls -> {
                SideControl(Icons.Default.Close, stringResource(R.string.wear_recorder_discard), onDiscard)
            }

            isIdle -> {
                SideControl(Icons.Default.Mood, stringResource(R.string.wear_home_mood_checkin), onNavigateToMoodCheckIn)
            }

            else -> {
                Spacer(Modifier.size(SIDE_CONTROL_SIZE))
            }
        }
    }
}

@Composable
private fun SideControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(SIDE_CONTROL_SIZE),
        shapes = IconButtonDefaults.animatedShapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor),
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/** What sits under the record surface: More when idle, Undo for a saved note, or the fix for an error. */
@Composable
private fun RecorderFollowUp(
    recordingState: RecordingUiState,
    onUndo: () -> Unit,
    onAllowMicrophone: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onNavigateToMore: () -> Unit,
) {
    Box(
        modifier = Modifier.height(FOLLOW_UP_SLOT_HEIGHT).fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            recordingState.phase == RecordingPhase.READY -> {
                CompactButton(
                    onClick = onNavigateToMore,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(R.string.wear_home_more)) },
                )
            }

            recordingState.phase == RecordingPhase.SAVED && recordingState.undoableNoteId != null -> {
                CompactButton(onClick = onUndo, label = { Text(stringResource(R.string.wear_recorder_undo)) })
            }

            recordingState.phase == RecordingPhase.ERROR && recordingState.error == RecordingError.NOT_ENOUGH_STORAGE -> {
                CompactButton(onClick = onOpenStorageSettings, label = { Text(stringResource(R.string.wear_recorder_open_storage)) })
            }

            recordingState.phase == RecordingPhase.ERROR && recordingState.error == RecordingError.MICROPHONE_PERMISSION_DENIED -> {
                CompactButton(onClick = onAllowMicrophone, label = { Text(stringResource(R.string.wear_onboarding_permissions_allow)) })
            }
        }
    }
}

/**
 * The circular record control. Touch starts a recording; a quick lift latches it, a long hold saves
 * on release. Assistive services get one action that behaves like a tap.
 */
@Composable
fun RecordSurface(
    phase: RecordingPhase,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    isLatched: Boolean = false,
) {
    val isRecording = phase == RecordingPhase.RECORDING
    var pressed by remember { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val scale by animateFloatAsState(
        targetValue =
            when {
                pressed -> PRESSED_SCALE
                isRecording -> 1.1f
                else -> 1f
            },
        animationSpec = motion.defaultSpatialSpec(),
        label = "recordScale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = recordSurfaceCornerRadius(phase, isLatched, pressed),
        animationSpec = motion.defaultSpatialSpec(),
        label = "recordShape",
    )
    val color by animateColorAsState(
        targetValue =
            when (phase) {
                RecordingPhase.RECORDING -> MaterialTheme.colorScheme.primary
                RecordingPhase.READY, RecordingPhase.SAVED -> MaterialTheme.colorScheme.primaryContainer
                RecordingPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        animationSpec = motion.defaultEffectsSpec(),
        label = "recordColor",
    )
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val description = stringResource(recordSurfaceDescription(phase, isLatched))

    Box(
        modifier =
            modifier
                .size(RECORD_SURFACE_SIZE)
                .scale(scale)
                .clip(RoundedCornerShape(cornerRadius))
                .background(color)
                .pointerInput(Unit) {
                    // Not detectTapGestures: it reports a cancelled press when the finger slides off this
                    // small circle, which would end a hold-to-talk recording while the finger is still down.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        currentOnPress()
                        try {
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pressed = false
                            currentOnRelease()
                        }
                    }
                }.semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        currentOnPress()
                        currentOnRelease()
                        true
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        RecordSurfaceIcon(phase = phase, isLatched = isLatched)
    }
}

/**
 * The record control's corner radius for a state, so it morphs between shapes: a soft square when
 * ready, a circle while it is held or working, and a tighter square once Stop is what a tap does.
 */
private fun recordSurfaceCornerRadius(
    phase: RecordingPhase,
    isLatched: Boolean,
    pressed: Boolean,
): Dp =
    when {
        pressed -> RECORD_SURFACE_SIZE / 2
        phase == RecordingPhase.RECORDING && isLatched -> STOP_CORNER_RADIUS
        phase == RecordingPhase.PAUSED -> STOP_CORNER_RADIUS
        phase == RecordingPhase.READY || phase == RecordingPhase.TOO_SHORT || phase == RecordingPhase.ERROR -> READY_CORNER_RADIUS
        else -> RECORD_SURFACE_SIZE / 2
    }

@Composable
private fun RecordSurfaceIcon(
    phase: RecordingPhase,
    isLatched: Boolean,
) {
    val isRecording = phase == RecordingPhase.RECORDING
    val (icon, tint) =
        when {
            phase == RecordingPhase.SAVED -> Icons.Default.Check to MaterialTheme.colorScheme.onPrimaryContainer
            phase == RecordingPhase.READY -> Icons.Default.Mic to MaterialTheme.colorScheme.onPrimaryContainer
            isRecording && isLatched -> Icons.Default.Stop to MaterialTheme.colorScheme.onPrimary
            isRecording -> Icons.Default.Mic to MaterialTheme.colorScheme.onPrimary
            phase == RecordingPhase.PAUSED -> Icons.Default.Stop to MaterialTheme.colorScheme.onSurface
            else -> Icons.Default.Mic to MaterialTheme.colorScheme.onSurface
        }
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = tint)
}

private fun recordSurfaceDescription(
    phase: RecordingPhase,
    isLatched: Boolean,
): Int =
    when {
        phase == RecordingPhase.RECORDING && isLatched -> R.string.wear_recording_stop
        phase == RecordingPhase.PAUSED -> R.string.wear_recording_stop
        else -> R.string.wear_home_record_audio
    }
