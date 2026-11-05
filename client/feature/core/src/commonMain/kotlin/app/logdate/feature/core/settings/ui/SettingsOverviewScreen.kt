@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.feature.core.settings.ui.watch.WatchDeviceCard
import app.logdate.feature.core.settings.ui.watch.WatchSettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.screen_title_settings
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

/**
 * Main settings overview screen that displays navigation options to different settings sections.
 * This is the entry point for the settings flow and serves as the list pane in list-detail layouts.
 *
 * Groups: Personal / Privacy & Security / Data & Storage
 */
@Composable
fun SettingsOverviewScreen(
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToWatch: (() -> Unit)? = null,
    onNavigateToReset: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToLibrarySettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onNavigateToVoiceNotes: () -> Unit = {},
    onNavigateToNotifications: (() -> Unit)? = null,
    onNavigateToStreaks: () -> Unit = {},
    onNavigateToRewindSettings: () -> Unit = {},
    onNavigateToEventsSettings: () -> Unit = {},
    onNavigateToPeopleSettings: () -> Unit = {},
    onNavigateToTimeline: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsOverviewViewModel = koinViewModel(),
    campfireViewModel: CampfireViewModel = koinViewModel(),
    placesBetaViewModel: PlacesBetaSettingsViewModel = koinViewModel(),
) {
    val placesBetaState by placesBetaViewModel.state.collectAsStateWithLifecycle()
    val flags = koinInject<FeatureFlagStore>()
    val watchRedesign by remember(flags) { flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }.collectAsStateWithLifecycle(false)
    val deviceCards: (@Composable () -> Unit)? =
        if (watchRedesign && onNavigateToWatch != null) {
            {
                val watchViewModel = koinViewModel<WatchSettingsViewModel>()
                val watchStatus by watchViewModel.status.collectAsStateWithLifecycle()
                WatchDeviceCard(watchStatus, onClick = onNavigateToWatch, modifier = Modifier.padding(Spacing.lg))
            }
        } else {
            null
        }
    val identity by viewModel.resolvedIdentity.collectAsStateWithLifecycle()
    val streakData by viewModel.streakData.collectAsStateWithLifecycle()
    val campfire by campfireViewModel.presentation.collectAsStateWithLifecycle()
    val isCampfireEnabled by campfireViewModel.isCampfireEnabled.collectAsStateWithLifecycle()

    SettingsOverviewContent(
        onBack = onBack,
        onNavigateToProfile = onNavigateToProfile,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToDevices = onNavigateToDevices,
        onNavigateToWatch = onNavigateToWatch,
        onNavigateToReset = onNavigateToReset,
        onNavigateToLocation = onNavigateToLocation,
        onNavigateToPrivacy = onNavigateToPrivacy,
        onNavigateToLibrarySettings = onNavigateToLibrarySettings,
        onNavigateToMemories = onNavigateToMemories,
        onNavigateToVoiceNotes = onNavigateToVoiceNotes,
        onNavigateToNotifications = onNavigateToNotifications,
        onNavigateToStreaks = onNavigateToStreaks,
        onNavigateToRewindSettings = onNavigateToRewindSettings,
        onNavigateToEventsSettings = onNavigateToEventsSettings,
        onNavigateToPeopleSettings = onNavigateToPeopleSettings,
        onNavigateToTimeline = onNavigateToTimeline,
        onNavigateToSync = onNavigateToSync,
        onNavigateToExport = onNavigateToExport,
        onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
        onNavigateToSignIn = onNavigateToSignIn,
        onNavigateToAbout = onNavigateToAbout,
        userProfile =
            UserProfile(
                name = identity.displayName,
                username = identity.username ?: "",
                isAuthenticated = identity.isAuthenticated,
            ),
        onboardedDate = identity.onboardedDate ?: Instant.DISTANT_PAST,
        // The old streak badge only shows once the campfire flag is known to be off.
        streakCount = if (isCampfireEnabled == false && streakData.isEnabled) streakData.currentStreak else null,
        campfire = campfire,
        modifier = modifier,
        deviceCards = deviceCards,
        placesBetaState = placesBetaState,
        onSetPlacesBetaEnabled = placesBetaViewModel::setEnabled,
    )
}

@Composable
fun SettingsOverviewContent(
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToWatch: (() -> Unit)? = null,
    onNavigateToReset: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToMemories: () -> Unit,
    onNavigateToVoiceNotes: () -> Unit = {},
    onNavigateToNotifications: (() -> Unit)? = null,
    onNavigateToStreaks: () -> Unit = {},
    onNavigateToRewindSettings: () -> Unit = {},
    onNavigateToEventsSettings: () -> Unit = {},
    onNavigateToPeopleSettings: () -> Unit = {},
    onNavigateToTimeline: () -> Unit = {},
    onNavigateToSync: () -> Unit,
    onNavigateToExport: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToLibrarySettings: () -> Unit = {},
    userProfile: UserProfile,
    onboardedDate: Instant = Instant.DISTANT_PAST,
    streakCount: Int? = null,
    campfire: CampfirePresentation? = null,
    modifier: Modifier = Modifier,
    deviceCards: (@Composable () -> Unit)? = null,
    placesBetaState: PlacesBetaSettingsState = PlacesBetaSettingsState.Loading,
    onSetPlacesBetaEnabled: (Boolean) -> Unit = {},
) {
    FoldableBookLayout(
        modifier = modifier,
        minPaneWidth = 320.dp,
        startPane = {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsIdentityCard(
                    userProfile = userProfile,
                    onboardedDate = onboardedDate,
                    streakCount = streakCount,
                    campfire = campfire,
                    onEditProfile = onNavigateToProfile,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )

                deviceCards?.invoke()

                SettingsPersonalSection(
                    onNavigateToProfile = onNavigateToProfile,
                    onNavigateToMemories = onNavigateToMemories,
                    onNavigateToVoiceNotes = onNavigateToVoiceNotes,
                    onNavigateToTimeline = onNavigateToTimeline,
                    onNavigateToStreaks = onNavigateToStreaks,
                    onNavigateToRewindSettings = onNavigateToRewindSettings,
                    onNavigateToEventsSettings = onNavigateToEventsSettings,
                    onNavigateToPeopleSettings = onNavigateToPeopleSettings,
                    onNavigateToLibrarySettings = onNavigateToLibrarySettings,
                    placesBetaSettings = { PlacesBetaSettingsItem(placesBetaState, onSetPlacesBetaEnabled) },
                )
            }
        },
        endPane = {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SettingsPrivacySection(
                    userProfile = userProfile,
                    onNavigateToPrivacy = onNavigateToPrivacy,
                    onNavigateToAccount = onNavigateToAccount,
                    onNavigateToDevices = onNavigateToDevices,
                    onNavigateToLocation = onNavigateToLocation,
                    onNavigateToWatch = onNavigateToWatch.takeIf { deviceCards == null },
                    onNavigateToNotifications = onNavigateToNotifications,
                )

                SettingsDataSection(
                    userProfile = userProfile,
                    onNavigateToSync = onNavigateToSync,
                    onNavigateToExport = onNavigateToExport,
                    onNavigateToReset = onNavigateToReset,
                    onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
                    onNavigateToSignIn = onNavigateToSignIn,
                )
                SettingsAboutItem(onNavigateToAbout)
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.screen_title_settings),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    SettingsIdentityCard(
                        userProfile = userProfile,
                        onboardedDate = onboardedDate,
                        streakCount = streakCount,
                        campfire = campfire,
                        onEditProfile = onNavigateToProfile,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }

                deviceCards?.let { cards -> item { cards() } }

                // Personal group
                item {
                    SettingsPersonalSection(
                        onNavigateToProfile = onNavigateToProfile,
                        onNavigateToMemories = onNavigateToMemories,
                        onNavigateToVoiceNotes = onNavigateToVoiceNotes,
                        onNavigateToTimeline = onNavigateToTimeline,
                        onNavigateToStreaks = onNavigateToStreaks,
                        onNavigateToRewindSettings = onNavigateToRewindSettings,
                        onNavigateToEventsSettings = onNavigateToEventsSettings,
                        onNavigateToPeopleSettings = onNavigateToPeopleSettings,
                        onNavigateToLibrarySettings = onNavigateToLibrarySettings,
                        placesBetaSettings = { PlacesBetaSettingsItem(placesBetaState, onSetPlacesBetaEnabled) },
                    )
                }

                // Privacy & Security group
                item {
                    SettingsPrivacySection(
                        userProfile = userProfile,
                        onNavigateToPrivacy = onNavigateToPrivacy,
                        onNavigateToAccount = onNavigateToAccount,
                        onNavigateToDevices = onNavigateToDevices,
                        onNavigateToLocation = onNavigateToLocation,
                        onNavigateToWatch = onNavigateToWatch.takeIf { deviceCards == null },
                        onNavigateToNotifications = onNavigateToNotifications,
                    )
                }

                // Data & Storage group
                item {
                    SettingsDataSection(
                        userProfile = userProfile,
                        onNavigateToSync = onNavigateToSync,
                        onNavigateToExport = onNavigateToExport,
                        onNavigateToReset = onNavigateToReset,
                        onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
                        onNavigateToSignIn = onNavigateToSignIn,
                    )
                }
                item { SettingsAboutItem(onNavigateToAbout) }
            }
        },
    )
}

@Preview
@Composable
private fun SettingsOverviewScreenPreview() {
    SettingsOverviewContent(
        onBack = {},
        onNavigateToProfile = {},
        onNavigateToAccount = {},
        onNavigateToDevices = {},
        onNavigateToReset = {},
        onNavigateToLocation = {},
        onNavigateToPrivacy = {},
        onNavigateToMemories = {},
        onNavigateToSync = {},
        onNavigateToExport = {},
        userProfile =
            UserProfile(
                name = "John Doe",
                username = "johndoe",
                isAuthenticated = true,
            ),
    )
}

@Preview
@Composable
private fun SettingsOverviewScreenPreviewNotSignedIn() {
    SettingsOverviewContent(
        onBack = {},
        onNavigateToProfile = {},
        onNavigateToAccount = {},
        onNavigateToDevices = {},
        onNavigateToReset = {},
        onNavigateToLocation = {},
        onNavigateToPrivacy = {},
        onNavigateToMemories = {},
        onNavigateToSync = {},
        onNavigateToExport = {},
        userProfile =
            UserProfile(
                name = "",
                username = "",
                isAuthenticated = false,
            ),
    )
}
