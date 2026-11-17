package app.logdate.wear.v2.navigation

import app.logdate.wear.launch.WearLaunchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class WearRouteTest {
    @Test
    fun `every route survives being saved and restored`() {
        val routes =
            listOf(
                WearRoute.Home,
                WearRoute.Flow,
                WearRoute.Memories,
                WearRoute.Settings,
                WearRoute.Moment(id = Uuid.random().toString()),
                WearRoute.CaptureSheet,
                WearRoute.Archive,
                WearRoute.PhoneCamera,
            )

        routes.forEach { route ->
            val saved = Json.encodeToString(serializer<WearRoute>(), route)
            assertEquals(route, Json.decodeFromString(serializer<WearRoute>(), saved))
        }
    }

    @Test
    fun `capture launch requests still open Today`() {
        val requests =
            listOf(
                null,
                WearLaunchRequest.Home,
                WearLaunchRequest.Record,
                WearLaunchRequest.MoodCheckIn,
                WearLaunchRequest.QuickText,
                WearLaunchRequest.RemoteCamera,
                WearLaunchRequest.Mark,
            )

        requests.forEach { request ->
            assertEquals(listOf<WearRoute>(WearRoute.Home), request.toV2BackStack(), "for $request")
        }
    }

    @Test
    fun `library and settings launch requests open their own screens`() {
        assertEquals(listOf(WearRoute.Home, WearRoute.Flow), WearLaunchRequest.Timeline.toV2BackStack())
        assertEquals(listOf(WearRoute.Home, WearRoute.Memories), WearLaunchRequest.Memories.toV2BackStack())
        assertEquals(listOf(WearRoute.Home, WearRoute.Memories), WearLaunchRequest.OnThisDay.toV2BackStack())
        assertEquals(listOf(WearRoute.Home, WearRoute.Settings), WearLaunchRequest.Settings.toV2BackStack())
    }

    @Test
    fun `a moment link opens the moment above Today`() {
        val noteId = Uuid.random()

        assertEquals(
            listOf(WearRoute.Home, WearRoute.Moment(noteId.toString())),
            WearLaunchRequest.Moment(noteId).toV2BackStack(),
        )
    }
}
