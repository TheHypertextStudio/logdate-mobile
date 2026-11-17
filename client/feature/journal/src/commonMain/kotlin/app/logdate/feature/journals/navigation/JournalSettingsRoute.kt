package app.logdate.feature.journals.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.journals.ui.settings.JournalSettingsScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class JournalSettingsRoute(
    val journalId: String,
) : NavKey {
    constructor(journalId: Uuid) : this(journalId.toString())
}

/** Registers the journal settings entry. */
fun EntryProviderScope<NavKey>.journalSettingsEntry(
    onBack: () -> Unit,
    onJournalDeleted: () -> Unit,
) {
    taggedEntry<JournalSettingsRoute> { route ->
        JournalSettingsScreen(
            journalId = Uuid.parse(route.journalId),
            onGoBack = onBack,
            onJournalDeleted = onJournalDeleted,
        )
    }
}
