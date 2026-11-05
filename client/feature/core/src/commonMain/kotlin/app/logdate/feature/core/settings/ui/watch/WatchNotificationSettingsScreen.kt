@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.watch.WatchNotificationSettings
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_notification_entry_section
import logdate.client.feature.core.generated.resources.watch_notification_show
import logdate.client.feature.core.generated.resources.watch_notification_show_description
import logdate.client.feature.core.generated.resources.watch_notifications_title
import org.jetbrains.compose.resources.stringResource

/**
 * Detail screen for configuring notifications triggered by watch sync events.
 */
@Composable
fun WatchNotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: WatchSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()

    WatchNotificationSettingsContent(
        settings = notificationSettings,
        onBack = onBack,
        onSetShowEntryNotifications = viewModel::setShowEntryNotifications,
        modifier = modifier,
    )
}

@Composable
fun WatchNotificationSettingsContent(
    settings: WatchNotificationSettings,
    onBack: () -> Unit,
    onSetShowEntryNotifications: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = stringResource(Res.string.watch_notifications_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.watch_notification_entry_section),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            ) {
                ToggleSettingsItem(
                    title = stringResource(Res.string.watch_notification_show),
                    description = stringResource(Res.string.watch_notification_show_description),
                    checked = settings.showEntryNotifications,
                    onCheckedChange = onSetShowEntryNotifications,
                )
            }
        }
    }
}
