package app.logdate.feature.rewind.di

import app.logdate.client.domain.di.domainModule
import app.logdate.client.intelligence.di.intelligenceModule
import app.logdate.client.intelligence.rewind.RewindMessageGenerator
import app.logdate.client.intelligence.rewind.WittyRewindMessageGenerator
import app.logdate.client.media.di.audioModule
import app.logdate.client.sharing.di.sharingModule
import app.logdate.feature.rewind.ui.RewindDetailViewModel
import app.logdate.feature.rewind.ui.overview.RewindOverviewViewModel
import app.logdate.feature.rewind.ui.settings.RewindSettingsViewModel
import app.logdate.ui.audio.di.audioUiModule
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Module for Rewind functionality.
 */
val rewindFeatureModule: Module =
    module {
        includes(domainModule)
        includes(intelligenceModule)
        includes(sharingModule)
        includes(audioModule)
        includes(audioUiModule)

        // Ensure we have a RewindMessageGenerator implementation
        single<RewindMessageGenerator> { WittyRewindMessageGenerator() }

        // ViewModels using standard Koin viewModelOf DSL
        viewModelOf(::RewindOverviewViewModel)
        viewModelOf(::RewindDetailViewModel)
        viewModelOf(::RewindSettingsViewModel)
    }
