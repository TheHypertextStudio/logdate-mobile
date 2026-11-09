package app.logdate.di

import app.logdate.client.domain.journals.GetCurrentUserJournalsUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.rewind.GetWeekRewindUseCase
import app.logdate.client.feature.widgets.shortcuts.DynamicShortcutPublisher
import app.logdate.client.shortcuts.AndroidCaptureUsageReporter
import app.logdate.client.shortcuts.AndroidDynamicShortcutApplier
import app.logdate.client.shortcuts.AndroidHomeScreenPins
import app.logdate.client.shortcuts.DynamicShortcutRefreshObserver
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.client.shortcuts.JournalShortcutIcons
import app.logdate.feature.editor.ui.editor.CaptureUsageReporter
import app.logdate.ui.common.HomeScreenPins
import coil3.SingletonImageLoader
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal val androidShortcutsModule: Module =
    module {
        single {
            val fetchMostRecentDraft: FetchMostRecentDraftUseCase = get()
            val getCurrentUserJournals: GetCurrentUserJournalsUseCase = get()
            DynamicShortcutPublisher(
                fetchMostRecentDraft = { fetchMostRecentDraft() },
                currentWeekRewind = { get<GetWeekRewindUseCase>().invoke() },
                observeJournals = { getCurrentUserJournals() },
            )
        }
        single {
            AndroidDynamicShortcutApplier(
                context = androidContext(),
                imageLoader = SingletonImageLoader.get(androidContext()),
            )
        }
        single { DynamicShortcutScheduler(androidContext()) }
        single {
            DynamicShortcutRefreshObserver(
                fetchMostRecentDraft = get(),
                currentWeekRewind = get(),
                observeJournals = get(),
                shortcutScheduler = get(),
                applicationScope = get(),
            )
        }
        single<CaptureUsageReporter> { AndroidCaptureUsageReporter(androidContext()) }
        single<HomeScreenPins> {
            AndroidHomeScreenPins(
                context = androidContext(),
                icons = JournalShortcutIcons(androidContext(), SingletonImageLoader.get(androidContext())),
                journals = get(),
                scope = get(),
            )
        }
    }
