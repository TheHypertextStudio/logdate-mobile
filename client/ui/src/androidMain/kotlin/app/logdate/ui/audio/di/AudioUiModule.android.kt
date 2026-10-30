package app.logdate.ui.audio.di

import app.logdate.ui.audio.AndroidWaveformStorage
import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.ui.audio.WaveformStorage
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import app.logdate.ui.audio.extraction.AndroidAmplitudeExtractor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val audioUiModule: Module =
    module {
        single<WaveformStorage> { AndroidWaveformStorage(androidContext()) }
        single<AmplitudeExtractor> { AndroidAmplitudeExtractor(androidContext(), get()) }
        single { AudioContextProcessor(get(), get()) }
    }
