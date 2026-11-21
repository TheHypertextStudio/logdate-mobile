package app.logdate.feature.editor.di

import app.logdate.client.domain.di.domainModule
import app.logdate.client.repository.account.AccountRepository
import app.logdate.feature.editor.ui.audio.audioModule
import app.logdate.feature.editor.ui.camera.CameraViewModel
import app.logdate.feature.editor.ui.editor.CaptureUsageReporter
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import app.logdate.feature.editor.ui.editor.delegate.ContentLoader
import app.logdate.feature.editor.ui.editor.delegate.DefaultPendingAudioRecoverer
import app.logdate.feature.editor.ui.editor.delegate.DraftManager
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioRecoverer
import app.logdate.ui.audio.di.audioUiModule
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin dependency injection module for the editor feature.
 *
 * This module provides all dependencies needed by the editor screens, including ViewModels,
 * use cases, and platform-specific services.
 */
val editorFeatureModule: Module =
    module {
        includes(domainModule)
        includes(platformEditorModule)
        includes(audioModule)
        includes(audioUiModule)

        factory {
            DraftManager(get(), get(), get(), get(), get(), get(), recordingOwner = {
                get<AccountRepository>()
                    .currentAccount
                    .first()
                    ?.id
                    ?.toString() ?: "anonymous"
            })
        }
        factoryOf(::ContentLoader)

        factory<PendingAudioRecoverer> { DefaultPendingAudioRecoverer(durationResolver = get(), recordingManager = get()) }

        viewModel {
            CameraViewModel(
                cameraCaptureManager = get(),
            )
        }

        viewModel {
            EntryEditorViewModel(
                savedStateHandle = get(),
                observeEditorData = get(),
                saveEntryUseCase = get(),
                draftManager = get(),
                contentLoader = get(),
                preferences = get(),
                pendingAudioRecoverer = get(),
                audioDurationResolver = get(),
                captureUsage = getOrNull<CaptureUsageReporter>() ?: CaptureUsageReporter.None,
                observeCapturePlace = getOrNull(),
            )
        }
    }
