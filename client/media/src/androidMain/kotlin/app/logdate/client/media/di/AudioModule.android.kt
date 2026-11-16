package app.logdate.client.media.di

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.media.audio.AndroidAudioDurationResolver
import app.logdate.client.media.audio.AndroidAudioPlaybackManager
import app.logdate.client.media.audio.AndroidAudioRecordingManager
import app.logdate.client.media.audio.AndroidAudioStorage
import app.logdate.client.media.audio.AndroidRecordingServiceController
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.AudioStorage
import app.logdate.client.media.audio.QuietSaveNotifier
import app.logdate.client.media.audio.RecordingDraftSession
import app.logdate.client.media.audio.RecordingSessionOptions
import app.logdate.client.media.audio.SpeechFeatureProviderLoader
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.tagging.InstallTimeAudioTaggingService
import app.logdate.client.media.audio.transcription.InstallTimeTranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.device.AndroidAudioRouteRepository
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.repository.account.AccountRepository
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android implementation of audio module
 */
actual val audioModule: Module =
    module {
        single<AudioStorage> { AndroidAudioStorage(androidContext()) }
        // Provide the Android implementation of AudioRecordingManager as a singleton
        single<AudioRecordingManager> {
            val preferences = get<LogdatePreferencesDataSource>()
            val flags = get<FeatureFlagStore>()
            val accounts = get<AccountRepository>()
            val notifier = QuietSaveNotifier(androidContext())
            AndroidAudioRecordingManager(
                audioStorage = get(),
                transcriptionRepository = get(),
                audioTaggingService = get(),
                audioTagRepository = get(),
                audioRouteRepository = get(),
                serviceController =
                    AndroidRecordingServiceController(
                        androidContext(),
                        RecordingSessionOptions(
                            crashSafe = true,
                            holdWakeLock = true,
                            pauseOnInterruption = true,
                            excludeIncompleteFromBackup = true,
                        ),
                    ),
                draftSession =
                    RecordingDraftSession(get()) {
                        accounts.currentAccount
                            .first()
                            ?.id
                            ?.toString() ?: "anonymous"
                    },
                quietSaveEnabled = { flags.isEnabled(FeatureFlag.QUIET_SAVE) && preferences.observeQuietSaveEnabled().first() },
                onQuietSave = { notifier.saved(it) },
            )
        }
        single<AudioPlaybackManager> { AndroidAudioPlaybackManager(androidContext(), get(), mediaFiles = get()) }
        single<AudioDurationResolver> { AndroidAudioDurationResolver(androidContext(), mediaFiles = get()) }
        single<AudioRouteRepository> { AndroidAudioRouteRepository(androidContext()) }

        single { SpeechFeatureProviderLoader() }

        // Core on-device speech ships as an install-time feature split. The facade
        // keeps the base module independent of Sherpa-ONNX while making recording
        // treat transcription as an always-present product capability.
        single<TranscriptionService>(createdAtStart = true) {
            InstallTimeTranscriptionService(
                context = androidContext(),
                scope = get(),
                providerLoader = get(),
            )
        }

        // CED remains an optional model download inside the installed speech feature.
        single<AudioTaggingService>(createdAtStart = true) {
            InstallTimeAudioTaggingService(
                context = androidContext(),
                providerLoader = get(),
            )
        }
    }
