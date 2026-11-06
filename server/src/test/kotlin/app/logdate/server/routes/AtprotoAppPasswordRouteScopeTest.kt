package app.logdate.server.routes

import app.logdate.server.auth.Account
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtprotoAppPasswordRouteScopeTest {
    @Test
    fun `app password principal only receives service auth for approved Bluesky methods`() {
        val account = mockk<Account>()
        val principal = XrpcPrincipal(account, oauth = null, appPassword = true)
        assertTrue(principal.mayUseServiceMethod("app.bsky.feed.getTimeline"))
        assertFalse(principal.mayUseServiceMethod("app.logdate.journal.list"))
        assertFalse(principal.mayUseServiceMethod("com.atproto.repo.getRecord"))
        assertFalse(principal.mayUseServiceMethod("com.atproto.server.createAppPassword"))
        assertFalse(principal.mayUseServiceMethod("chat.bsky.convo.listConvos"))
    }
}
