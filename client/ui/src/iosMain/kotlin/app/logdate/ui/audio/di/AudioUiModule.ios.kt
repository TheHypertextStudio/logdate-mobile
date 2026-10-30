package app.logdate.ui.audio.di

import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.ui.audio.IosWaveformStorage
import app.logdate.ui.audio.WaveformStorage
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import app.logdate.ui.audio.extraction.IosAmplitudeExtractor
import org.koin.core.module.Module
import org.koin.dsl.module

actual val audioUiModule: Module =
    module {
        single<WaveformStorage> { IosWaveformStorage() }
        single<AmplitudeExtractor> { IosAmplitudeExtractor(get()) }
        single { AudioContextProcessor(get(), get()) }
    }
