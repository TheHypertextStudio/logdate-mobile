package app.logdate.feature.core.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signing in takes the name a person knows. The name alone says where the account lives, so the
 * sign-in step never asks which server to use.
 */
class SignInNameTest {
    @Test
    fun `a bare username is an account on the server the app already uses`() {
        assertEquals(SignInName("alex", server = null), parseSignInName("alex"))
        assertEquals(SignInName("alex", server = null), parseSignInName("  @alex "))
    }

    @Test
    fun `a name with a server after an at sign is an account on that server`() {
        assertEquals(SignInName("alex", "journal.example.com"), parseSignInName("alex@journal.example.com"))
        assertEquals(SignInName("alex", "journal.example.com"), parseSignInName("@alex@Journal.Example.com/"))
    }

    @Test
    fun `a full handle is the username and the server it belongs to`() {
        assertEquals(SignInName("alex", "example.com"), parseSignInName("alex.example.com"))
    }

    @Test
    fun `a name on LogDate's own domain is an account on LogDate Cloud`() {
        assertTrue(parseSignInName("alex@logdate.app").isOnLogDateCloud)
        assertTrue(parseSignInName("alex.logdate.app").isOnLogDateCloud)
        assertFalse(parseSignInName("alex@journal.example.com").isOnLogDateCloud)
        assertFalse(parseSignInName("alex").isOnLogDateCloud)
    }

    @Test
    fun `a name that stops at the separator has no server yet`() {
        assertEquals(SignInName("alex", server = null), parseSignInName("alex@"))
        assertEquals(SignInName("alex", server = null), parseSignInName("alex."))
    }
}
