package app.logdate.client.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class LogdateHostClassifierTest {
    @Test
    fun `apex host is classified as Apex`() {
        assertEquals(
            LogdateHostClass.Apex,
            classifyLogdateHost("logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `tenant subdomain returns the leftmost label as the handle`() {
        assertEquals(
            LogdateHostClass.TenantClaimed("alice"),
            classifyLogdateHost("alice.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `tenant subdomain accepts hyphens within the label`() {
        assertEquals(
            LogdateHostClass.TenantClaimed("multi-word-handle"),
            classifyLogdateHost("multi-word-handle.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `reserved subdomain app falls through to Other`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("app.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `reserved subdomain api falls through to Other`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("api.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `multi-level subdomain is rejected`() {
        // `a.b.logdate.app` shouldn't be claimed — the wildcard
        // `applinks:*.logdate.app` covers single-label subdomains, and
        // ATProto handles are themselves DNS-shaped single labels under
        // the apex.
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("a.b.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `empty subdomain label is rejected`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost(".logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `label starting with a hyphen is rejected`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("-handle.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `label ending with a hyphen is rejected`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("handle-.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `label with disallowed characters is rejected`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("ben_franklin.logdate.app", "logdate.app"),
        )
    }

    @Test
    fun `unrelated host is Other`() {
        assertEquals(
            LogdateHostClass.Other,
            classifyLogdateHost("example.com", "logdate.app"),
        )
    }
}
