package app.logdate.feature.core.di

import app.logdate.client.device.crypto.CryptographyDeviceTransfer
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.IosBiometricGatekeeper
import app.logdate.feature.core.export.ExportLauncher
import app.logdate.feature.core.export.IosExportLauncher
import app.logdate.feature.core.export.IosMediaSourceOpener
import app.logdate.feature.core.restore.IosRestoreLauncher
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.settings.ui.DiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.HiddenMemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.IosDiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.ServerConfigurationCoordinator
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.UnsupportedAppUpdateController
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.UIKit.UIApplication

actual val corePlatformModule: Module =
    module {
        single<BiometricGatekeeper> { IosBiometricGatekeeper() }
        single<AppUpdateController> { UnsupportedAppUpdateController(get()) }
        single<MemoriesWidgetInstallController> { HiddenMemoriesWidgetInstallController() }
        single<DeviceTransferSealer> { CryptographyDeviceTransfer() }
        single<MediaSourceOpener> { IosMediaSourceOpener(get()) }

        single<ExportLauncher> {
            IosExportLauncher(
                rootViewController = {
                    UIApplication.sharedApplication.keyWindow?.rootViewController
                        ?: throw IllegalStateException("No root view controller available")
                },
            )
        }
        single<RestoreLauncher> {
            IosRestoreLauncher(
                rootViewController = {
                    UIApplication.sharedApplication.keyWindow?.rootViewController
                        ?: throw IllegalStateException("No root view controller available")
                },
            )
        }
        single<DiagnosticArchiveExporter> {
            IosDiagnosticArchiveExporter(
                rootViewController = {
                    UIApplication.sharedApplication.keyWindow?.rootViewController
                        ?: throw IllegalStateException("No root view controller available")
                },
            )
        }

        factory {
            ServerConfigurationCoordinator(
                serverHealthChecker = get(),
                serverDiscoveryClient = get(),
                configRepository = get(),
                passkeysWorkWith = ::iosCreatesPasskeysFor,
            )
        }

        single(named("supportsSystemSearchVisibilityToggle")) { false }
    }

private fun iosCreatesPasskeysFor(rpId: String): Boolean = rpId == "logdate.app" || rpId.endsWith(".logdate.app")
