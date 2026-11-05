@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.RestoreState
import app.logdate.feature.core.settings.account.AccountContent
import app.logdate.feature.core.settings.account.AccountDestinations
import app.logdate.feature.core.settings.account.AccountHeader
import app.logdate.feature.core.settings.account.AccountUiState
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.EmailRow
import app.logdate.feature.core.settings.account.ServerHealth
import app.logdate.feature.core.settings.account.ServerRow
import app.logdate.feature.core.settings.account.SignInSummary
import app.logdate.feature.core.settings.account.delete.DeleteAccountContent
import app.logdate.feature.core.settings.account.delete.DeleteAccountUiState
import app.logdate.feature.core.settings.account.hosting.HostingContent
import app.logdate.feature.core.settings.account.hosting.HostingUiState
import app.logdate.feature.core.settings.account.recovery.AccountRecoveryUiState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseContent
import app.logdate.feature.core.settings.account.recovery.PhraseRevealState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseUiState
import app.logdate.feature.core.settings.account.signin.LinkedProviderRow
import app.logdate.feature.core.settings.account.signin.PasskeyRow
import app.logdate.feature.core.settings.account.signin.SignInMethodsContent
import app.logdate.feature.core.settings.account.signin.SignInMethodsUiState
import app.logdate.feature.core.settings.ui.DataSettingsContent
import app.logdate.feature.core.settings.ui.DefaultCaptureModeState
import app.logdate.feature.core.settings.ui.IntegrityState
import app.logdate.feature.core.settings.ui.MemoriesSettingsContent
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallUiState
import app.logdate.feature.core.settings.ui.PrivacySettingsContent
import app.logdate.feature.core.settings.ui.SettingsOverviewContent
import app.logdate.feature.core.settings.ui.StorageCategory
import app.logdate.feature.core.settings.ui.StorageQuotaUi
import app.logdate.feature.core.settings.ui.UserProfile
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsContent
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsViewModel
import kotlinx.datetime.LocalDate
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_settings_quota_category_photos
import logdate.client.feature.core.generated.resources.data_settings_quota_category_written_entries
import kotlin.time.Instant

internal val personalSettingsScenes: List<SharedScreenshotSceneSpec> =
    listOf(
        sharedScene(SharedScreenshotSceneId.SettingsOverview, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            SettingsOverviewContent(
                onBack = {},
                onNavigateToProfile = {},
                onNavigateToAccount = {},
                onNavigateToDevices = {},
                onNavigateToWatch = {},
                onNavigateToReset = {},
                onNavigateToLocation = {},
                onNavigateToPrivacy = {},
                onNavigateToMemories = {},
                onNavigateToVoiceNotes = {},
                onNavigateToNotifications = {},
                onNavigateToStreaks = {},
                onNavigateToRewindSettings = {},
                onNavigateToEventsSettings = {},
                onNavigateToPeopleSettings = {},
                onNavigateToTimeline = {},
                onNavigateToSync = {},
                onNavigateToExport = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
                onNavigateToLibrarySettings = {},
                userProfile = UserProfile(name = "Alex Rivera", username = "alex", isAuthenticated = true),
                onboardedDate = Instant.DISTANT_PAST,
                streakCount = 12,
            )
        },
        sharedScene(SharedScreenshotSceneId.AccountSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            AccountContent(
                state =
                    AccountUiState.SignedIn(
                        header = AccountHeader(displayName = "Alex Rivera", username = "alex"),
                        signIn = SignInSummary.Known(passkeyCount = 2, linkedProviders = listOf(LinkedSignInProvider.Kind.GOOGLE)),
                        hasRecoveryPhrase = true,
                        email = EmailRow(address = "alex@example.com", isVerified = true, canVerify = false),
                        server = ServerRow(name = "LogDate Cloud", host = "cloud.logdate.app", isLogDateCloud = true),
                        isSigningOut = false,
                    ),
                destinations = AccountDestinations({}, {}, {}, {}, {}, {}, {}, {}),
                onOpenEmailVerification = {},
                onSignOut = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.PrivacySettings, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            PrivacySettingsContent(
                onBack = {},
                onSetBiometricsEnabled = {},
                onSetSystemSearchVisibilityEnabled = {},
                isBiometricsEnabled = true,
                isSystemSearchVisibilityEnabled = true,
                showSystemSearchVisibilityToggle = true,
                onNavigateToLocationSettings = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.RecoveryPhrase, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            RecoveryPhraseContent(
                state =
                    RecoveryPhraseUiState.Available(
                        PhraseRevealState.Revealed(
                            listOf(
                                "orbit",
                                "canvas",
                                "meadow",
                                "lantern",
                                "harbor",
                                "velvet",
                                "summit",
                                "pepper",
                                "gravel",
                                "whisper",
                                "copper",
                                "tundra",
                            ),
                        ),
                    ),
                onBack = {},
                onReveal = {},
                onHide = {},
                onEnterPhrase = {},
            )
        },
        sharedScene(
            SharedScreenshotSceneId.RecoveryPhraseConfirmed,
            ScreenshotSceneGroup.SETTINGS,
            standardMatrixVariants +
                listOf(
                    ScreenshotSceneVariant(ScreenshotViewportId.PHONE_RTL),
                    ScreenshotSceneVariant(ScreenshotViewportId.PHONE_LARGE_TEXT),
                ),
        ) {
            RecoveryPhraseContent(
                state = RecoveryPhraseUiState.Available(),
                onBack = {},
                onReveal = {},
                onHide = {},
                onEnterPhrase = {},
                accountRecovery = AccountRecoveryUiState.Ready,
            )
        },
        sharedScene(SharedScreenshotSceneId.RecoveryPhraseFinishSetup, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            RecoveryPhraseContent(
                state = RecoveryPhraseUiState.Available(),
                onBack = {},
                onReveal = {},
                onHide = {},
                onEnterPhrase = {},
                accountRecovery = AccountRecoveryUiState.NeedsSetup,
            )
        },
        sharedScene(SharedScreenshotSceneId.Hosting, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            HostingContent(
                state =
                    HostingUiState(
                        server =
                            ConnectedServerInfo(
                                origin = "https://cloud.logdate.app",
                                displayName = "LogDate Cloud",
                                isLogDateCloud = true,
                                publishesIdentityChanges = false,
                            ),
                        health = ServerHealth.Reachable("1.4.0"),
                    ),
                onBack = {},
                onCheckAgain = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.DeleteAccount, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DeleteAccountContent(
                state = DeleteAccountUiState(serverName = "LogDate Cloud"),
                onBack = {},
                onEraseThisDeviceChange = {},
                onDelete = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.SignInMethods, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            SignInMethodsContent(
                state =
                    SignInMethodsUiState.Loaded(
                        passkeys =
                            listOf(
                                PasskeyRow(
                                    credentialId = "passkey-ios",
                                    deviceName = "iPhone 16 Pro",
                                    addedOn = LocalDate(2026, 3, 12),
                                    lastUsedOn = LocalDate(2026, 9, 22),
                                    canRemove = true,
                                ),
                                PasskeyRow(
                                    credentialId = "passkey-mac",
                                    deviceName = "MacBook Pro",
                                    addedOn = LocalDate(2026, 5, 2),
                                    lastUsedOn = null,
                                    canRemove = true,
                                ),
                            ),
                        linkedProviders =
                            listOf(
                                LinkedProviderRow(
                                    kind = LinkedSignInProvider.Kind.GOOGLE,
                                    email = "alex@example.com",
                                    lastSignInOn = LocalDate(2026, 9, 1),
                                ),
                            ),
                        canAddPasskey = true,
                    ),
                onBack = {},
                onRetry = {},
                onAddPasskey = {},
                onRemovePasskey = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.DataSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            DataSettingsContent(
                onBack = {},
                quotaUsage =
                    StorageQuotaUi(
                        totalBytes = 5_000_000_000,
                        usedBytes = 2_650_000_000,
                        usagePercentage = 0.53f,
                        categories =
                            listOf(
                                StorageCategory(
                                    name = Res.string.data_settings_quota_category_photos,
                                    usedBytes = 1_400_000_000,
                                    usagePercentage = 0.53f,
                                    color = Color(0xFF4CAF50),
                                    formattedUsed = "1.4 GB",
                                ),
                                StorageCategory(
                                    name = Res.string.data_settings_quota_category_written_entries,
                                    usedBytes = 650_000_000,
                                    usagePercentage = 0.25f,
                                    color = Color(0xFF2196F3),
                                    formattedUsed = "650 MB",
                                ),
                            ),
                        formattedTotal = "5.0 GB",
                        formattedUsed = "2.65 GB",
                    ),
                isQuotaAvailable = true,
                exportState = ExportState.Idle,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                restoreState = RestoreState.Idle,
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                nativeBackupStatus = NativeBackupStatus.Available(),
                integrityState = IntegrityState(),
                onRunIntegrityCheck = {},
                onRepairIntegrity = {},
                snackbarHostState = remember { SnackbarHostState() },
                isAuthenticated = true,
                onSyncNow = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.MemoriesSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            MemoriesSettingsContent(
                captureModeState = DefaultCaptureModeState.Ready(EntryStartMode.TEXT),
                onCaptureModeSelected = {},
                onBack = {},
                onNavigateToRecommendations = {},
                contextualRecommendationsEnabled = true,
                onToggleContextualRecommendations = {},
                recallMode = RecallMode.ON_THIS_DAY,
                onSetRecallMode = {},
                widgetInstallUiState = MemoriesWidgetInstallUiState.Available,
                onAddWidgetToHomeScreen = {},
                widgetContentTypes = setOf(WidgetContentType.TEXT, WidgetContentType.PHOTOS),
                onToggleContentType = { _, _ -> },
            )
        },
        sharedScene(SharedScreenshotSceneId.VoiceNotesSettings, ScreenshotSceneGroup.SETTINGS, standardMatrixVariants) {
            VoiceNotesSettingsContent(
                state =
                    VoiceNotesSettingsViewModel.UiState(
                        transcription =
                            VoiceNotesSettingsViewModel.ModelRowState(
                                status = ModelDownloadStatus.Completed,
                            ),
                        tagging =
                            VoiceNotesSettingsViewModel.ModelRowState(
                                status =
                                    ModelDownloadStatus.Downloading(
                                        bytesDownloaded = 64_000_000,
                                        totalBytes = 100_000_000,
                                    ),
                            ),
                    ),
                onDownloadTranscription = {},
                onDownloadTagging = {},
                onBack = {},
            )
        },
    )
