package app.logdate.client

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.client.ui.navigation.SearchRoute
import app.logdate.feature.core.watch.continuity.WatchHandoffRoute
import app.logdate.feature.events.navigation.EventDetailRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.postcards.navigation.PostcardViewerRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.navigation.TimelineDetailRoute
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class DeepLinkResolverTest {
    @Test
    fun `custom scheme journal deep link resolves journal details`() {
        val journalId = Uuid.random()

        assertEquals(
            JournalDetailsRoute(journalId),
            resolveDeepLinkUri(Uri.parse("logdate://journal/$journalId")),
        )
    }

    @Test
    fun `apex day deep link resolves timeline detail`() {
        val date = "2026-06-15"

        assertEquals(
            TimelineDetailRoute(date),
            resolveDeepLinkUri(Uri.parse("https://logdate.app/day/$date")),
        )
    }

    @Test
    fun `tenant note deep link resolves note details`() {
        val noteId = Uuid.random()

        assertEquals(
            NoteDetailRoute(noteId),
            resolveDeepLinkUri(Uri.parse("https://alice.logdate.app/note/$noteId")),
        )
    }

    @Test
    fun `watch playback link resolves the same recording`() {
        val noteId = Uuid.random()

        assertEquals(
            WatchHandoffRoute(noteId.toString(), "play"),
            resolveDeepLinkUri(Uri.parse("studio.hypertext.logdate://handoff/$noteId?target=play")),
        )
    }

    @Test
    fun `custom scheme location deep link resolves location timeline`() {
        assertEquals(
            LocationTimelineRoute,
            resolveDeepLinkUri(Uri.parse("logdate://location")),
        )
    }

    @Test
    fun `custom scheme search deep link resolves a search route`() {
        assertEquals(
            SearchRoute(
                query = "lanterns",
                typeFtsValues = listOf("text_note", "media_caption"),
                dateRangeName = "Today",
            ),
            resolveDeepLinkUri(
                Uri.parse("logdate://search?q=lanterns&type=text_note,media_caption&date=Today"),
            ),
        )
    }

    @Test
    fun `canonical url round trips for route types that support handoff`() {
        val journalId = Uuid.random()
        val timelineDate = "2026-06-15"
        val noteId = Uuid.random()
        val postcardId = Uuid.random()
        val rewindId = Uuid.random()
        val eventId = "event-123"

        val roundTrips =
            listOf(
                JournalDetailsRoute(journalId),
                TimelineDetailRoute(timelineDate),
                NoteDetailRoute(noteId),
                PostcardViewerRoute(postcardId),
                RewindDetailRoute(rewindId),
                EventDetailRoute(eventId),
            )

        roundTrips.forEach { route ->
            val resolved = route.toWebUrl()?.let(Uri::parse)?.let(::resolveDeepLinkUri)
            assertEquals(route, resolved)
        }
    }

    @Test
    fun `unsupported routes do not have canonical handoff urls`() {
        assertNull(SearchRoute().toWebUrl())
        assertNull(LocationTimelineRoute.toWebUrl())
    }
}
