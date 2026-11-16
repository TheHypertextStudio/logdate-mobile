@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.about_logdate
import logdate.client.feature.core.generated.resources.about_logdate_description
import logdate.client.feature.core.generated.resources.account_settings_description
import logdate.client.feature.core.generated.resources.account_title
import logdate.client.feature.core.generated.resources.devices
import logdate.client.feature.core.generated.resources.devices_settings_description
import logdate.client.feature.core.generated.resources.events_settings
import logdate.client.feature.core.generated.resources.events_settings_description
import logdate.client.feature.core.generated.resources.export_and_import
import logdate.client.feature.core.generated.resources.export_and_import_description
import logdate.client.feature.core.generated.resources.library_settings
import logdate.client.feature.core.generated.resources.library_settings_description
import logdate.client.feature.core.generated.resources.location_settings
import logdate.client.feature.core.generated.resources.location_settings_description
import logdate.client.feature.core.generated.resources.memories
import logdate.client.feature.core.generated.resources.memories_description
import logdate.client.feature.core.generated.resources.notifications_settings
import logdate.client.feature.core.generated.resources.notifications_settings_description
import logdate.client.feature.core.generated.resources.people_overview_description
import logdate.client.feature.core.generated.resources.people_title
import logdate.client.feature.core.generated.resources.privacy_and_security
import logdate.client.feature.core.generated.resources.privacy_security_description
import logdate.client.feature.core.generated.resources.profile
import logdate.client.feature.core.generated.resources.profile_settings_description
import logdate.client.feature.core.generated.resources.reset
import logdate.client.feature.core.generated.resources.reset_description
import logdate.client.feature.core.generated.resources.rewind_settings_overview_description
import logdate.client.feature.core.generated.resources.rewind_settings_overview_title
import logdate.client.feature.core.generated.resources.settings_group_data_storage
import logdate.client.feature.core.generated.resources.settings_group_personal
import logdate.client.feature.core.generated.resources.settings_group_privacy_security
import logdate.client.feature.core.generated.resources.streaks
import logdate.client.feature.core.generated.resources.streaks_description
import logdate.client.feature.core.generated.resources.sync_and_backup
import logdate.client.feature.core.generated.resources.sync_and_backup_description
import logdate.client.feature.core.generated.resources.timeline_settings
import logdate.client.feature.core.generated.resources.timeline_settings_description
import logdate.client.feature.core.generated.resources.voice_notes_settings
import logdate.client.feature.core.generated.resources.voice_notes_settings_description
import logdate.client.feature.core.generated.resources.watch_settings
import logdate.client.feature.core.generated.resources.watch_settings_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsPersonalSection(
    onNavigateToProfile: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onNavigateToVoiceNotes: () -> Unit,
    onNavigateToTimeline: () -> Unit,
    onNavigateToStreaks: () -> Unit,
    onNavigateToRewindSettings: () -> Unit,
    onNavigateToEventsSettings: () -> Unit,
    onNavigateToPeopleSettings: () -> Unit,
    onNavigateToLibrarySettings: () -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_group_personal),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.profile),
            description = stringResource(Res.string.profile_settings_description),
            icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
            onClick = onNavigateToProfile,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.memories),
            description = stringResource(Res.string.memories_description),
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
            onClick = onNavigateToMemories,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.voice_notes_settings),
            description = stringResource(Res.string.voice_notes_settings_description),
            icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
            onClick = onNavigateToVoiceNotes,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.timeline_settings),
            description = stringResource(Res.string.timeline_settings_description),
            icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
            onClick = onNavigateToTimeline,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.streaks),
            description = stringResource(Res.string.streaks_description),
            icon = { Icon(Icons.Default.LocalFireDepartment, contentDescription = null) },
            onClick = onNavigateToStreaks,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.rewind_settings_overview_title),
            description = stringResource(Res.string.rewind_settings_overview_description),
            icon = { Icon(Icons.Default.Replay, contentDescription = null) },
            onClick = onNavigateToRewindSettings,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.events_settings),
            description = stringResource(Res.string.events_settings_description),
            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            onClick = onNavigateToEventsSettings,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.people_title),
            description = stringResource(Res.string.people_overview_description),
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            onClick = onNavigateToPeopleSettings,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.library_settings),
            description = stringResource(Res.string.library_settings_description),
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
            onClick = onNavigateToLibrarySettings,
        )
    }
}

@Composable
internal fun SettingsPrivacySection(
    userProfile: UserProfile,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToWatch: (() -> Unit)?,
    onNavigateToNotifications: (() -> Unit)?,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_group_privacy_security),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.privacy_and_security),
            description = stringResource(Res.string.privacy_security_description),
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            onClick = onNavigateToPrivacy,
        )
        if (userProfile.isAuthenticated) {
            SettingsNavigationItem(
                title = stringResource(Res.string.account_title),
                description = stringResource(Res.string.account_settings_description),
                icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                onClick = onNavigateToAccount,
            )
            SettingsNavigationItem(
                title = stringResource(Res.string.devices),
                description = stringResource(Res.string.devices_settings_description),
                icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                onClick = onNavigateToDevices,
            )
        }
        SettingsNavigationItem(
            title = stringResource(Res.string.location_settings),
            description = stringResource(Res.string.location_settings_description),
            icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
            onClick = onNavigateToLocation,
        )
        onNavigateToWatch?.let { navigateToWatch ->
            SettingsNavigationItem(
                title = stringResource(Res.string.watch_settings),
                description = stringResource(Res.string.watch_settings_description),
                icon = { Icon(Icons.Default.Watch, contentDescription = null) },
                onClick = navigateToWatch,
            )
        }
        onNavigateToNotifications?.let { navigateToNotifications ->
            SettingsNavigationItem(
                title = stringResource(Res.string.notifications_settings),
                description = stringResource(Res.string.notifications_settings_description),
                icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                onClick = navigateToNotifications,
            )
        }
    }
}

@Composable
internal fun SettingsDataSection(
    userProfile: UserProfile,
    onNavigateToSync: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToReset: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit,
    onNavigateToSignIn: () -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_group_data_storage),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.sync_and_backup),
            description = stringResource(Res.string.sync_and_backup_description),
            icon = { Icon(Icons.Default.Sync, contentDescription = null) },
            onClick = onNavigateToSync,
        )
        if (!userProfile.isAuthenticated) {
            SyncPromotionCard(
                onCreateAccount = onNavigateToCloudAccountCreation,
                onSignIn = onNavigateToSignIn,
                onNavigateToSync = onNavigateToSync,
            )
        }
        SettingsNavigationItem(
            title = stringResource(Res.string.export_and_import),
            description = stringResource(Res.string.export_and_import_description),
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
            onClick = onNavigateToExport,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.reset),
            description = stringResource(Res.string.reset_description),
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            onClick = onNavigateToReset,
        )
    }
}

@Composable
internal fun SettingsAboutItem(onNavigateToAbout: () -> Unit) {
    app.logdate.ui.common.MaterialContainer(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        SettingsNavigationItem(
            title = stringResource(Res.string.about_logdate),
            description = stringResource(Res.string.about_logdate_description),
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = onNavigateToAbout,
        )
    }
}
