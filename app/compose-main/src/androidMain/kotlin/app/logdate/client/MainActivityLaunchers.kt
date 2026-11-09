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
 */
internal class MainActivityLaunchers(
    private val activity: MainActivity,
    private val biometricGatekeeper: BiometricGatekeeper,
    private val activityProvider: ActivityProvider,
    private val androidExportLauncher: AndroidExportLauncher,
    private val androidRestoreLauncher: AndroidRestoreLauncher,
    private val playInAppUpdateController: PlayInAppUpdateController,
    private val watchCompanionAssociationManager: WatchCompanionAssociationManager,
) {
    private val createDocumentLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uri = if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
            androidExportLauncher.onExportDestinationSelected(uri)
        }

    private val openDocumentLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uri = if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null
            androidRestoreLauncher.onRestoreSourceSelected(uri)
        }

    private val appUpdateLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            playInAppUpdateController.onUpdateFlowResult(result.resultCode)
        }

    private val watchAssociationLauncher =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            watchCompanionAssociationManager.onAssociationFlowResult(result.resultCode)
        }

    fun attachPlatformLaunchers() {
        (biometricGatekeeper as? AndroidBiometricGatekeeper)?.setActivity(activity)
        Napier.i("MainActivity onCreate: biometric gatekeeper configured", tag = APP_LAUNCH_TAG)

        activityProvider.currentActivity = activity
        androidExportLauncher.setupActivityResultLauncher(createDocumentLauncher)
        androidExportLauncher.setupWorkObserver(activity)
        androidRestoreLauncher.setupActivityResultLauncher(openDocumentLauncher)
        androidRestoreLauncher.setupWorkObserver(activity)
        playInAppUpdateController.attachLauncher(ActivityResultAppUpdateFlowLauncher(appUpdateLauncher))
        watchCompanionAssociationManager.attachLauncher(watchAssociationLauncher)
        Napier.i("MainActivity onCreate: export/restore/update launchers configured", tag = APP_LAUNCH_TAG)
    }
}
