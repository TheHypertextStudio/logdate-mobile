@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.streak.CampfireViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings screen where users can enable or disable daily journaling streak tracking
 * and see their campfire.
 */
@Composable
fun StreakSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StreakSettingsViewModel = koinViewModel(),
    campfireViewModel: CampfireViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val campfire by campfireViewModel.presentation.collectAsStateWithLifecycle()

    CampfireStreakContent(
        campfire = campfire,
        isTrackingEnabled = uiState.streakData.isEnabled,
        onBack = onBack,
        onToggleStreakTracking = viewModel::toggleStreakTracking,
        modifier = modifier,
    )
}
