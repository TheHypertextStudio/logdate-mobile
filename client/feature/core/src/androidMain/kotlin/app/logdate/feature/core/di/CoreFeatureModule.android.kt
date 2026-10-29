package app.logdate.feature.core.di

import android.app.Activity
import app.logdate.client.data.account.RestoreCredentialCleanupScheduler
import app.logdate.client.data.restore.NativeJournalStagedSourceIndex
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.AndroidDeviceTransferSealer
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.restore.AndroidNativeBackupStatusSource
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
import app.logdate.feature.core.AndroidBiometricGatekeeper
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.feature.core.account.CloudAccountOnboardingViewModel
import app.logdate.feature.core.account.di.blueskySignInFeatureModule
import app.logdate.feature.core.export.AndroidExportLauncher
import app.logdate.feature.core.export.AndroidMediaSourceOpener
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.export.ExportLauncher
import app.logdate.feature.core.export.ExportWorker
import app.logdate.feature.core.export.UserDataExportViewModel
import app.logdate.feature.core.main.HomeViewModel
import app.logdate.feature.core.people.ui.PeopleDirectoryViewModel
import app.logdate.feature.core.people.ui.PeopleInboxViewModel
import app.logdate.feature.core.people.ui.PeopleSettingsViewModel
import app.logdate.feature.core.people.ui.PersonDetailViewModel
import app.logdate.feature.core.profile.ui.ProfileViewModel
import app.logdate.feature.core.restore.AndroidRestoreCredentialCleanupScheduler
import app.logdate.feature.core.restore.AndroidRestoreLauncher
import app.logdate.feature.core.restore.CloudRestoreScheduler
import app.logdate.feature.core.restore.CloudRestoreWorker
import app.logdate.feature.core.restore.DefaultRestoreDiagnosticEvents
import app.logdate.feature.core.restore.RestoreCredentialCleanupWorker
import app.logdate.feature.core.restore.RestoreDiagnosticEvents
import app.logdate.feature.core.restore.RestoreLauncher
import app.logdate.feature.core.restore.RestoreWorker
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
import app.logdate.feature.core.settings.account.signin.atprotoConnectionFeatureModule
import app.logdate.feature.core.settings.ui.AdvancedSettingsViewModel
import app.logdate.feature.core.settings.ui.AndroidCloudArchiveStatusSource
import app.logdate.feature.core.settings.ui.AndroidDiagnosticArchiveExporter
import app.logdate.feature.core.settings.ui.CloudArchiveStatusSource
import app.logdate.feature.core.settings.ui.DangerZoneSettingsViewModel
import app.logdate.feature.core.settings.ui.DataSettingsViewModel
import app.logdate.feature.core.settings.ui.DayBoundarySettingsViewModel
import app.logdate.feature.core.settings.ui.DeviceEraser
import app.logdate.feature.core.settings.ui.DiagnosticArchiveExporter
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
import app.logdate.feature.core.settings.ui.devices.CodeScannerAvailability
import app.logdate.feature.core.settings.ui.devices.PlayServicesCodeScannerAvailability
import app.logdate.feature.core.streak.CampfireViewModel
import app.logdate.feature.core.sync.SyncPresentationViewModel
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Feature module exposing core app screens and functionality.
 */
actual val coreFeatureModule: Module =
    module {
        // Include our domain modules - note that accountModule is included separately to avoid circular deps
        includes(domainModule)
        includes(blueskySignInFeatureModule)
        includes(atprotoConnectionFeatureModule)
        includes(accountModule)
        includes(devicesModule)
        includes(labsSettingsModule)
        includes(locationSettingsModule)
        includes(watchSettingsModule)

        // TODO: Refactor to separate auth module
        single { AndroidBiometricGatekeeper() }
        single<BiometricGatekeeper> { get<AndroidBiometricGatekeeper>() }

        // Export functionality with activity provider for file picker
        // We use lazy provider for current activity that will be set by the MainActivity
        single { ActivityProvider() }

        // Single instance exposed as both concrete type and interface
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
        workerOf(::RestoreCredentialCleanupWorker)
        single<RestoreCredentialCleanupScheduler> { AndroidRestoreCredentialCleanupScheduler(androidContext()) }
        worker { CloudRestoreWorker(androidContext(), get(), get(), get(), get()) }
        single<RestoreDiagnosticEvents> { DefaultRestoreDiagnosticEvents(get(), get()) }

        factory { ServerConfigurationCoordinator(get(), get(), get()) }

        viewModel { AppViewModel(get(), get(), get(), get(), get(), get<CloudRestoreScheduler>()::enqueueRestore) }
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
                confirmPhrase = { get<IdentityKeyManager>().verifyRecoveryPhrase(it) },
                isPhraseVerified = { get<IdentityKeyManager>().isRecoveryPhraseVerified() },
                onVerified = { get<CloudBackupScheduler>().enqueueImmediateBackup() },
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
                supportsSystemSearchVisibilityToggle = true,
            )
        }
        single<DiagnosticArchiveExporter> { AndroidDiagnosticArchiveExporter(androidContext()) }
        viewModel { LocalDiagnosticsViewModel(get(), get(), get()) }
        viewModel {
            SignInMethodsViewModel(
                accountRepository = get(),
                passkeyManager = get(),
                defaultPasskeyName = { get<LogDateConfigRepository>().getCurrentServerDescriptor()?.passkey?.rpName },
            )
        }
        viewModel {
            RecoveryPhraseEntryViewModel(
                recoverIdentityUseCase = get(),
                onRecoverySuccess = { get<CloudRestoreScheduler>().enqueueRestore() },
            )
        }
        viewModel {
            DataSettingsViewModel(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                nativeBackupStatusSource =
                    AndroidNativeBackupStatusSource(
                        androidContext(),
                        retryRecoveryAction = {
                            app.logdate.client.data.restore
                                .scheduleRetainedJournalReconciliation(androidContext())
                        },
                        stagedMediaInventories = { NativeJournalStagedSourceIndex(androidContext()).admittedMediaInventories() },
                        isRecoverableStagedAuxiliary = { payload ->
                            NativeJournalStagedSourceIndex(androidContext()).isRecoverableAuxiliary(payload)
                        },
                    ),
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
        viewModel { MemoriesSettingsViewModel(get(), get<MemoriesWidgetInstallController>()) }
        viewModel { VoiceNotesSettingsViewModel(get(), get(), get(), get()) }
        viewModel { StreakSettingsViewModel(get(), get(), get()) }
        viewModel { TimelineSettingsViewModel(get(), get(), get()) }
        viewModel { DayBoundarySettingsViewModel(get(), get(), get()) }
        viewModel { ProfileViewModel(get(), get(), get(), get(), get()) }
        viewModel { PeopleSettingsViewModel(get(), get(), get(), get(), get()) }
        viewModel { PeopleDirectoryViewModel(get(), get()) }
        viewModel { PeopleInboxViewModel(get()) }
        viewModel { PersonDetailViewModel(get(), get()) }
        viewModel { SyncPresentationViewModel(syncManager = get(), sessionStorage = get(), cloudArchiveStatusSource = get()) }
        viewModel { CampfireViewModel(observeCampfire = get(), featureFlagStore = get()) }
    }

// TODO: Fix this obvious code smell

/**
 * Helper class to hold a reference to the current activity.
 * This is needed for launching the file picker.
 */
class ActivityProvider {
    var currentActivity: Activity? = null
}
