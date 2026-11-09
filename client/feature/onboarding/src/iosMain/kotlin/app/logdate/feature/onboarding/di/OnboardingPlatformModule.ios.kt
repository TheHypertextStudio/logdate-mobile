package app.logdate.feature.onboarding.di

import app.logdate.client.media.MediaManager
import app.logdate.feature.onboarding.ui.SelectedMemoryMediaImporter
import org.koin.core.module.Module
import org.koin.dsl.module

actual val onboardingPlatformModule: Module =
    module {
        single<SelectedMemoryMediaImporter> {
            val manager = get<MediaManager>()
            object : SelectedMemoryMediaImporter {
                override suspend fun import(sourceUri: String): String = manager.saveMedia(manager.readMedia(sourceUri))

                override suspend fun discard(managedUri: String) {
                    manager.deleteOwnedMedia(managedUri)
                }
            }
        }
    }
