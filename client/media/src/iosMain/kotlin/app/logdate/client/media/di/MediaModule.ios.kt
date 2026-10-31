package app.logdate.client.media.di

import app.logdate.client.media.IosMediaCleaner
import app.logdate.client.media.IosMediaManager
import app.logdate.client.media.IosPhotoLibraryMediaManager
import app.logdate.client.media.MediaCleaner
import app.logdate.client.media.MediaManager
import app.logdate.client.media.audio.transcription.IosTranscriptionManager
import app.logdate.client.media.audio.transcription.TranscriptionManager
import app.logdate.client.media.display.RemoteDisplayManager
import app.logdate.client.media.display.UnavailableRemoteDisplayManager
import app.logdate.client.media.storage.IosOutOfLibraryMediaRescuer
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.MediaRescuer
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.iosMediaFileResolver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Module that exposes handles for interacting with OS-specific media library APIs.
 */
actual val mediaModule: Module =
    module {
        factory {
            val manager = get<MediaManager>()
            app.logdate.client.media.ManagedMediaImporter(
                manager,
                app.logdate.client.media
                    .StreamingManagedMediaImportSource(manager, platform.Foundation.NSTemporaryDirectory()),
            )
        }
        // Include the audio module only
        includes(audioModule)

        single { iosMediaFileResolver() }
        single<MediaRescuer> { IosOutOfLibraryMediaRescuer(get()) }
        single<StoredMediaReferences> { get<MediaFileResolver>() }
        single<MediaManager> { IosPhotoLibraryMediaManager(IosMediaManager(get())) }
        single<MediaCleaner> { IosMediaCleaner(get()) }

        // Transcription manager for iOS
        single<TranscriptionManager> {
            IosTranscriptionManager(get(), get(), repository = { get() })
        }

        single<RemoteDisplayManager> { UnavailableRemoteDisplayManager() }
    }
