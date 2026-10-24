package app.logdate.wear.presentation.memories

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.wear.R
import app.logdate.wear.playback.AudioOutputState
import app.logdate.wear.presentation.recording.formatDuration
import app.logdate.wear.presentation.timeline.WearPlaybackUiState
import app.logdate.wear.presentation.timeline.formatDayLabel
import app.logdate.wear.v2.playon.OutputPicker
import app.logdate.wear.v2.playon.OutputPickerRoute
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun WearMemoryPlayerScreen(
    noteId: Uuid,
    viewModel: WearMemoryPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val outputPicker: OutputPicker = koinInject()
    LaunchedEffect(noteId) { viewModel.open(noteId) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }

    WearMemoryPlayerContent(
        state = state,
        onPlayPause = viewModel::onPlayPause,
        onSkipBack = viewModel::onSkipBack,
        onSkipForward = viewModel::onSkipForward,
        onChooseOutput = { outputPicker.open() },
        canChooseOutput = outputPicker.route != OutputPickerRoute.NONE,
    )
}

@Composable
internal fun WearMemoryPlayerContent(
    state: MemoryPlayerUiState,
    onPlayPause: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onChooseOutput: () -> Unit = {},
    canChooseOutput: Boolean = true,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    ScreenScaffold(timeText = { TimeText() }) {
        val active = state.playback as? WearPlaybackUiState.Active
        if (active != null) {
            CircularProgressIndicator(
                progress = { active.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize().padding(PROGRESS_RING_INSET),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val memory = state.memory
            when {
                !state.isLoaded -> Unit
                memory == null -> PlayerMessage(stringResource(R.string.wear_memory_not_found))
                else -> PlayerBody(state, memory, timeZone, onPlayPause, onSkipBack, onSkipForward, onChooseOutput, canChooseOutput)
            }
        }
    }
}

@Composable
private fun PlayerBody(
    state: MemoryPlayerUiState,
    memory: VoiceMemoryItem,
    timeZone: TimeZone,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onChooseOutput: () -> Unit,
    canChooseOutput: Boolean,
) {
    val playback = state.playback
    val active = playback as? WearPlaybackUiState.Active
    val blocked = playback is WearPlaybackUiState.BlockedOutput || state.output is AudioOutputState.Unavailable
    val controlsEnabled = active != null

    Box(modifier = Modifier.height(TITLE_SLOT_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = memoryTitle(memory, timeZone),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
    ButtonGroup(
        modifier =
            Modifier
                .fillMaxWidth(if (canChooseOutput) OUTPUT_CONTROLS_WIDTH_FRACTION else CONTROLS_WIDTH_FRACTION)
                .padding(vertical = 6.dp),
    ) {
        val backSource = remember { MutableInteractionSource() }
        val playSource = remember { MutableInteractionSource() }
        val forwardSource = remember { MutableInteractionSource() }
        PlayerSideButton(
            icon = Icons.Default.Replay10,
            description = R.string.wear_player_skip_back,
            enabled = controlsEnabled,
            onClick = onSkipBack,
            interactionSource = backSource,
            modifier = Modifier.weight(SIDE_WEIGHT).animateWidth(backSource),
        )
        PlayButton(
            playback = playback,
            blocked = blocked,
            onPlayPause = onPlayPause,
            interactionSource = playSource,
            modifier = Modifier.weight(PLAY_WEIGHT).animateWidth(playSource),
        )
        PlayerSideButton(
            icon = Icons.Default.Forward10,
            description = R.string.wear_player_skip_forward,
            enabled = controlsEnabled,
            onClick = onSkipForward,
            interactionSource = forwardSource,
            modifier = Modifier.weight(SIDE_WEIGHT).animateWidth(forwardSource),
        )
        if (canChooseOutput) {
            val outputSource = remember { MutableInteractionSource() }
            PlayerSideButton(
                icon = Icons.Default.VolumeUp,
                description = R.string.wear_audio_output,
                enabled = true,
                onClick = onChooseOutput,
                interactionSource = outputSource,
                modifier = Modifier.weight(SIDE_WEIGHT).animateWidth(outputSource),
            )
        }
    }
    Box(modifier = Modifier.height(STATUS_SLOT_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        PlayerStatus(playback = playback, blocked = blocked, memory = memory)
    }
}

@Composable
private fun PlayButton(
    playback: WearPlaybackUiState,
    blocked: Boolean,
    onPlayPause: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val active = playback as? WearPlaybackUiState.Active
    val (icon, description) =
        when {
            active != null && !active.isPaused -> Icons.Default.Pause to R.string.wear_player_pause
            else -> Icons.Default.PlayArrow to R.string.wear_player_play
        }
    IconButton(
        onClick = onPlayPause,
        enabled = !blocked && playback !is WearPlaybackUiState.Preparing,
        modifier = modifier.height(PLAY_BUTTON_SIZE),
        interactionSource = interactionSource,
        shapes = IconButtonDefaults.animatedShapes(),
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(description), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun PlayerSideButton(
    icon: ImageVector,
    description: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SIDE_BUTTON_SIZE),
        interactionSource = interactionSource,
        shapes = IconButtonDefaults.animatedShapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(description), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PlayerStatus(
    playback: WearPlaybackUiState,
    blocked: Boolean,
    memory: VoiceMemoryItem,
) {
    when {
        blocked -> {
            PlayerMessage(stringResource(R.string.wear_playback_choose_output))
        }

        playback is WearPlaybackUiState.Preparing -> {
            PlayerMessage(stringResource(R.string.wear_playback_preparing_audio))
        }

        playback is WearPlaybackUiState.Error -> {
            PlayerMessage(stringResource(R.string.wear_playback_retry_download))
        }

        playback is WearPlaybackUiState.Active -> {
            val position = (memory.durationMs * playback.progress).toLong()
            Text(
                text = stringResource(R.string.wear_player_position, formatDuration(position), formatDuration(memory.durationMs)),
                style = MaterialTheme.typography.numeralExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            PlayerMessage(formatDuration(memory.durationMs))
        }
    }
}

@Composable
private fun PlayerMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun memoryTitle(
    memory: VoiceMemoryItem,
    timeZone: TimeZone,
): String {
    val day = memory.createdAt.toLocalDateTime(timeZone).date
    return "${formatDayLabel(day)}, ${formatMemoryTime(memory, timeZone)}"
}

private val TITLE_SLOT_HEIGHT = 34.dp
private val STATUS_SLOT_HEIGHT = 34.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val SIDE_BUTTON_SIZE = 36.dp
private val PROGRESS_RING_INSET = 2.dp
private const val CONTROLS_WIDTH_FRACTION = 0.86f
private const val OUTPUT_CONTROLS_WIDTH_FRACTION = 0.96f
private const val SIDE_WEIGHT = 0.75f
private const val PLAY_WEIGHT = 1.5f
