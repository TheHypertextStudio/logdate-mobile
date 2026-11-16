package app.logdate.screenshots.audit.adaptive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.export.ExportCounts
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.restore.ArchivePreview
import app.logdate.client.text.toUiText
import app.logdate.client.ui.LockableContent
import app.logdate.feature.core.account.CloudAccountSignInContent
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.account.PasskeyAccountCreationFinalContent
import app.logdate.feature.core.export.ExportOptions
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.ImportOptions
import app.logdate.feature.core.restore.RestoreState
import app.logdate.feature.core.settings.ui.ClearDataSettingsContent
import app.logdate.feature.core.settings.ui.DataSettingsContent
import app.logdate.feature.core.settings.ui.IntegrityState
import app.logdate.feature.core.settings.ui.ResetAppSettingsContent
import app.logdate.feature.core.settings.ui.ResetSettingsScreen
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.feature.core.settings.ui.StorageQuotaUi
import app.logdate.feature.core.settings.updates.AppUpdatePrompt
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import app.logdate.feature.onboarding.ui.BackupSyncScreenContent
import app.logdate.feature.onboarding.ui.CloudAccountSetupContent
import app.logdate.feature.onboarding.ui.PersonalIntroContent
import app.logdate.feature.onboarding.ui.PersonalIntroStep
import app.logdate.feature.onboarding.ui.PersonalIntroUiState
import app.logdate.feature.onboarding.ui.RecoveryPhraseEntryScreen
import app.logdate.feature.onboarding.ui.RecoveryPhraseSetupScreen
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import com.android.tools.screenshot.PreviewTest

private val auditArchivePreview =
    ArchivePreview(
        version = ExportSchemaVersion.V1_2,
        exportDate = ScreenshotTestData.baseInstant,
        appVersion = "0.1.0",
        stats =
            ExportStats(
                journalCount = 18,
                noteCount = 126,
                draftCount = 7,
                mediaCount = 42,
                placeCount = 12,
                locationHistoryCount = 26,
                hasProfile = true,
            ),
    )

private val auditExportCounts =
    ExportCounts(
        journalCount = 18,
        noteCount = 126,
        draftCount = 7,
        mediaCount = 42,
    )

private val auditStorageQuota =
    StorageQuotaUi(
        totalBytes = 5_368_709_120L,
        usedBytes = 2_147_483_648L,
        usagePercentage = 0.4f,
        formattedTotal = "5.0 GB",
        formattedUsed = "2.0 GB",
    )

private val tabletopPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 0.dp,
                        top = 438.dp,
                        right = 1440.dp,
                        bottom = 462.dp,
                        width = 1440.dp,
                        height = 24.dp,
                    ),
                isSeparating = true,
            ),
    )

private val bookPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 708.dp,
                        top = 0.dp,
                        right = 732.dp,
                        bottom = 900.dp,
                        width = 24.dp,
                        height = 900.dp,
                    ),
                isSeparating = true,
            ),
    )

private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val TABLETOP_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val SAMPLE_IMAGE_URI =
    "android.resource://studio.hypertext.logdate.debug/drawable/sample_note_photo"
private const val SAMPLE_VIDEO_URI = "android.resource://studio.hypertext.logdate.debug/mipmap/ic_launcher"

@PreviewTest
@Preview(name = "Cloud account welcome book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A98_CloudAccountWelcomeBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountWelcomeContent(
                onContinue = {},
                onSignIn = {},
                onBack = {},
                serverSelectionState = ServerSelectionState(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account welcome tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A99_CloudAccountWelcomeTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountWelcomeContent(
                onContinue = {},
                onSignIn = {},
                onBack = {},
                serverSelectionState = ServerSelectionState(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account sign in book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A100_CloudAccountSignInBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountSignInContent(
                username = "alex_j",
                onUsernameChange = {},
                onSignIn = {},
                onAccountRecovery = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onBack = {},
                serverDisplayName = "LogDate Cloud",
                serverHandleDomain = "logdate.app",
                errorMessage = "Invalid username or password.",
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account sign in tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A101_CloudAccountSignInTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountSignInContent(
                username = "alex_j",
                onUsernameChange = {},
                onSignIn = {},
                onAccountRecovery = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onBack = {},
                serverDisplayName = "LogDate Cloud",
                serverHandleDomain = "logdate.app",
                onSignInWithGoogle = {},
                isSigningIn = true,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account creation book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A102_CloudAccountCreationBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            PasskeyAccountCreationFinalContent(
                displayName = "Alex Johnson",
                username = "alex_j",
                stepNumber = 2,
                stepCount = 2,
                onCreateAccount = {},
                onBack = {},
                isCreatingAccount = false,
                errorMessage = null,
                onClearError = {},
                isPasskeySupported = true,
                serverDisplayName = "LogDate Cloud",
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account creation tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A103_CloudAccountCreationTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            PasskeyAccountCreationFinalContent(
                displayName = "Alex Johnson",
                username = "alex_j",
                stepNumber = 2,
                stepCount = 2,
                onCreateAccount = {},
                onBack = {},
                isCreatingAccount = true,
                errorMessage = null,
                onClearError = {},
                isPasskeySupported = true,
                serverDisplayName = "LogDate Cloud",
            )
        }
    }
}

@PreviewTest
@Preview(name = "Backup sync book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A104_BackupSyncBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            BackupSyncScreenContent(
                useCompactLayout = false,
                onBack = {},
                onPlanSelected = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Backup sync tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A105_BackupSyncTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            BackupSyncScreenContent(
                useCompactLayout = false,
                onBack = {},
                onPlanSelected = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Recovery phrase setup book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A106_RecoveryPhraseSetupBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            RecoveryPhraseSetupScreen(
                words =
                    listOf(
                        "orbit",
                        "paper",
                        "candle",
                        "forest",
                        "river",
                        "silver",
                        "puzzle",
                        "anchor",
                        "signal",
                        "harbor",
                        "lantern",
                        "sunset",
                    ),
                isLoading = false,
                errorMessage = null,
                onRetry = {},
                onPhraseContinue = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Recovery phrase setup tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A107_RecoveryPhraseSetupTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            RecoveryPhraseSetupScreen(
                words =
                    listOf(
                        "orbit",
                        "paper",
                        "candle",
                        "forest",
                        "river",
                        "silver",
                        "puzzle",
                        "anchor",
                        "signal",
                        "harbor",
                        "lantern",
                        "sunset",
                    ),
                isLoading = false,
                errorMessage = null,
                onRetry = {},
                onPhraseContinue = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Recovery phrase entry book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A108_RecoveryPhraseEntryBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            RecoveryPhraseEntryScreen(
                onRecoverPhrase = { Result.success(Unit) },
                onRecovered = {},
                onError = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Recovery phrase entry tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A109_RecoveryPhraseEntryTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            RecoveryPhraseEntryScreen(
                onRecoverPhrase = { Result.success(Unit) },
                onRecovered = {},
                onError = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Data settings export book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A110_DataSettingsExportBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            val snackbarHostState = remember { SnackbarHostState() }
            DataSettingsContent(
                onBack = {},
                quotaUsage = auditStorageQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Configuring(options = ExportOptions(), counts = auditExportCounts),
                isExportSheetVisible = true,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                restoreState = RestoreState.Idle,
                isRestoreSheetVisible = false,
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                integrityState = IntegrityState(),
                onRunIntegrityCheck = {},
                onRepairIntegrity = {},
                snackbarHostState = snackbarHostState,
                syncStatus = null,
                isAuthenticated = false,
                onSyncNow = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Data settings export progress book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A111_DataSettingsExportProgressBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            val snackbarHostState = remember { SnackbarHostState() }
            DataSettingsContent(
                onBack = {},
                quotaUsage = auditStorageQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Exporting(progressPercent = 62, message = "Packaging entries and attachments".toUiText()),
                isExportSheetVisible = true,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                restoreState = RestoreState.Idle,
                isRestoreSheetVisible = false,
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                integrityState = IntegrityState(),
                onRunIntegrityCheck = {},
                onRepairIntegrity = {},
                snackbarHostState = snackbarHostState,
                syncStatus = null,
                isAuthenticated = false,
                onSyncNow = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Data settings restore preview book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A112_DataSettingsRestorePreviewBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            val snackbarHostState = remember { SnackbarHostState() }
            DataSettingsContent(
                onBack = {},
                quotaUsage = auditStorageQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Idle,
                isExportSheetVisible = false,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                restoreState =
                    RestoreState.Previewing(
                        preview = auditArchivePreview,
                        fileName = "logdate-backup.ldz",
                        options = ImportOptions(),
                    ),
                isRestoreSheetVisible = true,
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                integrityState = IntegrityState(),
                onRunIntegrityCheck = {},
                onRepairIntegrity = {},
                snackbarHostState = snackbarHostState,
                syncStatus = null,
                isAuthenticated = false,
                onSyncNow = {},
                onNavigateToCloudAccountCreation = {},
                onNavigateToSignIn = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Reset settings book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A113_ResetSettingsBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            ResetSettingsScreen(
                onBack = {},
                onNavigateToClearData = {},
                onNavigateToResetApp = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Clear data book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A114_ClearDataSettingsBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            ClearDataSettingsContent(
                onBack = {},
                onClearData = { _, _ -> },
            )
        }
    }
}

@PreviewTest
@Preview(name = "Reset app book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A115_ResetAppSettingsBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            ResetAppSettingsContent(
                onBack = {},
                onAppReset = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Lock screen book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A116_LockScreenBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            LockableContent(
                isLocked = true,
                displayName = "Alex Johnson",
                onUsePasscode = {},
            ) {}
        }
    }
}

@PreviewTest
@Preview(name = "App update prompt book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A117_AppUpdatePromptBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            AppUpdatePrompt(
                uiState =
                    AppUpdateUiState(
                        status = AppUpdateStatus.Available,
                        message = "LogDate 0.2.0 is ready to install.".toUiText(),
                    ),
                onLaunchUpdate = {},
                onCompleteUpdate = {},
            )
        }
    }
}

// A74/A75 cover the personal intro flow's Bio step in both postures, but the flow's actual
// text-entry step -- the one a keyboard can cover -- had no posture coverage at all.
@PreviewTest
@Preview(name = "Personal intro name step book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A118_PersonalIntroNameStepBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            PersonalIntroContent(
                uiState =
                    PersonalIntroUiState(
                        currentStep = PersonalIntroStep.Name,
                        name = "",
                    ),
                onNameChanged = {},
                onBioChanged = {},
                onProceedToBio = {},
                onGoBackToName = {},
                onProcessWithLlm = {},
                onBack = {},
                modifier = Modifier.fillMaxSize(),
                autoFocusInputs = false,
                animateStepTransitions = false,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Personal intro name step tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A119_PersonalIntroNameStepTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            PersonalIntroContent(
                uiState =
                    PersonalIntroUiState(
                        currentStep = PersonalIntroStep.Name,
                        name = "",
                    ),
                onNameChanged = {},
                onBioChanged = {},
                onProceedToBio = {},
                onGoBackToName = {},
                onProcessWithLlm = {},
                onBack = {},
                modifier = Modifier.fillMaxSize(),
                autoFocusInputs = false,
                animateStepTransitions = false,
            )
        }
    }
}

// CloudAccountSetupContent previously only ever split by window width (A03 covers that), never
// by an actual half-folded, tabletop posture.
@PreviewTest
@Preview(name = "Cloud account setup book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A120_CloudAccountSetupBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountSetupContent(
                onBack = {},
                onContinue = {},
                onSkip = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Cloud account setup tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A121_CloudAccountSetupTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            CloudAccountSetupContent(
                onBack = {},
                onContinue = {},
                onSkip = {},
            )
        }
    }
}
