package app.logdate.feature.events.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.events.ui.EventDetailScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class EventDetailRoute(
    val eventId: String,
) : NavKey {
    constructor(eventId: Uuid) : this(eventId.toString())
}

/** Registers the event detail entry. */
fun EntryProviderScope<NavKey>.eventDetailEntry(onGoBack: () -> Unit) {
    taggedEntry<EventDetailRoute> { route ->
        EventDetailScreen(
            eventId = Uuid.parse(route.eventId),
            onGoBack = onGoBack,
        )
    }
}
