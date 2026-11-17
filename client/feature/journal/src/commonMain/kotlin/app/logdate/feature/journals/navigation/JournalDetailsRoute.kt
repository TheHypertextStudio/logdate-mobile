package app.logdate.feature.journals.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.feature.journals.ui.detail.JournalDetailScreen
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Serializable
data class JournalDetailsRoute(
    val journalId: String,
    val mergedSourceTitle: String? = null,
) : NavKey {
    constructor(journalId: Uuid) : this(journalId.toString())
}

/** Registers the journal detail entry. */
fun EntryProviderScope<NavKey>.journalDetailsEntry(
    onBack: () -> Unit,
    onJournalDeleted: () -> Unit,
    onOpenNote: (noteId: Uuid, journalId: Uuid) -> Unit,
    onOpenEditor: (Uuid) -> Unit = {},
    onOpenContentPicker: (Uuid) -> Unit = {},
    onOpenSettings: (Uuid) -> Unit = {},
    onShareJournal: (Uuid) -> Unit = {},
    onMergeJournal: (Uuid) -> Unit = {},
    onJournalRedirected: (sourceId: Uuid, destinationId: Uuid) -> Unit = { _, _ -> },
) {
    taggedEntry<JournalDetailsRoute>(metadata = { route ->
        ActivityPresentation.Detail("journals", childContext = "journal:${route.journalId}").asMetadata()
    }) { route ->
        val journalRepository: JournalRepository = koinInject()
        val requestedId = Uuid.parse(route.journalId)
        val resolvedId by produceState<Uuid?>(null, requestedId, journalRepository) {
            journalRepository.observeJournalRouteDestination(requestedId).collect { value = it }
        }
        LaunchedEffect(resolvedId) {
            resolvedId?.takeIf { it != requestedId }?.let { onJournalRedirected(requestedId, it) }
        }
        if (resolvedId != requestedId) return@taggedEntry
        JournalDetailScreen(
            journalId = requestedId,
            mergedSourceTitle = route.mergedSourceTitle,
            onNavigateToMerge = onMergeJournal,
            onGoBack = onBack,
            onJournalDeleted = onJournalDeleted,
            onNavigateToNoteDetail = { onOpenNote(it, requestedId) },
            onOpenEditor = onOpenEditor,
            onOpenContentPicker = onOpenContentPicker,
            onNavigateToSettings = onOpenSettings,
            onNavigateToShare = onShareJournal,
        )
    }
}

internal fun JournalRepository.observeJournalRouteDestination(requestedId: Uuid): Flow<Uuid?> =
    allJournalsObserved
        .map { journals ->
            resolveJournalId(requestedId).takeIf { resolved -> resolved == requestedId || journals.any { it.id == resolved } }
        }.distinctUntilChanged()

fun MutableList<NavKey>.redirectJournalDetail(
    sourceId: Uuid,
    destinationId: Uuid,
) {
    val sourceIndex = indexOfLast { it is JournalDetailsRoute && it.journalId == sourceId.toString() }
    if (sourceIndex >= 0) this[sourceIndex] = JournalDetailsRoute(destinationId)
}
