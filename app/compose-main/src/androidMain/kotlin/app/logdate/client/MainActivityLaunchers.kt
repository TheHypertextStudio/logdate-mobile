package app.logdate.client

import androidx.activity.result.contract.ActivityResultContracts
import app.logdate.client.updates.ActivityResultAppUpdateFlowLauncher
import app.logdate.client.updates.PlayInAppUpdateController
import app.logdate.client.watch.WatchCompanionAssociationManager
import app.logdate.feature.core.AndroidBiometricGatekeeper
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.di.ActivityProvider
import app.logdate.feature.core.export.AndroidExportLauncher
import app.logdate.feature.core.restore.AndroidRestoreLauncher
import io.github.aakira.napier.Napier

/**
 * Manages ActivityResult launchers and platform integration hooks for [MainActivity].
 *
 * Result contracts must be registered while the activity is constructed, but Android constructs
 * the launch activity before application startup has installed Koin. Dependencies therefore arrive
 * as [Lazy] values and are resolved only when a result or [attachPlatformLaunchers] needs them.
 */
internal class MainActivityLaunchers(
    private val activity: MainActivity,
    private val biometricGatekeeper: Lazy<BiometricGatekeeper>,
    private val activityProvider: Lazy<ActivityProvider>,
    private val androidExportLauncher: Lazy<AndroidExportLauncher>,
    private val androidRestoreLauncher: Lazy<AndroidRestoreLauncher>,
    private val playInAppUpdateController: Lazy<PlayInAppUpdateController>,
    private val watchCompanionAssociationManager: Lazy<WatchCompanionAssociationManager>,
) {
    private val createDocumentLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uri = if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
            androidExportLauncher.value.onExportDestinationSelected(uri)
        }

    private val openDocumentLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uri = if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
            androidRestoreLauncher.value.onRestoreSourceSelected(uri)
        }

    private val appUpdateLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            playInAppUpdateController.value.onUpdateFlowResult(result.resultCode)
        }

    private val watchAssociationLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            watchCompanionAssociationManager.value.onAssociationFlowResult(result.resultCode)
        }

    fun attachPlatformLaunchers() {
        (biometricGatekeeper.value as? AndroidBiometricGatekeeper)?.setActivity(activity)
        Napier.i("MainActivity onCreate: biometric gatekeeper configured", tag = APP_LAUNCH_TAG)

        activityProvider.value.currentActivity = activity
        androidExportLauncher.value.setupActivityResultLauncher(createDocumentLauncher)
        androidExportLauncher.value.setupWorkObserver(activity)
        androidRestoreLauncher.value.setupActivityResultLauncher(openDocumentLauncher)
        androidRestoreLauncher.value.setupWorkObserver(activity)
        playInAppUpdateController.value.attachLauncher(ActivityResultAppUpdateFlowLauncher(appUpdateLauncher))
        watchCompanionAssociationManager.value.attachLauncher(watchAssociationLauncher)
        Napier.i("MainActivity onCreate: export/restore/update launchers configured", tag = APP_LAUNCH_TAG)
    }
}
