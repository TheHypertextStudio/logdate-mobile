package app.logdate.client.data.transcription.di

import app.logdate.client.data.transcription.OfflineFirstTranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import org.koin.dsl.module

/**
 * Koin module for transcription-related dependencies.
 * TranscriptionManager is now provided by the mediaModule.
 */
val transcriptionModule =
    module {
        // Repository implementation
        single<TranscriptionRepository> {
            OfflineFirstTranscriptionRepository(
                get(),
                get(),
                get(),
                mediaReferences = get(),
                syncMetadataService = get(),
                transactionManager = get(),
                syncManagerProvider = { get() },
                recoveryDeletions =
                    app.logdate.client.data.transcription
                        .ScopedTranscriptRecoveryDeletions(get(), get(), get()),
            )
        }
    }
