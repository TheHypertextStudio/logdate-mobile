package app.logdate.feature.library.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.library.ui.LibraryScreen
import app.logdate.feature.library.ui.detail.MediaDetailScreen
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data object LibraryOverviewRoute : NavKey

@Serializable
data class MediaDetailRoute(
    val id: String,
) : NavKey {
    constructor(id: Uuid) : this(id.toString())
}

/** Pushes the Library overview onto the back stack. */
fun NavBackStack<NavKey>.navigateToLibrary() {
    add(LibraryOverviewRoute)
}

/** Pushes the Media detail viewer onto the back stack. */
fun NavBackStack<NavKey>.navigateToMediaDetail(mediaId: Uuid) {
    add(MediaDetailRoute(mediaId))
}

/** Registers the Library overview entry. */
fun EntryProviderScope<NavKey>.libraryOverviewEntry(onOpenMediaDetail: (Uuid) -> Unit) {
    taggedEntry<LibraryOverviewRoute>(metadata = ActivityPresentation.Collection("library").asMetadata()) {
        LibraryScreen(onOpenMediaDetail = onOpenMediaDetail)
    }
}

/** Registers the Media detail entry. */
fun EntryProviderScope<NavKey>.mediaDetailEntry(onBack: () -> Unit) {
    taggedEntry<MediaDetailRoute>(metadata = ActivityPresentation.Detail("library").asMetadata()) { route ->
        MediaDetailScreen(
            mediaId = Uuid.parse(route.id),
            onBack = onBack,
        )
    }
}

/** Convenience to register both Library entries at once. */
fun EntryProviderScope<NavKey>.libraryEntries(
    onOpenMediaDetail: (Uuid) -> Unit,
    onBack: () -> Unit,
) {
    libraryOverviewEntry(onOpenMediaDetail = onOpenMediaDetail)
    mediaDetailEntry(onBack = onBack)
}
