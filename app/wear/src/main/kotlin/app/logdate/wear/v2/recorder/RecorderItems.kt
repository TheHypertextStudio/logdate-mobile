package app.logdate.wear.v2.recorder

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.logdate.ui.glyphs.LogDateGlyph
import app.logdate.ui.glyphs.vector
import app.logdate.wear.R
import app.logdate.wear.capture.Feeling
import app.logdate.wear.v2.moments.formatClockDuration
import app.logdate.wear.v2.permissions.openAppSettings
import app.logdate.wear.v2.permissions.openStorageSettings
import kotlin.time.Duration

object RecorderTags {
    const val ELAPSED = "recorder_elapsed"
    const val MESSAGE = "recorder_message"
    const val LOCK_HINT = "recorder_lock_hint"
    const val PAUSE = "recorder_pause"
    const val STOP = "recorder_stop"
    const val DISCARD = "recorder_discard"
    const val UNDO = "recorder_undo"
    const val FEELINGS = "recorder_feelings"
    const val FAILURE_ACTION = "recorder_failure_action"
    const val RING = "recorder_ring"
}

/** Whether Today shows the recorder in place of the day's summary. */
internal val RecorderState.replacesSummary: Boolean
    get() = this != RecorderState.Ready && this != RecorderState.Arming

/** Today's list while the recorder has something to show. */
internal fun TransformingLazyColumnScope.recorderItems(recorder: TodayRecorder) {
    when (val state = recorder.state) {
        is RecorderState.Live -> liveItems(state, recorder.actions)
        RecorderState.Saving -> item { RecorderMessage(stringResource(R.string.wear_today_recorder_saving)) }
        is RecorderState.Saved -> savedItems(state, recorder.actions)
        RecorderState.TooShort -> item { RecorderMessage(stringResource(R.string.wear_today_recorder_too_short), live = true) }
        is RecorderState.Failed -> failedItems(state.failure, recorder.actions)
        RecorderState.Ready, RecorderState.Arming -> Unit
    }
}

private fun TransformingLazyColumnScope.liveItems(
    live: RecorderState.Live,
    actions: RecorderActions,
) {
    item { ElapsedTime(live.elapsed) }
    item { LiveStatus(live) }
    if (live.mode == LiveMode.LOCKED) item { LockedControls(live, actions) }
}

private fun TransformingLazyColumnScope.savedItems(
    saved: RecorderState.Saved,
    actions: RecorderActions,
) {
    item {
        RecorderMessage(
            text = stringResource(R.string.wear_today_recorder_saved, formatClockDuration(saved.duration)),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            live = true,
        )
    }
    item {
        Button(onClick = actions::undo, modifier = Modifier.fillMaxWidth().testTag(RecorderTags.UNDO)) {
            Text(text = stringResource(R.string.wear_today_recorder_undo), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
    if (saved.feelingsOffered) item { FeelingRow(actions) }
    saved.feeling?.let { feeling ->
        item {
            val name = stringResource(feeling.labelRes)
            RecorderMessage(stringResource(R.string.wear_today_recorder_feeling_chosen, feeling.emoji, name))
        }
    }
}

private fun TransformingLazyColumnScope.failedItems(
    failure: RecorderFailure,
    actions: RecorderActions,
) {
    item {
        RecorderMessage(
            text = stringResource(failure.messageRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            live = true,
            inset = FAILURE_MESSAGE_INSET,
        )
    }
    item {
        val context = LocalContext.current
        Button(
            onClick = { runFailureAction(context, failure, actions) },
            modifier = Modifier.fillMaxWidth().testTag(RecorderTags.FAILURE_ACTION),
        ) {
            Text(text = stringResource(failure.actionRes()), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

/** The one action that helps with each failure: ask again, open Settings or storage, save again, or clear it. */
private fun runFailureAction(
    context: Context,
    failure: RecorderFailure,
    actions: RecorderActions,
) {
    when (failure) {
        RecorderFailure.MICROPHONE_BLOCKED -> {
            openAppSettings(context)
            actions.dismiss()
        }

        RecorderFailure.NOT_ENOUGH_STORAGE -> {
            openStorageSettings(context)
            actions.dismiss()
        }

        RecorderFailure.MICROPHONE_DENIED, RecorderFailure.SAVE_FAILED -> {
            actions.retry()
        }

        RecorderFailure.RECORDER_UNAVAILABLE, RecorderFailure.RECORDING_LOST -> {
            actions.dismiss()
        }
    }
}

private fun RecorderFailure.messageRes(): Int =
    when (this) {
        RecorderFailure.MICROPHONE_DENIED -> R.string.wear_today_recorder_failed_microphone
        RecorderFailure.MICROPHONE_BLOCKED -> R.string.wear_today_recorder_failed_microphone_off
        RecorderFailure.NOT_ENOUGH_STORAGE -> R.string.wear_today_recorder_failed_storage
        RecorderFailure.RECORDER_UNAVAILABLE -> R.string.wear_today_recorder_failed_start
        RecorderFailure.SAVE_FAILED -> R.string.wear_today_recorder_failed_save
        RecorderFailure.RECORDING_LOST -> R.string.wear_today_recorder_failed_lost
    }

private fun RecorderFailure.actionRes(): Int =
    when (this) {
        RecorderFailure.MICROPHONE_DENIED -> R.string.wear_today_recorder_allow
        RecorderFailure.MICROPHONE_BLOCKED -> R.string.wear_today_recorder_open_settings
        RecorderFailure.NOT_ENOUGH_STORAGE -> R.string.wear_today_recorder_open_storage
        RecorderFailure.SAVE_FAILED -> R.string.wear_today_recorder_try_again
        RecorderFailure.RECORDER_UNAVAILABLE, RecorderFailure.RECORDING_LOST -> R.string.wear_today_recorder_ok
    }

@Composable
private fun ElapsedTime(elapsed: Duration) {
    Text(
        text = formatClockDuration(elapsed),
        modifier = Modifier.fillMaxWidth().testTag(RecorderTags.ELAPSED),
        style = MaterialTheme.typography.numeralSmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun LiveStatus(live: RecorderState.Live) {
    when {
        live.confirmingDiscard -> {
            RecorderMessage(
                text = stringResource(R.string.wear_today_recorder_discard_confirm),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                live = true,
            )
        }

        live.mode == LiveMode.HOLDING -> {
            LockHint(live.lockProgress)
        }

        live.pausedByInterruption -> {
            RecorderMessage(stringResource(R.string.wear_today_recorder_paused_by_app), style = MaterialTheme.typography.bodySmall)
        }

        live.paused -> {
            RecorderMessage(stringResource(R.string.wear_today_recorder_paused))
        }

        else -> {
            RecorderMessage(stringResource(R.string.wear_today_recorder_recording))
        }
    }
}

/** While holding: release to save, and a lock that fills in as the finger slides toward it. */
@Composable
private fun LockHint(progress: Float) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(RecorderTags.LOCK_HINT),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = LogDateGlyph.LOCK.vector(),
            contentDescription = null,
            modifier = Modifier.size(LOCK_GLYPH_SIZE).graphicsLayer { alpha = LOCK_MIN_ALPHA + (1f - LOCK_MIN_ALPHA) * progress },
        )
        Text(
            text = stringResource(R.string.wear_today_recorder_release_to_save),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.wear_today_recorder_slide_to_lock),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Pause, Stop and Discard in one row, once the recording is locked. Stop sits between the other two
 * and is the filled one, and a Discard waiting for its second tap turns red so the two never look alike.
 */
@Composable
private fun LockedControls(
    live: RecorderState.Live,
    actions: RecorderActions,
) {
    val pauseLabel = stringResource(if (live.paused) R.string.wear_today_recorder_resume else R.string.wear_today_recorder_pause)
    val pauseIcon = if (live.paused) R.drawable.ic_recorder_resume else R.drawable.ic_recorder_pause
    val stopLabel = stringResource(R.string.wear_today_recorder_stop)
    val discardLabel =
        stringResource(if (live.confirmingDiscard) R.string.wear_today_recorder_discard_confirm else R.string.wear_today_recorder_discard)
    ButtonGroup(modifier = Modifier.fillMaxWidth()) {
        FilledTonalIconButton(onClick = actions::togglePause, modifier = Modifier.weight(1f).testTag(RecorderTags.PAUSE)) {
            Icon(painter = painterResource(pauseIcon), contentDescription = pauseLabel)
        }
        FilledIconButton(onClick = actions::stop, modifier = Modifier.weight(1f).testTag(RecorderTags.STOP)) {
            Icon(painter = painterResource(R.drawable.ic_recorder_stop), contentDescription = stopLabel)
        }
        if (live.confirmingDiscard) {
            FilledIconButton(
                onClick = actions::discard,
                modifier = Modifier.weight(1f).testTag(RecorderTags.DISCARD),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Icon(painter = painterResource(R.drawable.ic_recorder_discard), contentDescription = discardLabel)
            }
        } else {
            FilledTonalIconButton(onClick = actions::discard, modifier = Modifier.weight(1f).testTag(RecorderTags.DISCARD)) {
                Icon(painter = painterResource(R.drawable.ic_recorder_discard), contentDescription = discardLabel)
            }
        }
    }
}

/** The five feelings, one tap each, for four seconds after a save. */
@Composable
private fun FeelingRow(actions: RecorderActions) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(RecorderTags.FEELINGS),
        horizontalArrangement = Arrangement.spacedBy(FEELING_SPACING, Alignment.CenterHorizontally),
    ) {
        Feeling.entries.forEach { feeling ->
            val label = stringResource(feeling.labelRes)
            IconButton(
                onClick = { actions.chooseFeeling(feeling) },
                modifier = Modifier.size(FEELING_BUTTON_SIZE).semantics { contentDescription = label },
            ) {
                Text(text = feeling.emoji)
            }
        }
    }
}

@Composable
private fun RecorderMessage(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    live: Boolean = false,
    inset: Dp = 0.dp,
) {
    Text(
        text = text,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = inset).testTag(RecorderTags.MESSAGE).semantics {
                if (live) liveRegion = LiveRegionMode.Polite
            },
        color = color,
        style = style,
        textAlign = TextAlign.Center,
    )
}

private val LOCK_GLYPH_SIZE = 20.dp
private const val LOCK_MIN_ALPHA = 0.4f
private val FEELING_BUTTON_SIZE = 32.dp
private val FEELING_SPACING = 2.dp
private val FAILURE_MESSAGE_INSET = 4.dp
