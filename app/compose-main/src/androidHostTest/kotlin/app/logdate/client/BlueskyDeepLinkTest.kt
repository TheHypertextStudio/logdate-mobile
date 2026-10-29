package app.logdate.client

import android.content.Intent
import android.net.Uri
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.shared.model.BlueskyBrowserReturn
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class BlueskyDeepLinkTest {
    @Test
    fun `both browser returns reach the account flow through the real activity resolver`() {
        val id = "a".repeat(43)
        for (base in listOf(BlueskyBrowserReturn.URL, BlueskyBrowserReturn.NATIVE_URL)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$base?attempt=$id"))
            assertEquals(BlueskySignInRoute(id), resolveMainActivityNavKey(intent))
            assertEquals(BlueskySignInRoute(id), resolveDeepLinkUri(intent.data!!))
        }
    }

    @Test
    fun `foreign or malformed authentication returns never become navigation destinations`() {
        val id = "a".repeat(43)
        for (url in listOf(
            "${BlueskyBrowserReturn.URL}?attempt=$id&token=private-marker",
            "${BlueskyBrowserReturn.URL}?attempt=$id#fragment",
            "${BlueskyBrowserReturn.URL}?attempt=short",
            "https://other.logdate.app/auth/bluesky/return?attempt=$id",
            "http://app.logdate.app/auth/bluesky/return?attempt=$id",
        )) {
            assertNull(resolveMainActivityNavKey(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
        }
    }
}
