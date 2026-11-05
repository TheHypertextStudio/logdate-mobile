package app.logdate.feature.core.di

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.CryptographyDeviceTransfer
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.account.EmailVerificationAvailability
import app.logdate.client.domain.account.EnqueueAllLocalDataUseCase
import app.logdate.client.domain.account.VerifyEmailUseCase
import app.logdate.client.domain.di.accountModule
import app.logdate.client.domain.di.domainModule
import app.logdate.client.domain.export.archive.MediaSourceOpener
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.location.di.locationSettingsModule
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.account.PasskeyConfirmations
import app.logdate.client.sync.diagnostics.DiagnosticReportingController
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.IosBiometricGatekeeper
import app.logdate.feature.core.account.CloudAccountOnboardingViewModel
import app.logdate.feature.core.export.ExportLauncher
import app.logdate.feature.core.export.IosExportLauncher
import app.logdate.feature.core.export.IosMediaSourceOpener
import app.logdate.feature.core.export.UserDataExportViewModel
import app.logdate.feature.core.main.HomeViewModel
import app.logdate.feature.core.people.ui.PeopleDirectoryViewModel
import app.logdate.feature.core.people.ui.PeopleInboxViewModel
import app.logdate.feature.core.people.ui.PeopleSettingsViewModel
import app.logdate.feature.core.people.ui.PersonDetailViewModel
import app.logdate.feature.core.profile.ui.ProfileViewModel
import app.logdate.feature.core.restore.IosRestoreLauncher
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.restore.UserDataRestoreViewModel
import app.logdate.feature.core.settings.account.AccountViewModel
import app.logdate.feature.core.settings.account.ConnectedServer
import app.logdate.feature.core.settings.account.DefaultConnectedServer
import app.logdate.feature.core.settings.account.delete.DeleteAccountViewModel
import app.logdate.feature.core.settings.account.hosting.HostingViewModel
import app.logdate.feature.core.settings.account.move.DefaultServerMover
import app.logdate.feature.core.settings.account.move.LocalDataSurvey
import app.logdate.feature.core.settings.account.move.MoveServerViewModel
import app.logdate.feature.core.settings.account.move.ServerMove
import app.logdate.feature.core.settings.account.move.ServerMoveStore
import app.logdate.feature.core.settings.account.recovery.AutomaticAccountRecovery
import app.logdate.feature.core.settings.account.recovery.IdentityAccountRecovery
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseViewModel
import app.logdate.feature.core.settings.account.recovery.accountRecoveryOffered
import app.logdate.feature.core.settings.account.recovery.passkeysSupported
import app.logdate.feature.core.settings.account.signin.SignInMethodsViewModel
import app.logdate.feature.core.settings.ui.AdvancedSettingsViewModel
import app.logdate.feature.core.settings.ui.AutomaticDiagnosticReportingViewModel
import app.logdate.feature.core.settings.ui.DangerZoneSettingsViewModel
import app.logdate.feature.core.settings.ui.DataSettingsViewModel
import app.logdate.feature.core.settings.ui.DayBoundarySettingsViewModel
import app.logdate.feature.core.settings.ui.DeviceEraser
import app.logdate.feature.core.settings.ui.DiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.HiddenMemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.IosDiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.LibrarySettingsViewModel
import app.logdate.feature.core.settings.ui.LocalDiagnosticsViewModel
import app.logdate.feature.core.settings.ui.LocationSettingsViewModel
import app.logdate.feature.core.settings.ui.MemoriesSettingsViewModel
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.PlacesBetaSettingsViewModel
import app.logdate.feature.core.settings.ui.PrivacySettingsViewModel
import app.logdate.feature.core.settings.ui.RecoveryPhraseEntryViewModel
import app.logdate.feature.core.settings.ui.ServerConfigurationCoordinator
import app.logdate.feature.core.settings.ui.SettingsOverviewViewModel
import app.logdate.feature.core.settings.ui.StreakSettingsViewModel
import app.logdate.feature.core.settings.ui.TimelineSettingsViewModel
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsViewModel
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.UnsupportedAppUpdateController
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.feature.core.sync.SyncPresentationViewModel
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.flow.map
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import platform.UIKit.UIApplication

/**
 * Feature module exposing core app screens and functionality.
 */
actual val coreFeatureModule: Module =
    module {
        // Include our domain modules - note that accountModule is included separately to avoid circular deps
        includes(domainModule)
        includes(accountModule)
        includes(devicesModule)
        includes(labsSettingsModule)
        includes(locationSettingsModule)

        // TODO: Refactor to separate auth module
        single<BiometricGatekeeper> { IosBiometricGatekeeper() }
        single<AppUpdateController> { UnsupportedAppUpdateController(get()) }
        single<MemoriesWidgetInstallController> { HiddenMemoriesWidgetInstallController() }
        single<DeviceTransferSealer> { CryptographyDeviceTransfer() }
        single<MediaSourceOpener> { IosMediaSourceOpener(get()) }

        // TODO: Verify this iOS export implementation works correctly with the root view controller
        // Export functionality for iOS - gets root view controller from the main application window
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
        factory {
            ServerConfigurationCoordinator(
                serverHealthChecker = get(),
                serverDiscoveryClient = get(),
                configRepository = get(),
                passkeysWorkWith = ::iosCreatesPasskeysFor,
            )
        }

        viewModel { AppViewModel(get(), get(), get(), get(), get()) }
        factory<ConnectedServer> { DefaultConnectedServer(get(), get(), get()) }
        single {
            AutomaticAccountRecovery(
                recovery =
                    IdentityAccountRecovery(
                        identity = get(),
                        isOffered =
                            accountRecoveryOffered(
                                featureFlags = get(),
                                connectedServer = get(),
                                passkeysSupported = passkeysSupported(get()),
                            ),
                    ),
                loadPhrase = { get<IdentityKeyManager>().getStoredRecoveryPhrase()?.words },
                signedInAccount = get<SessionStorage>().getSessionFlow().map { it?.accountId },
                passkeyConfirmations = get<PasskeyConfirmations>().latest,
                scope = get(),
            )
        }
        viewModel {
            AccountViewModel(
                accountRepository = get(),
                userIdentity = get<ObserveUserIdentityUseCase>()(),
                connectedServer = get(),
                hasRecoveryPhrase = { get<IdentityKeyManager>().hasIdentityKey() },
                isEmailVerificationAvailable = { get<EmailVerificationAvailability>().isAvailable() },
                verifyEmail = { get<VerifyEmailUseCase>()() },
                accountRecovery = get<AutomaticAccountRecovery>().state,
            )
        }
        viewModel {
            RecoveryPhraseViewModel(
                gatekeeper = get(),
                loadPhrase = { get<IdentityKeyManager>().getStoredRecoveryPhrase()?.words },
                recovery = get(),
            )
        }
        viewModel {
            HostingViewModel(
                connectedServer = get(),
                canMoveAccount = { get<PasskeyManager>().getCapabilities().isSupported },
            )
        }
        factory { ServerMoveStore(get()) }
        factory { LocalDataSurvey(get(), get()) }
        factory<ServerMove> {
            DefaultServerMover(
                configRepository = get(),
                scopedAccounts = get(),
                vault = get(),
                sessionStorage = get(),
                accountRepository = get(),
                syncManager = get(),
                mediaSyncRefStore = get(),
                enqueueAllLocalData = { get<EnqueueAllLocalDataUseCase>()() },
                localDataSurvey = { get<LocalDataSurvey>()() },
                moveStore = get(),
            )
        }
        viewModel {
            MoveServerViewModel(
                move = get(),
                checkServer = { address -> get<ServerConfigurationCoordinator>().check(address) },
                currentAccount = { get<PasskeyAccountRepository>().currentAccount.value },
            )
        }
        viewModel {
            DeleteAccountViewModel(
                accountRepository = get(),
                connectedServer = get(),
                eraseThisDevice = { get<DeviceEraser>().eraseEverything() },
            )
        }
        viewModel { SettingsOverviewViewModel(get(), get(), get()) }
        viewModel { PlacesBetaSettingsViewModel(get()) }
        viewModel { LibrarySettingsViewModel(get()) }
        viewModel {
            PrivacySettingsViewModel(
                get(),
                get(),
                get(),
                supportsSystemSearchVisibilityToggle = false,
            )
        }
        single<DiagnosticArchiveExporter>(createdAtStart = true) {
            IosDiagnosticArchiveExporter {
                UIApplication.sharedApplication.keyWindow?.rootViewController
                    ?: throw IllegalStateException("No root view controller available")
            }
        }
        viewModel { LocalDiagnosticsViewModel(get(), get(), get()) }
        viewModel { AutomaticDiagnosticReportingViewModel(get<DiagnosticReportingController>()) }
        viewModel {
            SignInMethodsViewModel(
                accountRepository = get(),
                passkeyManager = get(),
                defaultPasskeyName = { get<LogDateConfigRepository>().getCurrentServerDescriptor()?.passkey?.rpName },
                connectedRpId = { get<LogDateConfigRepository>().getCurrentServerDescriptor()?.passkey?.rpId },
                passkeysWorkWith = ::iosCreatesPasskeysFor,
            )
        }
        viewModel {
            RecoveryPhraseEntryViewModel(get())
        }
        viewModel {
            DataSettingsViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
        viewModel { UserDataExportViewModel(get(), get()) }
        viewModel { UserDataRestoreViewModel(get(), get()) }
        viewModel { AdvancedSettingsViewModel(appUpdateController = get(), preferences = get()) }
        factory { DeviceEraser(get(), get(), get(), get(), get()) }
        viewModel { DangerZoneSettingsViewModel(get()) }
        viewModel {
            HomeViewModel(
                getStreamingTimelineUseCase = get(),
                getTimelinePageUseCase = get(),
                notesRepository = get(),
                getHomeRecommendation = get(),
                linkNoteToEvent = get(),
                getJournalMembership = get(),
                transcriptionRepository = get(),
                preferencesDataSource = get(),
            )
        }
        viewModel { CloudAccountOnboardingViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { LocationSettingsViewModel(get()) }
        viewModel { MemoriesSettingsViewModel(get(), get(), get()) }
        viewModel { VoiceNotesSettingsViewModel(get(), get()) }
        viewModel { StreakSettingsViewModel(get(), get(), get()) }
        viewModel { TimelineSettingsViewModel(get(), get(), get()) }
        viewModel { DayBoundarySettingsViewModel(get(), get(), get()) }
        viewModel { ProfileViewModel(get(), get(), get(), get(), get()) }
        viewModel { PeopleSettingsViewModel(get(), get(), get(), get(), get()) }
        viewModel { PeopleDirectoryViewModel(get(), get()) }
        viewModel { PeopleInboxViewModel(get()) }
        viewModel { PersonDetailViewModel(get(), get()) }
        viewModel { SyncPresentationViewModel(syncManager = get(), sessionStorage = get()) }
        viewModel { CampfireViewModel(observeCampfire = get(), featureFlagStore = get()) }
    }

/** iOS only creates passkeys for domains in the app's associated domains. */
private fun iosCreatesPasskeysFor(rpId: String): Boolean = rpId == "logdate.app" || rpId.endsWith(".logdate.app")
