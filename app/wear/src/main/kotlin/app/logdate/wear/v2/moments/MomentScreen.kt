package app.logdate.wear.v2.moments

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.R
import app.logdate.wear.v2.playback.PlaybackFailure
import app.logdate.wear.v2.playback.PlaybackSpeed
import app.logdate.wear.v2.playback.PlaybackState
import app.logdate.wear.v2.playback.captionAt
import app.logdate.wear.v2.playback.fullTranscript
import java.text.NumberFormat
import kotlin.time.Duration.Companion.milliseconds

/**
 * One moment. A voice note opens on its player, where turning the crown moves through the
 * recording; swiping up shows its transcript, place and actions. Any other moment opens on those
 * details.
 */
@Composable
fun MomentScreen(
    state: MomentUiState,
    actions: MomentActions,
    modifier: Modifier = Modifier,
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
) {
    Box(modifier = modifier.fillMaxSize().testTag(MomentTags.SCREEN)) {
        when (state) {
            MomentUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag(MomentTags.LOADING))
            MomentUiState.Missing -> MissingMoment(timeSource)
            is MomentUiState.Ready -> ReadyMoment(state, actions, timeSource)
        }
    }
}

/**
 * A moment's details: when, its words, its place, Play on for a voice note when [onPlayOn] is set,
 * and Delete when [onDelete] is set.
 */
@Composable
fun MomentDetailsPage(
    state: MomentUiState.Ready,
    onDelete: (() -> Unit)?,
    onPlayOn: (() -> Unit)? = null,
    onOpenOnPhone: (() -> Unit)? = null,
    phoneHandoffMessage: String? = null,
    modifier: Modifier = Modifier,
    timeSource: TimeSource = TimeTextDefaults.rememberTimeSource(TimeTextDefaults.timeFormat()),
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val note = state.note
    val body = momentBody(note)
    val place = note.location?.displayName
    ScreenScaffold(scrollState = listState, timeText = { TimeText(timeSource = timeSource) }) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier.testTag(MomentTags.DETAILS)) {
            item {
                ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(momentDayAndTime(note.creationTimestamp, state.timeZone))
                }
            }
            if (body != null) item { DetailText(body) }
            if (note is JournalNote.Handwriting) item { MomentGlance(note, inkHeight = 96.dp) }
            if (place != null) item { PlaceLine(place) }
            if (onPlayOn != null && note is JournalNote.Audio) {
                item { PlayOnButton(onPlayOn, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
            }
            if (onOpenOnPhone != null) {
                item {
                    Button(
                        onClick = onOpenOnPhone,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.wear_moment_open_on_phone)) },
                    )
                }
            }
            if (phoneHandoffMessage != null) item { DetailText(phoneHandoffMessage) }
            if (state.deleteFailed) {
                item { DetailText(stringResource(R.string.wear_moment_delete_failed), MaterialTheme.colorScheme.error) }
            }
            if (onDelete != null) {
                item { DeleteButton(onDelete, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
            }
        }
    }
}

/** What a moment says: its text, transcript or caption; null when it has no words. */
internal fun momentBody(note: JournalNote): String? =
    when (note) {
        is JournalNote.Text -> note.content.trim().takeIf(String::isNotEmpty)
        is JournalNote.Audio -> fullTranscript(note)
        is JournalNote.Image -> note.caption.trim().takeIf(String::isNotEmpty)
        is JournalNote.Video -> note.caption.trim().takeIf(String::isNotEmpty)
        is JournalNote.Handwriting -> null
    }

@Composable
private fun ReadyMoment(
    state: MomentUiState.Ready,
    actions: MomentActions,
    timeSource: TimeSource,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var choosingOutput by rememberSaveable { mutableStateOf(false) }
    val askToDelete: (() -> Unit)? = actions.onDelete?.let { { confirmingDelete = true } }
    val note = state.note
    if (note is JournalNote.Audio) {
        val available = actions.onPlayOn != null || actions.onPlayOnPhone != null
        VoiceMoment(
            note,
            state,
            actions,
            askToDelete,
            timeSource,
            onChooseDestination = if (available) ({ choosingOutput = true }) else null,
        )
        PlayOnDialog(
            visible = choosingOutput,
            onDismiss = { choosingOutput = false },
            onWatch = actions.onPlayOn,
            onPhone = actions.onPlayOnPhone,
        )
    } else {
        MomentDetailsPage(
            state,
            askToDelete,
            onOpenOnPhone = actions.onOpenOnPhone,
            phoneHandoffMessage = actions.phoneHandoffMessage,
            timeSource = timeSource,
        )
    }
    DeleteDialog(
        visible = confirmingDelete,
        onDismiss = { confirmingDelete = false },
        onConfirm = {
            confirmingDelete = false
            actions.onDelete?.invoke()
        },
    )
}

@Composable
private fun VoiceMoment(
    note: JournalNote.Audio,
    state: MomentUiState.Ready,
    actions: MomentActions,
    onDelete: (() -> Unit)?,
    timeSource: TimeSource,
    onChooseDestination: (() -> Unit)?,
) {
    val pagerState = rememberPagerState(pageCount = { VOICE_PAGES })
    VerticalPagerScaffold(pagerState = pagerState) {
        VerticalPager(state = pagerState, rotaryScrollableBehavior = null) { page ->
            AnimatedPage(page, pagerState) {
                if (page == PLAYER_PAGE) {
                    MomentPlayerPage(note, state, actions)
                } else {
                    MomentDetailsPage(
                        state,
                        onDelete,
                        onPlayOn = onChooseDestination,
                        onOpenOnPhone = actions.onOpenOnPhone,
                        phoneHandoffMessage = actions.phoneHandoffMessage,
                        timeSource = timeSource,
                    )
                }
            }
        }
    }
}

@Composable
private fun MomentPlayerPage(
    note: JournalNote.Audio,
    state: MomentUiState.Ready,
    actions: MomentActions,
) {
    val active = state.playback as? PlaybackState.Active
    val positionMs = active?.positionMs ?: 0L
    val durationMs = active?.durationMs?.takeIf { it > 0L } ?: note.durationMs
    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val dayAndTime = momentDayAndTime(note.creationTimestamp, state.timeZone)
    val dayAndTimeStyle = CurvedTextStyle(MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
    ScreenScaffold(timeText = {}) { _ ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        actions.onCrown(event.verticalScrollPixels)
                        true
                    }.requestFocusOnHierarchyActive()
                    .focusable()
                    .testTag(MomentTags.PLAYER),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize().padding(PROGRESS_INSET),
                strokeWidth = PROGRESS_STROKE,
            )
            CurvedLayout(modifier = Modifier.fillMaxSize()) {
                curvedText(
                    text = dayAndTime,
                    modifier = CurvedModifier.padding(radial = DAY_AND_TIME_INSET),
                    maxSweepAngle = DAY_AND_TIME_SWEEP,
                    style = dayAndTimeStyle,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = PLAYER_SIDE_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.playback !is PlaybackState.Failed) {
                        Text(
                            text =
                                stringResource(
                                    R.string.wear_moment_position,
                                    formatClockDuration(positionMs.milliseconds),
                                    formatClockDuration(durationMs.milliseconds),
                                ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag(MomentTags.POSITION),
                        )
                    }
                    if (state.playback !is PlaybackState.Failed || state.playback.reason != PlaybackFailure.NO_OUTPUT) {
                        actions.onPlayOn?.let { AudioOutputButton(it) }
                    }
                }
                PlayerControls(state, actions)
                PlayerLine(note, state.playback, positionMs, actions.phoneHandoffMessage)
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: MomentUiState.Ready,
    actions: MomentActions,
) {
    val playing = (state.playback as? PlaybackState.Active)?.isPaused == false
    val noOutput = state.playback is PlaybackState.Failed && state.playback.reason == PlaybackFailure.NO_OUTPUT
    val speedText = speedLabel(state.speed)
    val speedDescription = stringResource(R.string.wear_moment_speed_description, speedText)
    Row(
        modifier = Modifier.padding(vertical = CONTROL_SPACING),
        horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!noOutput) {
            CompactButton(
                onClick = actions.onCycleSpeed,
                modifier = Modifier.testTag(MomentTags.SPEED).semantics { contentDescription = speedDescription },
                label = { Text(speedText) },
            )
        }
        FilledIconButton(
            onClick = actions.onPlayPause,
            enabled = (!noOutput || actions.onPlayOn == null) && state.playback !is PlaybackState.Preparing,
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize).testTag(MomentTags.PLAY_PAUSE),
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.wear_moment_pause else R.string.wear_moment_play),
            )
        }
        if (noOutput) {
            actions.onPlayOn?.let { AudioOutputButton(it) }
        } else {
            IconToggleButton(
                checked = state.skipSilence,
                onCheckedChange = actions.onSkipSilenceChange,
                modifier = Modifier.size(IconButtonDefaults.SmallButtonSize).testTag(MomentTags.SKIP_SILENCE),
            ) {
                Icon(imageVector = Icons.Filled.FastForward, contentDescription = stringResource(R.string.wear_moment_skip_silence))
            }
        }
    }
}

@Composable
private fun AudioOutputButton(onClick: () -> Unit) {
    val outputDescription = stringResource(R.string.wear_audio_output)
    FilledTonalIconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(36.dp)
                .testTag(MomentTags.CHOOSE_OUTPUT)
                .semantics { contentDescription = outputDescription },
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
    }
}

@Composable
private fun PlayOnDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onWatch: (() -> Unit)?,
    onPhone: (() -> Unit)?,
) {
    val listState = rememberTransformingLazyColumnState()
    Dialog(visible = visible, onDismissRequest = onDismiss) {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item { ListHeader { Text(stringResource(R.string.wear_moment_play_on)) } }
                if (onWatch != null) {
                    item {
                        Button(
                            onClick = {
                                onDismiss()
                                onWatch()
                            },
                            modifier = Modifier.fillMaxWidth().testTag(MomentTags.PLAY_ON_WATCH),
                            icon = { Icon(Icons.Filled.Headphones, contentDescription = null) },
                            label = { Text(stringResource(R.string.wear_moment_this_watch)) },
                            secondaryLabel = { Text(stringResource(R.string.wear_moment_watch_output_detail)) },
                        )
                    }
                }
                if (onPhone != null) {
                    item {
                        Button(
                            onClick = {
                                onDismiss()
                                onPhone()
                            },
                            modifier = Modifier.fillMaxWidth().testTag(MomentTags.PLAY_ON_PHONE),
                            icon = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
                            label = { Text(stringResource(R.string.wear_moment_phone)) },
                            secondaryLabel = { Text(stringResource(R.string.wear_moment_phone_output_detail)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLine(
    note: JournalNote.Audio,
    playback: PlaybackState,
    positionMs: Long,
    handoffMessage: String?,
) {
    val failed = playback is PlaybackState.Failed && handoffMessage == null
    val text = handoffMessage ?: if (playback is PlaybackState.Failed) failureMessage(playback.reason) else captionAt(note, positionMs)
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = if (failed) FAILURE_LINES else CAPTION_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(if (failed) FAILURE_WIDTH_FRACTION else CAPTION_WIDTH_FRACTION).testTag(MomentTags.CAPTION),
    )
}

@Composable
internal fun failureMessage(reason: PlaybackFailure): String =
    stringResource(
        when (reason) {
            PlaybackFailure.NO_OUTPUT -> R.string.wear_moment_failed_no_output
            PlaybackFailure.NOT_ON_WATCH -> R.string.wear_moment_failed_not_on_watch
            PlaybackFailure.NO_ROOM -> R.string.wear_moment_failed_no_room
            PlaybackFailure.UNPLAYABLE -> R.string.wear_moment_failed_unplayable
        },
    )

/** [speed] as the person reads it, such as "1.5×" or "1,5×", in their locale. */
@Composable
private fun speedLabel(speed: PlaybackSpeed): String {
    val locale = LocalConfiguration.current.locales[0]
    val number =
        remember(locale, speed) {
            NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(speed.factor.toDouble())
        }
    return stringResource(R.string.wear_moment_speed, number)
}

@Composable
private fun MissingMoment(timeSource: TimeSource) {
    ScreenScaffold(timeText = { TimeText(timeSource = timeSource) }) { _ ->
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = MESSAGE_PADDING), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.wear_moment_missing),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(MomentTags.MISSING),
            )
        }
    }
}

@Composable
private fun DetailText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PlaceLine(place: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = stringResource(R.string.wear_moment_place),
            modifier = Modifier.size(PLACE_ICON_SIZE),
        )
        Text(
            text = place,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun PlayOnButton(
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(MomentTags.PLAY_ON),
        transformation = transformation,
        icon = { Icon(imageVector = Icons.Filled.Headphones, contentDescription = null) },
        label = { Text(stringResource(R.string.wear_moment_play_on)) },
    )
}

@Composable
private fun DeleteButton(
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(MomentTags.DELETE),
        transformation = transformation,
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        label = { Text(stringResource(R.string.wear_moment_delete)) },
    )
}

/** Asks before deleting; confirming also deletes the moment on the phone. */
@Composable
private fun DeleteDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        confirmButton = { DeleteConfirmButton(onConfirm) },
        title = { Text(stringResource(R.string.wear_moment_delete_title)) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        text = { Text(stringResource(R.string.wear_moment_delete_body)) },
    )
}

/**
 * What [DeleteDialog] shows, without the dialog's entrance animation, for screenshots.
 *
 * Rendered by app/wear screenshotTest MomentScreenshots, which SearchDeadCode does not treat as a root.
 */
@Suppress("unused", "FunctionName")
@Composable
fun DeleteDialogContent(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialogContent(
        confirmButton = { DeleteConfirmButton(onConfirm) },
        title = { Text(stringResource(R.string.wear_moment_delete_title)) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        text = { Text(stringResource(R.string.wear_moment_delete_body)) },
    )
}

@Composable
private fun DeleteConfirmButton(onConfirm: () -> Unit) {
    AlertDialogDefaults.ConfirmButton(onClick = onConfirm, modifier = Modifier.testTag(MomentTags.CONFIRM_DELETE))
}

private const val VOICE_PAGES = 2
private const val PLAYER_PAGE = 0
private val PROGRESS_INSET = 0.dp
private val PROGRESS_STROKE = 4.dp
private val PLAYER_SIDE_PADDING = 12.dp
private val DAY_AND_TIME_INSET = 8.dp
private const val DAY_AND_TIME_SWEEP = 110f
private const val CAPTION_WIDTH_FRACTION = 0.78f
private const val FAILURE_WIDTH_FRACTION = 0.92f
private const val CAPTION_LINES = 2
private const val FAILURE_LINES = 3
private val CONTROL_SPACING = 6.dp
private val PLACE_ICON_SIZE = 16.dp
private val MESSAGE_PADDING = 24.dp
