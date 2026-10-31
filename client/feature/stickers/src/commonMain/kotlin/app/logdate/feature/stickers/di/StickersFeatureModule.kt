package app.logdate.feature.stickers.di

import app.logdate.feature.stickers.ui.StickerExtractionViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val stickersFeatureModule =
    module {
        viewModelOf(::StickerExtractionViewModel)
    }
