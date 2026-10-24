@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.ui.adaptive.HingeAwareOverlay
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.app_update_prompt_available
import logdate.client.feature.core.generated.resources.app_update_prompt_downloaded
import logdate.client.feature.core.generated.resources.app_update_prompt_restart
import logdate.client.feature.core.generated.resources.app_update_prompt_update
import org.jetbrains.compose.resources.stringResource

/**
 * Surfaces the in-app update banner above the navigation graph when Play has reported an
 * actionable state. Renders nothing when the controller is idle / checking / unsupported.
 *
 * The banner caps at 560dp so it doesn't span the entire content pane on tablets and
 * desktop windows.
 *
 * @param uiState current value of [AppUpdateController.uiState]
 * @param onLaunchUpdate invoked when the user taps "Update" on an [AppUpdateStatus.Available]
 *   state — typically calls back into [AppUpdateController.checkForUpdates] with a
 *   manual trigger so Play re-shows the flow
 * @param onCompleteUpdate invoked when the user taps "Restart" on an [AppUpdateStatus.Downloaded]
 *   state — typically calls back into [AppUpdateController.completeUpdate]
 */
@Composable
fun AppUpdatePrompt(
    uiState: AppUpdateUiState,
    onLaunchUpdate: () -> Unit,
    onCompleteUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message: String?
    val actionLabel: String?
    val onAction: (() -> Unit)?
    when (uiState.status) {
        AppUpdateStatus.Available -> {
            message = uiState.message?.asString() ?: stringResource(Res.string.app_update_prompt_available)
            actionLabel = stringResource(Res.string.app_update_prompt_update)
            onAction = onLaunchUpdate
        }

        AppUpdateStatus.Downloaded -> {
            message = uiState.message?.asString() ?: stringResource(Res.string.app_update_prompt_downloaded)
            actionLabel = stringResource(Res.string.app_update_prompt_restart)
            onAction = onCompleteUpdate
        }

        else -> {
            message = null
            actionLabel = null
            onAction = null
        }
    }
    if (message == null) return

    // Pin the banner to a single physical pane (aligned to the top of that pane) so a centered
    // banner cannot straddle a foldable hinge. Non-foldable windows keep the intrinsic top banner
    // (fillMaxSizeWhenFlat = false), preserving the caller's top-center alignment and insets.
    HingeAwareOverlay(
        modifier = modifier,
        alignment = Alignment.TopCenter,
        fillMaxSizeWhenFlat = false,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
