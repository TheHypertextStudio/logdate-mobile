package app.logdate.feature.journals.di

import app.logdate.client.sharing.di.sharingModule
import app.logdate.feature.journals.ui.JournalsOverviewViewModel
import app.logdate.feature.journals.ui.creation.JournalCreationViewModel
import app.logdate.feature.journals.ui.detail.AudioNoteViewerViewModel
import app.logdate.feature.journals.ui.detail.JournalDetailViewModel
import app.logdate.feature.journals.ui.detail.NoteViewerViewModel
import app.logdate.feature.journals.ui.merge.JournalMergeViewModel
import app.logdate.feature.journals.ui.picker.JournalContentPickerViewModel
import app.logdate.feature.journals.ui.settings.JournalSettingsViewModel
import app.logdate.feature.journals.ui.share.ShareJournalViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.uuid.Uuid

/**
 * Module for Journals functionality.
 */
val journalsFeatureModule: Module =
    module {
        includes(sharingModule)
        viewModel { JournalsOverviewViewModel(get(), get(), get(), get(), get()) }
        viewModel { JournalCreationViewModel(get(), get(), get(), get()) }
        viewModel {
            JournalDetailViewModel(
                repository = get(),
                journalContentRepository = get(),
                getJournalMembership = get(),
                searchInJournal = get(),
                savedStateHandle = get(),
            )
        }
        viewModel { (noteId: Uuid, journalId: Uuid?) ->
            NoteViewerViewModel(
                noteId = noteId,
                journalId = journalId,
                notesRepository = get(),
                journalRepository = get(),
                journalContentRepository = get(),
                removeNoteUseCase = get(),
                sharingLauncher = get(),
            )
        }
        viewModel { (noteId: Uuid) ->
            AudioNoteViewerViewModel(
                noteId = noteId,
                notesRepository = get(),
                audioContextProcessor = get(),
                durationResolver = get(),
                audioPlaybackManager = get(),
                getJournalMembership = get(),
                audioTagRepository = get(),
            )
        }
        viewModel {
            JournalSettingsViewModel(
                getJournalByIdUseCase = get(),
                updateJournalUseCase = get(),
                deleteJournalUseCase = get(),
                journalContentRepository = get(),
                sharingLauncher = get(),
                linkSharingAvailability = get(),
            )
        }
        viewModel { ShareJournalViewModel(get(), get(), get()) }
        viewModel { JournalMergeViewModel(get(), get()) }
        viewModel { JournalContentPickerViewModel(get(), get(), get(), get()) }
    }
