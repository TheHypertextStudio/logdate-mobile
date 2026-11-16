package app.logdate.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeProfileTest {
    private fun profileFor(value: String?): RuntimeProfile =
        RuntimeProfile.fromEnvironment { name ->
            value.takeIf {
                name ==
                    RuntimeProfile.ENV_VAR
            }
        }

    @Test
    fun `production and its short form select production`() {
        assertEquals(RuntimeProfile.PRODUCTION, profileFor("production"))
        assertEquals(RuntimeProfile.PRODUCTION, profileFor("prod"))
    }

    @Test
    fun `an unset or blank value selects development`() {
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor(null))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor(""))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("   "))
    }

    @Test
    fun `development names select development`() {
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("development"))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("dev"))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("local"))
    }

    @Test
    fun `test selects test`() {
        assertEquals(RuntimeProfile.TEST, profileFor("test"))
    }

    @Test
    fun `case and surrounding spaces are ignored`() {
        assertEquals(RuntimeProfile.PRODUCTION, profileFor("  Production "))
    }

    @Test
    fun `a misspelled production value stops startup`() {
        val failure = assertFailsWith<UnknownRuntimeProfileException> { profileFor("prodution") }

        assertTrue("prodution" in failure.message.orEmpty())
        assertTrue(RuntimeProfile.ENV_VAR in failure.message.orEmpty())
    }

    @Test
    fun `an unrecognized environment name stops startup`() {
        assertFailsWith<UnknownRuntimeProfileException> { profileFor("staging") }
    }
}
