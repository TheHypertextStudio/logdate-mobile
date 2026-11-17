package app.logdate.feature.postcards.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.postcards.ui.CanvasEditorScreen
import app.logdate.feature.postcards.ui.PostcardViewerScreen
import app.logdate.feature.postcards.ui.PostcardsCollectionScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Browse the user's postcard collection (grid of cards). Reachable from the Library tab.
 */
@Serializable
data object PostcardsCollectionRoute : NavKey

/**
 * Open the canvas editor for a new or existing postcard. The optional `postcardId` is read
 * back out of `SavedStateHandle` by `CanvasEditorViewModel` (the property name must match
 * the ViewModel's `savedStateHandle.get<String>("postcardId")` lookup).
 */
@Serializable
data class PostcardEditorRoute(
    val postcardId: String? = null,
) : NavKey {
    constructor(postcardId: Uuid?) : this(postcardId?.toString())
}

@Serializable
data class PostcardViewerRoute(
    val postcardId: String,
) : NavKey {
    constructor(postcardId: Uuid) : this(postcardId.toString())
}

/** Registers the Postcards collection entry. */
fun EntryProviderScope<NavKey>.postcardsCollectionEntry(
    onOpenPostcard: (Uuid) -> Unit,
    onEditPostcard: (Uuid) -> Unit,
    onCreateNew: () -> Unit,
) {
    taggedEntry<PostcardsCollectionRoute> {
        PostcardsCollectionScreen(
            onOpenPostcard = onOpenPostcard,
            onEditPostcard = onEditPostcard,
            onCreateNew = onCreateNew,
        )
    }
}

/**
 * Registers the Postcard editor entry. Platform-specific affordances (`onToggleFullscreen`)
 * stay opt-in via parameter so iOS/desktop pass `null`.
 */
fun EntryProviderScope<NavKey>.postcardEditorEntry(
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    taggedEntry<PostcardEditorRoute> {
        CanvasEditorScreen(
            onNavigateBack = onNavigateBack,
            onSaved = onSaved,
            onToggleFullscreen = onToggleFullscreen,
        )
    }
}

/**
 * Registers the Postcard viewer entry. Platform-specific export hooks (`onSaveToFiles`,
 * `onPrint`) stay opt-in for iOS/desktop.
 */
fun EntryProviderScope<NavKey>.postcardViewerEntry(
    onNavigateBack: () -> Unit,
    onEditPostcard: (Uuid) -> Unit,
    onShareUri: (String) -> Unit = {},
    onSaveToFiles: ((String) -> Unit)? = null,
    onPrint: ((String) -> Unit)? = null,
) {
    taggedEntry<PostcardViewerRoute> {
        PostcardViewerScreen(
            onNavigateBack = onNavigateBack,
            onEditPostcard = onEditPostcard,
            onShareUri = onShareUri,
            onSaveToFiles = onSaveToFiles,
            onPrint = onPrint,
        )
    }
}

/** Convenience to register all three Postcards entries at once. */
fun EntryProviderScope<NavKey>.postcardsEntries(
    onOpenPostcard: (Uuid) -> Unit,
    onEditPostcard: (Uuid) -> Unit,
    onCreateNew: () -> Unit,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
    onShareUri: (String) -> Unit = {},
    onSaveToFiles: ((String) -> Unit)? = null,
    onPrint: ((String) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    postcardsCollectionEntry(
        onOpenPostcard = onOpenPostcard,
        onEditPostcard = onEditPostcard,
        onCreateNew = onCreateNew,
    )
    postcardEditorEntry(
        onNavigateBack = onNavigateBack,
        onSaved = onSaved,
        onToggleFullscreen = onToggleFullscreen,
    )
    postcardViewerEntry(
        onNavigateBack = onNavigateBack,
        onEditPostcard = onEditPostcard,
        onShareUri = onShareUri,
        onSaveToFiles = onSaveToFiles,
        onPrint = onPrint,
    )
}
