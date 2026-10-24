package app.logdate.wear.presentation.mood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.logdate.wear.R
import app.logdate.wear.capture.Feeling
import app.logdate.wear.haptic.WearHapticEngine
import app.logdate.wear.presentation.common.SaveFeedback
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MoodCheckInScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVoiceNote: () -> Unit,
    viewModel: MoodCheckInViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val haptics = koinInject<WearHapticEngine>()

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                MoodCheckInEvent.NavigateBack -> onNavigateBack()
                MoodCheckInEvent.NavigateToVoiceNote -> onNavigateToVoiceNote()
            }
        }
    }

    LaunchedEffect(uiState.step) {
        if (uiState.step == MoodCheckInStep.SAVED) {
            haptics.success()
        }
    }

    when (uiState.step) {
        MoodCheckInStep.SELECT_MOOD -> {
            SelectMoodContent(
                onMoodSelected = { mood -> viewModel.selectMood(mood) },
            )
        }

        MoodCheckInStep.VOICE_PROMPT -> {
            VoicePromptContent(
                selectedMood = uiState.selectedMood,
                isSaving = uiState.isSaving,
                saveFailed = uiState.saveFailed,
                onAttachVoice = { viewModel.attachVoice() },
                onSkip = { viewModel.skipVoiceAttachment() },
            )
        }

        MoodCheckInStep.SAVED -> {
            MoodSavedContent(
                saveFeedback = uiState.saveFeedback,
            )
        }
    }
}

@Composable
internal fun SelectMoodContent(onMoodSelected: (Feeling) -> Unit) {
    ScreenScaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = stringResource(R.string.wear_mood_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(Feeling.entries) { feeling ->
                FilledTonalButton(
                    onClick = { onMoodSelected(feeling) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "${feeling.emoji} ${stringResource(feeling.labelRes)}")
                }
            }
        }
    }
}

@Composable
internal fun VoicePromptContent(
    selectedMood: Feeling?,
    onAttachVoice: () -> Unit,
    onSkip: () -> Unit,
    isSaving: Boolean = false,
    saveFailed: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (selectedMood != null) {
            Text(
                text = selectedMood.emoji,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(selectedMood.labelRes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }
        Button(
            onClick = onAttachVoice,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.wear_mood_add_voice))
        }
        OutlinedButton(
            onClick = onSkip,
            enabled = !isSaving,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
        ) {
            Text(text = stringResource(R.string.wear_mood_skip))
        }
        if (saveFailed) {
            Text(
                text = stringResource(R.string.wear_mood_save_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
internal fun MoodSavedContent(saveFeedback: SaveFeedback? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(R.string.wear_mood_saved),
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(48.dp),
        )
        val feedbackText =
            when (saveFeedback) {
                SaveFeedback.SYNCING_TO_PHONE -> stringResource(R.string.wear_saved_syncing_to_phone)
                SaveFeedback.SAVED_LOCALLY -> stringResource(R.string.wear_saved_on_watch)
                null -> stringResource(R.string.wear_mood_saved)
            }
        Text(
            text = feedbackText,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
