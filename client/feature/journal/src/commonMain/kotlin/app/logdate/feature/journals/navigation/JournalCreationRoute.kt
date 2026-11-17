package app.logdate.feature.journals.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.journals.ui.creation.JournalCreationScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class JournalCreationRoute(
    val journalTitle: String = "",
) : NavKey

/** Registers the journal creation entry. */
fun EntryProviderScope<NavKey>.journalCreationEntry(
    onBack: () -> Unit,
    onJournalCreated: (Uuid) -> Unit,
) {
    taggedEntry<JournalCreationRoute> {
        JournalCreationScreen(
            onGoBack = onBack,
            onJournalCreated = onJournalCreated,
        )
    }
}
