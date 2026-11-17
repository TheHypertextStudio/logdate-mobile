@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.space.migration.JournalDataKind
import app.logdate.client.sync.space.migration.JournalMoveProblem
import app.logdate.client.sync.space.migration.JournalMoveState
import app.logdate.feature.core.settings.ui.CloudArchivePhase
import app.logdate.feature.core.settings.ui.CloudArchiveStatus
import app.logdate.feature.core.settings.ui.DeveloperToolsContent
import app.logdate.feature.core.settings.ui.LocalDiagnosticsState
import app.logdate.feature.core.settings.ui.StoragePreviewsSection
import app.logdate.feature.core.settings.ui.StorageQuotaUi
import app.logdate.feature.core.settings.ui.SyncSettingsContent
import kotlin.time.Duration.Companion.days

/**
 * Sync settings while the journal is upgraded to sealed storage: paused with a specific problem,
 * finished with the previous copy still kept, and an app too old for the account's storage.
 */
internal val journalStorageScenes =
    listOf(
        sharedScene(SharedScreenshotSceneId.JournalStoragePaused, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            JournalStoragePage(JournalMoveState.Stopped(JournalMoveProblem.MissingOnThisDevice(JournalDataKind.ENTRIES, 3), retryAt = null))
        },
        sharedScene(SharedScreenshotSceneId.JournalStorageSealed, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            JournalStoragePage(JournalMoveState.Moved(previousCopyKeptUntil = baseInstant + 30.days))
        },
        sharedScene(SharedScreenshotSceneId.StoragePreviews, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            DeveloperToolsContent(
                onBack = {},
                state = LocalDiagnosticsState(eventCount = 3),
                onPreview = {},
                onExport = {},
                onClear = {},
                onSetVerboseEnabled = {},
                storagePreviewsContent = {
                    StoragePreviewsSection(personalSpace = true, move = false, onPersonalSpaceChange = {}, onMoveChange = {})
                },
            )
        },
        sharedScene(SharedScreenshotSceneId.SyncUpdateRequired, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            JournalStoragePage(
                journalMove = JournalMoveState.NotOffered,
                lastError =
                    SyncError(
                        SyncErrorType.ACCOUNT_STORAGE_ERROR,
                        "This account's journal is stored in a personal space",
                        retryable = false,
                    ),
            )
        },
    )

@Composable
private fun JournalStoragePage(
    journalMove: JournalMoveState,
    lastError: SyncError? = null,
) {
    SyncSettingsContent(
        onBack = {},
        syncStatus = SyncStatus(true, baseInstant, 0, false, hasErrors = lastError != null, lastError = lastError),
        cloudArchiveStatus = CloudArchiveStatus(phase = CloudArchivePhase.COMPLETE, lastCompletedAt = baseInstant.toEpochMilliseconds()),
        isAuthenticated = true,
        onNavigateToSignIn = {},
        snackbarHostState = remember { SnackbarHostState() },
        journalMove = journalMove,
        onUpdateApp = if (lastError != null) ({}) else null,
    )
}
