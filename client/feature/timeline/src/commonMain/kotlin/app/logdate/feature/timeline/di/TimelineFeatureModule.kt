package app.logdate.feature.timeline.di

import app.logdate.client.domain.di.domainModule
import app.logdate.client.intelligence.di.intelligenceModule
import app.logdate.client.networking.di.networkingModule
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val timelineFeatureModule: Module =
    module {
        includes(domainModule)
        includes(intelligenceModule)
        includes(networkingModule)

        viewModel { parameters ->
            app.logdate.feature.timeline.ui.marks
                .MarkedMomentViewModel(parameters.get(), get())
        }
    }
