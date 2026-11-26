package app.logdate.wear.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import app.logdate.client.device.AndroidAppInfoProvider
import app.logdate.client.media.audio.AdtsToM4aRemuxer
import app.logdate.client.media.audio.AndroidAudioDurationResolver
import app.logdate.client.media.audio.AndroidAudioPlaybackManager
import app.logdate.client.media.audio.AndroidAudioStorage
import app.logdate.client.media.audio.AndroidRecordingServiceController
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.media.audio.AudioStorage
import app.logdate.client.media.audio.RecordingServiceController
import app.logdate.client.media.audio.RecordingSessionOptions
import app.logdate.client.media.device.AndroidAudioRouteRepository
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.androidMediaFileResolver
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.SyncManager
import app.logdate.wear.data.storage.StorageSpaceChecker
import app.logdate.wear.haptic.WearHapticEngine
import app.logdate.wear.playback.AndroidWearPlaybackEngine
import app.logdate.wear.playback.PhoneSyncedAudioResolver
import app.logdate.wear.playback.SyncedAudioWatchMediaCache
import app.logdate.wear.playback.WatchMediaCache
import app.logdate.wear.playback.WearAudioOutputMonitor
import app.logdate.wear.playback.WearAudioOutputs
import app.logdate.wear.playback.WearPlaybackEngine
import app.logdate.wear.playback.WearVoiceNotePlayer
import app.logdate.wear.presentation.camera.WearRemoteCameraViewModel
import app.logdate.wear.presentation.home.WearHomeViewModel
import app.logdate.wear.presentation.memories.WearMemoryPlayerViewModel
import app.logdate.wear.presentation.memories.WearVoiceMemoriesViewModel
import app.logdate.wear.presentation.mood.MoodCheckInViewModel
import app.logdate.wear.presentation.onboarding.WearOnboardingViewModel
import app.logdate.wear.presentation.recording.RecordingHintStore
import app.logdate.wear.presentation.recording.SharedPreferencesRecordingHintStore
import app.logdate.wear.presentation.recording.WearRecordingViewModel
import app.logdate.wear.presentation.settings.WearSettingsViewModel
import app.logdate.wear.presentation.timeline.WearTimelineViewModel
import app.logdate.wear.recording.MicrophonePermissionChecker
import app.logdate.wear.recording.WearAudioRecordingManager
import app.logdate.wear.recording.WearRecorder
import app.logdate.wear.recording.WearRecordingRecovery
import app.logdate.wear.sync.PhoneReachability
import app.logdate.wear.sync.SyncedAudioCache
import app.logdate.wear.sync.WearDataLayerClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/**
 * Koin module for Wear OS audio recording, playback, and capture features.
 */
private val wearIoDispatcherQualifier = named("wear-audio-io-dispatcher")

val wearAudioModule =
    module {
        single { StorageSpaceChecker(get()) }
        single<AudioStorage> { AndroidAudioStorage(get()) }
        single {
            val vibratorManager =
                get<Context>()
                    .getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            WearHapticEngine(vibratorManager.defaultVibrator)
        }
        single<AudioDurationResolver> { AndroidAudioDurationResolver(get(), mediaFiles = get()) }
        single<RecordingServiceController> {
            AndroidRecordingServiceController(
                context = get(),
                options =
                    RecordingSessionOptions(
                        maxDurationMs = WearAudioRecordingManager.MAX_RECORDING_DURATION.inWholeMilliseconds,
                        pauseOnInterruption = true,
                        holdWakeLock = true,
                        crashSafe = true,
                    ),
            )
        }
        single {
            val context = get<Context>()
            WearAudioRecordingManager(
                storageChecker = get(),
                audioStorage = get(),
                audioRouteRepository = get(),
                serviceController = get(),
                microphonePermission =
                    MicrophonePermissionChecker {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    },
                canCapture = { !get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>().removed() },
            )
        }
        single<WearRecorder> { get<WearAudioRecordingManager>() }
        single {
            WearRecordingRecovery(
                audioDirectory = File(get<Context>().filesDir, "audio_notes"),
                notesRepository = get(),
                durationResolver = get(),
                remuxer = AdtsToM4aRemuxer,
                captureGuard = get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>(),
            )
        }
        single<RecordingHintStore> { SharedPreferencesRecordingHintStore(get()) }

        // Audio playback — reuses the phone's AndroidAudioPlaybackManager + AudioPlaybackService
        single { WearAudioOutputMonitor(get()) }
        single<WearAudioOutputs> { get<WearAudioOutputMonitor>() }
        single { androidMediaFileResolver(get<Context>()) }
        single<StoredMediaReferences> { get<MediaFileResolver>() }
        single { AndroidAudioPlaybackManager(get(), get(), mediaFiles = get()) }
        single<AudioPlaybackManager> { get<AndroidAudioPlaybackManager>() }
        single<AudioPlaybackStatusProvider> { get<AndroidAudioPlaybackManager>() }
        single<WearPlaybackEngine> { AndroidWearPlaybackEngine(get<AndroidAudioPlaybackManager>(), get<CoroutineScope>()) }
        single<AudioRouteRepository> { AndroidAudioRouteRepository(get()) }
        single<CoroutineDispatcher>(wearIoDispatcherQualifier) { Dispatchers.IO }
        // One player for every screen: they share one playback engine, so a second player would never
        // learn that the first one's note was replaced. Its scope matches a view model's.
        single {
            WearVoiceNotePlayer(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                engine = get(),
                outputs = get(),
                resolver = get(),
            )
        }
        single { SyncedAudioCache(File(get<Context>().filesDir, SyncedAudioCache.DIRECTORY_NAME)) }
        single<WatchMediaCache> {
            val storage = get<StorageSpaceChecker>()
            SyncedAudioWatchMediaCache(cache = get(), freeBytes = storage::getAvailableStorageSpace)
        }
        single {
            PhoneSyncedAudioResolver(
                context = get(),
                mediaCache = get(),
                dataLayerClient = get(),
                notesRepository = get(),
                ioDispatcher = get(qualifier = wearIoDispatcherQualifier),
                mediaFiles = get(),
            )
        }
        viewModel {
            WearRecordingViewModel(
                recorder = get(),
                notesRepository = get(),
                durationResolver = get(),
                noteHealthAnnotator = get(),
                dataLayerClient = get(),
                locationCaptureCoordinator = get(),
                haptics = get(),
                hintStore = get(),
                removalNotifier = get(),
                applicationScope = get(),
                mediaFiles = get(),
            )
        }
        viewModel {
            MoodCheckInViewModel(
                get<JournalNotesRepository>(),
                get<WearDataLayerClient>(),
                get(),
                captureGuard = get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>(),
                captureActivity = get(),
            )
        }
        viewModel {
            WearHomeViewModel(
                get<JournalNotesRepository>(),
                get<SyncManager>(),
            )
        }
        viewModel {
            val player = get<WearVoiceNotePlayer>()
            WearTimelineViewModel(
                notesRepository = get(),
                playerFactory = { player },
                dataLayerClient = get(),
            )
        }
        viewModel {
            WearVoiceMemoriesViewModel(
                notesRepository = get(),
                dataLayerClient = get(),
            )
        }
        viewModel {
            val player = get<WearVoiceNotePlayer>()
            WearMemoryPlayerViewModel(
                notesRepository = get(),
                playerFactory = { player },
            )
        }
        viewModel {
            WearRemoteCameraViewModel(
                get(),
            )
        }
        viewModel {
            WearOnboardingViewModel(
                get(),
                get(),
            )
        }
        viewModel {
            WearSettingsViewModel(
                get<SyncManager>(),
                get<PhoneReachability>(),
                get(),
                get(),
                get(),
                appVersion = runCatching { AndroidAppInfoProvider(get()).getAppInfo().versionName }.getOrNull(),
            )
        }
    }
