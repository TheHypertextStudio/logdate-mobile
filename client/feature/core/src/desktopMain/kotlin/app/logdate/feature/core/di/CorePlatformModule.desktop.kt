package app.logdate.feature.core.di

import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.NoOpBiometricGatekeeper
import app.logdate.feature.core.export.DesktopExportLauncher
import app.logdate.feature.core.export.DesktopMediaSourceOpener
import app.logdate.feature.core.export.ExportLauncher
import app.logdate.feature.core.restore.DesktopRestoreLauncher
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.settings.ui.DesktopDiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.DiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.HiddenMemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallController
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.UnsupportedAppUpdateController
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val corePlatformModule: Module =
    module {
        single<BiometricGatekeeper> { NoOpBiometricGatekeeper() }
        single<AppUpdateController> { UnsupportedAppUpdateController(get()) }
        single<MemoriesWidgetInstallController> { HiddenMemoriesWidgetInstallController() }

        single<MediaSourceOpener> { DesktopMediaSourceOpener() }
        single<ExportLauncher> { DesktopExportLauncher() }
        single<RestoreLauncher> { DesktopRestoreLauncher() }
        single<DiagnosticArchiveExporter> { DesktopDiagnosticArchiveExporter() }

        single(named("supportsSystemSearchVisibilityToggle")) { false }
    }
