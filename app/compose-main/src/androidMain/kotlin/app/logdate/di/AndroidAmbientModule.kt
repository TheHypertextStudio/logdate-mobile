package app.logdate.di

import app.logdate.client.ambient.AmbientPromptNotificationCoordinator
import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.ambient.AmbientPromptSchedulingObserver
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.platform.FeatureFlagState
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal val androidAmbientModule: Module =
    module {
        single {
            val quickReply = FeatureFlagState(get(), FeatureFlag.NOTIFICATION_QUICK_REPLY, get())
            AmbientPromptNotificationCoordinator(androidContext(), quickReplyEnabled = quickReply::enabled)
        }
        single { AmbientPromptScheduler(androidContext(), get(), get(), get()) }
        single { AmbientPromptSchedulingObserver(get(), get(), get(), get(), get()) }
    }
