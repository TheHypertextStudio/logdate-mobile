@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavKey
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.identity.ResolvedUserIdentity
import app.logdate.feature.core.settings.navigation.SettingsCategory
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.about_logdate
import logdate.client.feature.core.generated.resources.account_title
import logdate.client.feature.core.generated.resources.devices
import logdate.client.feature.core.generated.resources.events_settings
import logdate.client.feature.core.generated.resources.export_and_import
import logdate.client.feature.core.generated.resources.library_settings
import logdate.client.feature.core.generated.resources.location_settings
import logdate.client.feature.core.generated.resources.memories
import logdate.client.feature.core.generated.resources.people_title
import logdate.client.feature.core.generated.resources.privacy_and_security
import logdate.client.feature.core.generated.resources.profile
import logdate.client.feature.core.generated.resources.reset
import logdate.client.feature.core.generated.resources.rewind_settings_overview_title
import logdate.client.feature.core.generated.resources.screen_title_settings
import logdate.client.feature.core.generated.resources.streaks
import logdate.client.feature.core.generated.resources.sync_and_backup
import logdate.client.feature.core.generated.resources.timeline_settings
import logdate.client.feature.core.generated.resources.voice_notes_settings
import logdate.client.feature.core.generated.resources.watch_settings
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
private fun rememberUserIdentity(): Flow<ResolvedUserIdentity> {
    val observeUserIdentity = koinInject<ObserveUserIdentityUseCase>()
    return remember(observeUserIdentity) { observeUserIdentity() }
}

@Composable
fun SettingsCategoryNavigationScreen(
    selectedSection: String,
    onNavigateTo: (NavKey) -> Unit,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToWatch: (() -> Unit)? = null,
    userIdentity: Flow<ResolvedUserIdentity> = rememberUserIdentity(),
    peopleSwitch: PeopleSwitch = koinInject(),
) {
    val identity by userIdentity.collectAsState(initial = null)
    val optionalRows = rememberOptionalSettingsRows(peopleSwitch)
    SettingsCategoryNavigation(
        selectedSection = selectedSection,
        onNavigateTo = onNavigateTo,
        onBack = onBack,
        onNavigateToProfile = onNavigateToProfile,
        onNavigateToWatch = onNavigateToWatch,
        isAuthenticated = identity?.isAuthenticated == true,
        showPeople = optionalRows.people,
    )
}

/**
 * The list of Settings categories shown beside a settings page on wide windows.
 *
 * The list scrolls to the selected category when that category changes or moves. A category that
 * appears or disappears elsewhere in the list leaves the scroll position alone.
 *
 * @param showPeople whether People is turned on for this installation; its category is listed only then
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryNavigation(
    selectedSection: String,
    onNavigateTo: (NavKey) -> Unit,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToWatch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isAuthenticated: Boolean = true,
    showPeople: Boolean = false,
) {
    val categories =
        SettingsCategory.entries.filter { category ->
            when (category) {
                SettingsCategory.Account, SettingsCategory.Devices -> isAuthenticated || category.id == selectedSection
                SettingsCategory.Events -> FeatureFlag.EVENTS.availableForLaunch
                SettingsCategory.People -> showPeople
                SettingsCategory.Watch -> onNavigateToWatch != null
                else -> true
            }
        }
    val listState = rememberLazyListState()
    val selectedIndex = categories.indexOfFirst { it.id == selectedSection }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
    }
    Column(modifier.fillMaxSize().testTag("settings_categories")) {
        TopAppBar(
            title = { Text(stringResource(Res.string.screen_title_settings)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(UiRes.string.common_back))
                }
            },
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(categories, key = { it.id }) { category ->
                NavigationDrawerItem(
                    label = { Text(settingsCategoryTitle(category)) },
                    selected = category.id == selectedSection,
                    onClick = { onNavigateTo(category.route) },
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(Res.string.profile)) },
                    selected = false,
                    onClick = onNavigateToProfile,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun settingsCategoryTitle(category: SettingsCategory): String =
    when (category) {
        SettingsCategory.Account -> stringResource(Res.string.account_title)
        SettingsCategory.Memories -> stringResource(Res.string.memories)
        SettingsCategory.VoiceNotes -> stringResource(Res.string.voice_notes_settings)
        SettingsCategory.Timeline -> stringResource(Res.string.timeline_settings)
        SettingsCategory.Streaks -> stringResource(Res.string.streaks)
        SettingsCategory.Rewind -> stringResource(Res.string.rewind_settings_overview_title)
        SettingsCategory.Events -> stringResource(Res.string.events_settings)
        SettingsCategory.People -> stringResource(Res.string.people_title)
        SettingsCategory.Library -> stringResource(Res.string.library_settings)
        SettingsCategory.Privacy -> stringResource(Res.string.privacy_and_security)
        SettingsCategory.Devices -> stringResource(Res.string.devices)
        SettingsCategory.Location -> stringResource(Res.string.location_settings)
        SettingsCategory.Watch -> stringResource(Res.string.watch_settings)
        SettingsCategory.Backup -> stringResource(Res.string.sync_and_backup)
        SettingsCategory.Export -> stringResource(Res.string.export_and_import)
        SettingsCategory.Reset -> stringResource(Res.string.reset)
        SettingsCategory.About -> stringResource(Res.string.about_logdate)
    }
