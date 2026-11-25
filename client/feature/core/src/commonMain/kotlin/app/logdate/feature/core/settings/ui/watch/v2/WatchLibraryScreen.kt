@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_v2_library
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Dedicated subscreen for watch timeline history, Rewind, capture behavior, and audio download policy. */
@Composable
fun WatchLibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchCompanionPreferencesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(Res.string.watch_v2_library),
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            WatchCompanionSections(
                state = state,
                saving = saving,
                saveFailed = saveFailed,
                onDays = { viewModel.set(SharedPreferenceKey.WindowDays, it) },
                onThisDay = { viewModel.set(SharedPreferenceKey.WindowOnThisDay, it) },
                onRewind = { viewModel.set(SharedPreferenceKey.WindowRewind, it) },
                onPlaces = { viewModel.set(SharedPreferenceKey.CapturePlaces, it) },
                onMood = { viewModel.set(SharedPreferenceKey.MoodAfterRecording, it) },
                onAudioDownloads = { viewModel.set(SharedPreferenceKey.AudioDownloadPolicy, it.stored) },
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
    }
}
