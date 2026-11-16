package app.logdate.feature.journals.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.journals.ui.detail.NoteViewerScreen
import app.logdate.ui.adaptive.ActivityRouteFrame
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.ViewModelsPerVisit
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class NoteDetailRoute(
    val noteId: String,
    val journalId: String? = null,
) : NavKey {
    constructor(noteId: Uuid) : this(noteId.toString())
}

/** Pushes the note viewer for the given note id. */
fun MutableList<NavKey>.navigateToNoteDetail(noteId: Uuid) = navigateToNoteDetail(noteId.toString())

fun MutableList<NavKey>.navigateToNoteDetail(noteId: String) {
    val target = NoteDetailRoute(noteId)
    if (lastOrNull() != target) add(target)
}

/** Registers the note viewer entry. */
fun EntryProviderScope<NavKey>.noteDetailEntry(
    onBack: () -> Unit,
    onNavigateToNote: (noteId: Uuid, journalId: Uuid?) -> Unit = { _, _ -> },
    onEditNote: ((Uuid) -> Unit)? = null,
    onOpenLocationTimeline: (() -> Unit)? = null,
) {
    taggedEntry<NoteDetailRoute>(metadata = { route ->
        (route.journalId?.let { ActivityPresentation.Detail("journal:$it") } ?: ActivityPresentation.Replacement).asMetadata() +
            ViewModelsPerVisit
    }) { route ->
        val journalId = route.journalId?.let(Uuid::parse)
        ActivityRouteFrame {
            NoteViewerScreen(
                noteId = Uuid.parse(route.noteId),
                journalId = journalId,
                onGoBack = onBack,
                onEdit = onEditNote,
                onOpenLocationTimeline = onOpenLocationTimeline,
                onNavigateToNote = { onNavigateToNote(it, journalId) },
            )
        }
    }
}

fun MutableList<NavKey>.replaceNoteDetail(
    noteId: Uuid,
    journalId: Uuid?,
) {
    if (lastOrNull() is NoteDetailRoute) this[lastIndex] = NoteDetailRoute(noteId.toString(), journalId?.toString())
}
