package app.logdate.client.media.di

import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.AudioStorage
import app.logdate.client.media.audio.DesktopAudioDurationResolver
import app.logdate.client.media.audio.DesktopAudioPlaybackManager
import app.logdate.client.media.audio.DesktopAudioRecordingManager
import app.logdate.client.media.audio.DesktopAudioStorage
import app.logdate.client.media.audio.sherpa.DesktopSherpaAudioTaggingService
import app.logdate.client.media.audio.sherpa.DesktopSherpaModelManager
import app.logdate.client.media.audio.sherpa.DesktopSherpaTranscriptionService
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.device.SystemControlledAudioRouteRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop implementation of audio module
 */
actual val audioModule: Module =
    module {
        single<AudioStorage> { DesktopAudioStorage() }
        // Provide the Desktop implementation of AudioRecordingManager as a singleton
        single<AudioRecordingManager> { DesktopAudioRecordingManager(get()) }
        single<AudioPlaybackManager> { DesktopAudioPlaybackManager(get()) }
        single<AudioDurationResolver> { DesktopAudioDurationResolver(get()) }
        single<AudioRouteRepository> { SystemControlledAudioRouteRepository() }

        single { DesktopSherpaModelManager() }

        // Real on-device Whisper transcription via Sherpa-ONNX JVM. The
        // service handles its own download lifecycle and degrades cleanly
        // (live transcription off, file transcription off until the model
        // is downloaded) when the user hasn't pulled the model from
        // Settings → Audio notes yet.
        single<TranscriptionService>(createdAtStart = true) { DesktopSherpaTranscriptionService(modelManager = get()) }

        // Real on-device ambient sound tagger via Sherpa-ONNX CED. The
        // service handles its own download lifecycle and degrades cleanly
        // (no detected sounds) when the user hasn't pulled the model from
        // Settings → Audio notes yet.
        single<AudioTaggingService>(createdAtStart = true) { DesktopSherpaAudioTaggingService(modelManager = get()) }
    }
