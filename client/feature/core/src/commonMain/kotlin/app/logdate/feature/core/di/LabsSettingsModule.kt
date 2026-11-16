package app.logdate.feature.core.di

import app.logdate.client.datastore.featureflags.PHONE_ACTIVITY_CONSENT_STORE
import app.logdate.feature.core.activity.PhoneActivityLinkViewModel
import app.logdate.feature.core.settings.ui.labs.LabsSettingsViewModel
import app.logdate.feature.core.settings.ui.labs.labsFlags
import app.logdate.ui.platform.currentPlatform
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Dependency injection for the Labs page that About LogDate opens.
 */
val labsSettingsModule: Module =
    module {
        viewModel { LabsSettingsViewModel(get(), labsFlags(currentPlatform)) }
        viewModel { PhoneActivityLinkViewModel(get(named(PHONE_ACTIVITY_CONSENT_STORE)), get()) }
    }
