package app.logdate.feature.journals.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.feature.journals.ui.JournalsOverviewScreen
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data object JournalsOverviewRoute : NavKey

/** Pushes the journals overview list. */
fun NavBackStack<NavKey>.navigateToJournalsOverview() {
    add(JournalsOverviewRoute)
}

/** Registers the journals overview entry. */
fun EntryProviderScope<NavKey>.journalsOverviewEntry(
    onOpenJournal: (Uuid) -> Unit,
    onCreateJournal: () -> Unit,
    onBrowseJournals: () -> Unit = {},
) {
    taggedEntry<JournalsOverviewRoute>(metadata = ActivityPresentation.Collection("journals").asMetadata()) {
        JournalsOverviewScreen(
            onOpenJournal = onOpenJournal,
            onBrowseJournals = onBrowseJournals,
            onCreateJournal = onCreateJournal,
        )
    }
}

/**
 * Convenience that registers every journal-related entry: overview, detail, settings,
 * creation, share, and note viewer. Callers supply navigation lambdas; this module never
 * has to know about the surrounding back stack shape.
 */
fun EntryProviderScope<NavKey>.journalEntries(
    onOpenJournal: (Uuid) -> Unit,
    onCreateJournal: () -> Unit,
    onBack: () -> Unit,
    onJournalDeleted: () -> Unit,
    onOpenNote: (noteId: Uuid, journalId: Uuid) -> Unit,
    onOpenEditorForJournal: (Uuid) -> Unit,
    onOpenContentPickerForJournal: (Uuid) -> Unit,
    onOpenJournalSettings: (Uuid) -> Unit,
    onShareJournal: (Uuid) -> Unit,
    onMergeJournal: (Uuid) -> Unit = {},
    onJournalMerged: (JournalMergeOperation) -> Unit = {},
    onJournalRedirected: (sourceId: Uuid, destinationId: Uuid) -> Unit = { _, _ -> },
    onNavigateToNote: (noteId: Uuid, journalId: Uuid?) -> Unit = { _, _ -> },
    onEditNote: ((Uuid) -> Unit)? = null,
    onOpenLocationTimeline: (() -> Unit)? = null,
) {
    journalsOverviewEntry(
        onOpenJournal = onOpenJournal,
        onCreateJournal = onCreateJournal,
    )
    journalDetailsEntry(
        onBack = onBack,
        onJournalDeleted = onJournalDeleted,
        onOpenNote = onOpenNote,
        onOpenEditor = onOpenEditorForJournal,
        onOpenContentPicker = onOpenContentPickerForJournal,
        onOpenSettings = onOpenJournalSettings,
        onShareJournal = onShareJournal,
        onMergeJournal = onMergeJournal,
        onJournalRedirected = onJournalRedirected,
    )
    journalCreationEntry(
        onBack = onBack,
        onJournalCreated = onOpenJournal,
    )
    journalSettingsEntry(
        onBack = onBack,
        onJournalDeleted = onJournalDeleted,
    )
    journalContentPickerEntry(onBack = onBack)
    journalMergeEntry(onBack = onBack, onJournalMerged = onJournalMerged)
    shareJournalEntry(onBack = onBack)
    noteDetailEntry(
        onBack = onBack,
        onNavigateToNote = onNavigateToNote,
        onEditNote = onEditNote,
        onOpenLocationTimeline = onOpenLocationTimeline,
    )
}
