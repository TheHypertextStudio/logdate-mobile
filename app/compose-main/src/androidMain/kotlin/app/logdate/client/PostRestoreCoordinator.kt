package app.logdate.client

import app.logdate.client.database.DatabaseStartupState
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.PostRestoreType
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import io.github.aakira.napier.Napier

/**
 * Handles post-restore detection and recovery triggers after application startup.
 */
internal class PostRestoreCoordinator(
    private val postRestoreDetector: PostRestoreDetector,
    private val viewModel: AppViewModel,
) {
    private var hasDetectedPostRestore = false

    fun detectPostRestoreOnce(
        state: GlobalAppUiLoadedState,
        databaseStartupState: DatabaseStartupState,
    ) {
        if (hasDetectedPostRestore || databaseStartupState is DatabaseStartupState.Initializing) return
        hasDetectedPostRestore = true

        val detected = postRestoreDetector.detect(isOnboarded = state.isOnboarded)
        if (postRestoreDetector.isAccountRecoveryPending() || detected == PostRestoreType.CONTENT_MISSING) {
            val accountDelivery = postRestoreDetector.pendingAccountRecoveryId()
            viewModel.tryRestoreSignInAfterCloudRestore(
                recoverMissingContent =
                    detected == PostRestoreType.CONTENT_MISSING &&
                        databaseStartupState !is DatabaseStartupState.RecoveryRequired,
                onAccountResolved = {
                    if (accountDelivery != null) {
                        postRestoreDetector.acknowledgeAccountRecovery(accountDelivery)
                    }
                },
            )
        }

        when (detected) {
            PostRestoreType.NONE -> {
                postRestoreDetector.markDeviceInitialized()
            }

            PostRestoreType.CONTENT_RESTORED -> {
                if (databaseStartupState !is DatabaseStartupState.RecoveryRequired) {
                    postRestoreDetector.markDeviceInitialized()
                }
            }

            PostRestoreType.RECOVERY_REQUIRED -> {
                Napier.w("Native restore delivery state needs recovery", tag = APP_LAUNCH_TAG)
            }

            PostRestoreType.CONTENT_MISSING -> {
                Napier.i("Native restore needs journal content recovery", tag = APP_LAUNCH_TAG)
            }
        }
    }
}
