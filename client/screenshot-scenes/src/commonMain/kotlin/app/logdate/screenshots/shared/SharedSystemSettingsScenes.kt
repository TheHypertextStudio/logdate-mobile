@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.client.domain.recommendation.AmbientPromptTime
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.client.domain.streak.StreakData
import app.logdate.client.sync.SyncStatus
import app.logdate.client.text.toUiText
import app.logdate.feature.core.settings.ui.AdvancedSettingsContent
import app.logdate.feature.core.settings.ui.BirthdaySettingsContent
import app.logdate.feature.core.settings.ui.DayBoundarySettingsContent
import app.logdate.feature.core.settings.ui.DeveloperLocationSection
import app.logdate.feature.core.settings.ui.DeveloperToolsContent
import app.logdate.feature.core.settings.ui.FeaturePreview
import app.logdate.feature.core.settings.ui.FeaturePreviewsSection
import app.logdate.feature.core.settings.ui.LibrarySettingsContent
import app.logdate.feature.core.settings.ui.LocalDiagnosticsState
import app.logdate.feature.core.settings.ui.LocationIntervalContent
import app.logdate.feature.core.settings.ui.LocationSettingsContent
import app.logdate.feature.core.settings.ui.LocationTrackingOptionsContent
import app.logdate.feature.core.settings.ui.RecommendationSettingsContent
import app.logdate.feature.core.settings.ui.StorageQuotaUi
import app.logdate.feature.core.settings.ui.StreakSettingsContent
import app.logdate.feature.core.settings.ui.SyncSettingsContent
import app.logdate.feature.core.settings.ui.TimelineSettingsContent
import app.logdate.feature.core.settings.ui.devices.DeviceInfoUiState
import app.logdate.feature.core.settings.ui.devices.DevicesScreenContent
import app.logdate.feature.core.settings.ui.devices.DeviceListAvailability
import app.logdate.feature.core.settings.ui.devices.DevicesUiState
import app.logdate.feature.core.settings.ui.devices.LinkedDeviceUiState
import app.logdate.feature.core.settings.ui.devices.LinkedDevicesUiState
import app.logdate.feature.core.settings.ui.labs.LabsFlag
import app.logdate.feature.core.settings.ui.labs.LabsSettingsContent
import app.logdate.feature.core.settings.ui.labs.LabsUiState
import app.logdate.feature.core.settings.ui.osIntegrationPreviewFlags
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import app.logdate.feature.events.ui.calendarsync.CalendarSyncOverviewUiState
import app.logdate.feature.events.ui.calendarsync.CalendarSyncSettingsContent
import app.logdate.feature.events.ui.calendarsync.PermissionState
import app.logdate.feature.events.ui.settings.EventsSettingsContent
import app.logdate.feature.events.ui.settings.EventsSettingsUiState
import app.logdate.ui.streak.CampfirePhase
import app.logdate.ui.streak.CampfireSize
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_macos
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import kotlin.uuid.Uuid

internal val systemSettingsScenes: List<SharedScreenshotSceneSpec> =
    listOf(
        sharedScene(SharedScreenshotSceneId.SyncSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            SyncSettingsContent(
                onBack = {},
                syncStatus =
                    SyncStatus(
                        isEnabled = true,
                        lastSyncTime = baseInstant,
                        pendingUploads = 2,
                        isSyncing = false,
                        hasErrors = true,
                    ),
                isAuthenticated = true,
                onSyncNow = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
                quotaUsage =
                    StorageQuotaUi(
                        totalBytes = 5_000_000_000,
                        usedBytes = 2_650_000_000,
                        usagePercentage = 0.53f,
                        categories = emptyList(),
                        formattedTotal = "5.0 GB",
                        formattedUsed = "2.65 GB",
                    ),
                isQuotaAvailable = true,
                snackbarHostState = remember { SnackbarHostState() },
            )
        },
        sharedScene(SharedScreenshotSceneId.LocationSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            LocationSettingsContent(
                settings = sampleLocationSettings(),
                onBack = {},
                onToggleBackgroundTracking = {},
                onSetCaptureMode = {},
                onShowLocationTimeline = {},
                onNavigateToTrackingOptions = {},
                onNavigateToInterval = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.LocationTrackingOptions, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            LocationTrackingOptionsContent(
                settings = sampleLocationSettings(),
                onBack = {},
                onToggleJournalTracking = {},
                onToggleTimelineTracking = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.LocationInterval, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            LocationIntervalContent(
                settings = sampleLocationSettings(),
                onBack = {},
                onUpdateTrackingInterval = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.DeveloperTools, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DeveloperToolsContent(
                onBack = {},
                state = LocalDiagnosticsState(eventCount = 3),
                onPreview = {},
                onExport = {},
                onClear = {},
                onSetVerboseEnabled = {},
                locationContent = {
                    DeveloperLocationSection(
                        settings = sampleLocationSettings(),
                        onToggleServerAssist = {},
                        onSetDefaultLocation = {},
                    )
                },
            )
        },
        sharedScene(SharedScreenshotSceneId.FeaturePreviews, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            DeveloperToolsContent(
                onBack = {},
                state = LocalDiagnosticsState(eventCount = 3),
                onPreview = {},
                onExport = {},
                onClear = {},
                onSetVerboseEnabled = {},
                featurePreviewsContent = {
                    FeaturePreviewsSection(
                        previews =
                            osIntegrationPreviewFlags.map { flag ->
                                FeaturePreview(
                                    flag,
                                    enabled =
                                        flag == FeatureFlag.QUICK_SETTINGS_TILES || flag == FeatureFlag.LIVE_UPDATES,
                                )
                            },
                        onToggle = { _, _ -> },
                    )
                },
            )
        },
        sharedScene(SharedScreenshotSceneId.DevicesSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DevicesScreenContent(
                onBackClick = {},
                uiState =
                    DevicesUiState(
                        availability = DeviceListAvailability.Available,
                        devices =
                            listOf(
                                DeviceInfoUiState(
                                    id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
                                    name = "Alex's Pixel 9 Pro".toUiText(),
                                    platformName = Res.string.devices_platform_android,
                                    lastActiveFormatted = "Today".toUiText(),
                                    appVersion = "1.0.0",
                                    isCurrentDevice = true,
                                ),
                                DeviceInfoUiState(
                                    id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
                                    name = "Alex's MacBook Pro".toUiText(),
                                    platformName = Res.string.devices_platform_macos,
                                    lastActiveFormatted = "Yesterday".toUiText(),
                                    appVersion = "1.0.0",
                                    isCurrentDevice = false,
                                ),
                            ),
                    ),
            )
        },
        sharedScene(SharedScreenshotSceneId.DevicesLinkedWatch, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DevicesScreenContent(
                onBackClick = {},
                uiState =
                    DevicesUiState(
                        availability = DeviceListAvailability.Available,
                        devices =
                            listOf(
                                DeviceInfoUiState(
                                    id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
                                    name = "Alex's Pixel 9 Pro".toUiText(),
                                    platformName = Res.string.devices_platform_android,
                                    lastActiveFormatted = "Today".toUiText(),
                                    appVersion = "1.0.0",
                                    isCurrentDevice = true,
                                ),
                            ),
                        linked =
                            LinkedDevicesUiState.Listed(
                                listOf(
                                    LinkedDeviceUiState(
                                        id = "watch-session",
                                        name = "Alex's Pixel Watch 3".toUiText(),
                                        platformName = Res.string.devices_platform_wear_os,
                                        linkedAtFormatted = "Oct 3, 2026, 9:41 AM".toUiText(),
                                        lastSeenFormatted = "Oct 16, 2026, 8:02 AM".toUiText(),
                                        pairedWatch = false,
                                        erasesData = true,
                                    ),
                                ),
                            ),
                    ),
            )
        },
        sharedScene(SharedScreenshotSceneId.StreakSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            StreakSettingsContent(
                streakData = StreakData(currentStreak = 12, isEnabled = true),
                onBack = {},
                onToggleStreakTracking = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.CampfireStates, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStatesGallery()
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakUnlit, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.phase == CampfirePhase.UNLIT })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakBurning, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.size == CampfireSize.CAMPFIRE && it.loggedToday })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakWaiting, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.isWaitingForToday })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakEmbers, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.phase == CampfirePhase.EMBERS })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakOut, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.phase == CampfirePhase.OUT })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakRekindled, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = campfireSamples.first { it.showsRekindledLabel })
        },
        sharedScene(SharedScreenshotSceneId.CampfireStreakTrackingOff, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CampfireStreakScene(campfire = null, isTrackingEnabled = false)
        },
        sharedScene(SharedScreenshotSceneId.TimelineSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            TimelineSettingsContent(
                onBack = {},
                onNavigateToDayBoundary = {},
                sleepBasedBoundariesEnabled = true,
                healthConnectStatus = HealthConnectStatus.CONNECTED,
            )
        },
        sharedScene(SharedScreenshotSceneId.DayBoundarySettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DayBoundarySettingsContent(
                sleepBasedPreferenceEnabled = true,
                fallbackStartHour = 4,
                gateState = HealthConnectGateState(kind = HealthConnectGateKind.READY),
                isRequestInFlight = false,
                onBack = {},
                onToggleSleepBased = {},
                onSetFallbackHour = {},
                onRequestPermissions = {},
                onSetUpHealthConnect = {},
                onOpenHealthConnectPermissions = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.LibrarySettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            LibrarySettingsContent(
                onBack = {},
                isLibraryEnabled = true,
                onSetLibraryEnabled = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.RecommendationSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            RecommendationSettingsContent(
                settings =
                    MemoriesSettings(
                        contextualRecommendationsEnabled = true,
                        ambientPromptsEnabled = true,
                        morningPromptTime = AmbientPromptTime(hour = 8, minute = 30),
                        eveningPromptTime = AmbientPromptTime(hour = 21, minute = 0),
                        aiRecallEnabled = true,
                    ),
                onBack = {},
                onToggleContextualRecommendations = {},
                onToggleAmbientPrompts = {},
                onToggleCaptureNudges = {},
                onToggleDraftRescue = {},
                onToggleMemoryRecallNotifications = {},
                onToggleMorningPrompt = {},
                onToggleEveningPrompt = {},
                onSetMorningPromptTime = {},
                onSetEveningPromptTime = {},
                onToggleSmartRecall = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.BirthdaySettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            BirthdaySettingsContent(
                currentBirthday = baseInstant,
                onBack = {},
                onSave = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.AdvancedSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            AdvancedSettingsContent(
                onBack = {},
                appUpdateUiState =
                    AppUpdateUiState(
                        currentVersionName = "1.0.0",
                        currentVersionCode = 100,
                        status = AppUpdateStatus.UpToDate,
                        lastCheckedAt = baseInstant,
                    ),
                onCheckForAppUpdates = {},
                onCompleteAppUpdate = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.LabsSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            LabsSettingsContent(
                uiState =
                    LabsUiState.Ready(
                        listOf(
                            LabsFlag(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true),
                            LabsFlag(FeatureFlag.ACTIVITY_AWARENESS, enabled = true),
                        ),
                    ),
                onBack = {},
                onSetEnabled = { _, _ -> },
                activityAllowed = false,
            )
        },
        sharedScene(SharedScreenshotSceneId.EventsSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            EventsSettingsContent(
                uiState =
                    EventsSettingsUiState(
                        isAutoEventsEnabled = true,
                        isSmartNamingEnabled = true,
                    ),
                onBack = {},
                onAutoEventsToggled = {},
                onSmartNamingToggled = {},
                onNavigateToCalendar = {},
                onNavigateToCalendarSync = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.CalendarSyncSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            CalendarSyncSettingsContent(
                uiState =
                    CalendarSyncOverviewUiState(
                        permissionState = PermissionState.Granted,
                        isSyncEnabled = true,
                        selectedCalendarCount = 3,
                        totalCalendarCount = 5,
                    ),
                onBack = {},
                onRequestPermission = {},
                onSyncEnabledToggled = {},
                onNavigateToCalendars = {},
                onNavigateToActivity = {},
            )
        },
    )
