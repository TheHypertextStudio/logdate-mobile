package app.logdate.feature.onboarding.di

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.media.AndroidManagedMediaDiscarder
import app.logdate.client.media.AndroidManagedMediaImportSource
import app.logdate.client.media.ManagedMediaImporter
import app.logdate.client.media.MediaManager
import app.logdate.feature.onboarding.ui.SelectedMemoryMediaImporter
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val onboardingPlatformModule: Module =
    module {
        single {
            OnboardingWatchSetup(
                coordinator = get(),
                enabled = get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
            )
        }
        single<SelectedMemoryMediaImporter> {
            val manager = get<MediaManager>()
            val context = androidContext().applicationContext
            val discarder = AndroidManagedMediaDiscarder(context, manager)
            val importer = ManagedMediaImporter(manager, AndroidManagedMediaImportSource(context), discarder::discard)
            object : SelectedMemoryMediaImporter {
                override suspend fun import(sourceUri: String): String = importer.import(sourceUri)

                override suspend fun discard(managedUri: String) = discarder.discard(managedUri)
            }
        }
    }
