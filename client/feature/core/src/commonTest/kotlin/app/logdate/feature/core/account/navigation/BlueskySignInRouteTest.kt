package app.logdate.feature.core.account.navigation

import app.logdate.shared.model.BlueskyBrowserReturn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlueskySignInRouteTest {
    @Test
    fun `only exact supported browser returns create a route with the opaque attempt`() {
        val id = "a".repeat(43)
        for (base in listOf(BlueskyBrowserReturn.URL, BlueskyBrowserReturn.NATIVE_URL)) {
            val route = blueskyReturnRoute("$base?attempt=$id")
            assertEquals(BlueskySignInRoute(id), route)
            assertEquals("BlueskySignInRoute()", route.toString())
            assertEquals(route, Json.decodeFromString<BlueskySignInRoute>(Json.encodeToString(route!!)))
            for (suffix in listOf("&token=secret", "#fragment", "&attempt=$id", "%20", "/")) {
                assertNull(blueskyReturnRoute("$base?attempt=$id$suffix"))
            }
        }
        assertNull(blueskyReturnRoute("https://other.logdate.app/auth/bluesky/app-return?attempt=$id"))
        assertNull(blueskyReturnRoute("${BlueskyBrowserReturn.URL}?attempt=short"))
    }
}
