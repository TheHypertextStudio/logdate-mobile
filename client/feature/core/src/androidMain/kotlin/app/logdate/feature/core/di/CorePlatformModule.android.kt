package app.logdate.feature.core.di

import android.app.Activity
import app.logdate.client.data.account.RestoreCredentialCleanupScheduler
import app.logdate.client.data.account.RestoreCredentialLifecycle
import app.logdate.client.device.crypto.AndroidDeviceTransferSealer
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.restore.AndroidNativeBackupStatusSource
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.feature.core.AndroidBiometricGatekeeper
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.export.AndroidExportLauncher
import app.logdate.feature.core.export.AndroidMediaSourceOpener
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.export.ExportLauncher
import app.logdate.feature.core.export.ExportWorker
import app.logdate.feature.core.restore.AndroidRestoreCredentialCleanupScheduler
import app.logdate.feature.core.restore.AndroidRestoreLauncher
import app.logdate.feature.core.restore.CloudRestoreScheduler
import app.logdate.feature.core.restore.CloudRestoreWorker
import app.logdate.feature.core.restore.DefaultRestoreDiagnosticEvents
import app.logdate.feature.core.restore.RestoreCredentialCleanupWorker
import app.logdate.feature.core.restore.RestoreDiagnosticEvents
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.restore.RestoreWorker
import app.logdate.feature.core.settings.ui.AndroidCloudArchiveStatusSource
import app.logdate.feature.core.settings.ui.AndroidDiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.CloudArchiveStatusSource
import app.logdate.feature.core.settings.ui.DiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.devices.CodeScannerAvailability
import app.logdate.feature.core.settings.ui.devices.PlayServicesCodeScannerAvailability
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val corePlatformModule: Module =
    module {
        includes(watchSettingsModule)

        single { AndroidBiometricGatekeeper() }
        single<BiometricGatekeeper> { get<AndroidBiometricGatekeeper>() }

        single { ActivityProvider() }

        single<DeviceTransferSealer> { AndroidDeviceTransferSealer() }
        single<CodeScannerAvailability> { PlayServicesCodeScannerAvailability }
        single<MediaSourceOpener> { AndroidMediaSourceOpener(androidContext(), get()) }
        single {
            val identity = get<IdentityKeyManager>()
            val derivation = get<KeyDerivation>()
            CloudArchiveCipher {
                derivation.deriveKey(identity.getIdentityKey(), "cloud_archive", "cloud_archive_v1")
            }
        }
        single { AndroidExportLauncher(androidContext()) }
        single<CloudArchiveStatusSource> {
            AndroidCloudArchiveStatusSource(androidContext(), get(), get(), get(), get(), get())
        }
        single<ExportLauncher> { get<AndroidExportLauncher>() }
        workerOf(::ExportWorker)
        single { AndroidRestoreLauncher(androidContext()) }
        single<RestoreLauncher> { get<AndroidRestoreLauncher>() }
        workerOf(::RestoreWorker)
        workerOf(::CloudRestoreWorker)
        single<RestoreCredentialCleanupScheduler> { AndroidRestoreCredentialCleanupScheduler(androidContext()) }
        worker {
            RestoreCredentialCleanupWorker(
                context = get(),
                params = get(),
                lifecycle = get<RestoreCredentialLifecycle>(),
            )
        }
        single { AndroidNativeBackupStatusSource(androidContext()) }
        single<RestoreDiagnosticEvents> { DefaultRestoreDiagnosticEvents(get(), get()) }
        single<DiagnosticArchiveExporter> { AndroidDiagnosticArchiveExporter(androidContext()) }

        single(named("supportsSystemSearchVisibilityToggle")) { true }
        single<CloudRestoreAction> { CloudRestoreAction { get<CloudRestoreScheduler>().enqueueRestore() } }
    }

/**
 * Helper class to hold a reference to the current activity.
 * This is needed for launching the file picker.
 */
class ActivityProvider {
    var currentActivity: Activity? = null
}
