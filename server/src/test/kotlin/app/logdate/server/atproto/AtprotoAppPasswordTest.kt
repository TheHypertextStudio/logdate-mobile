package app.logdate.server.atproto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtprotoAppPasswordTest {
    @Test
    fun `generated credentials use the compatible format and hide their value in diagnostics`() {
        val password = AtprotoAppPassword.generate()
        assertTrue(Regex("[a-z2-7]{4}(-[a-z2-7]{4}){3}").matches(password.value))
        assertEquals(password.verifier, AtprotoAppPassword.verifier(password.value))
        assertFalse(password.toString().contains(password.value))
        assertNotEquals(password.verifier, AtprotoAppPassword.generate().verifier)
    }

    @Test
    fun `ordinary passwords are rejected before credential lookup`() {
        assertNull(AtprotoAppPassword.verifier("account-password"))
        assertNull(AtprotoAppPassword.verifier("abcd-efgh-ijkl-mn0p"))
    }
}
