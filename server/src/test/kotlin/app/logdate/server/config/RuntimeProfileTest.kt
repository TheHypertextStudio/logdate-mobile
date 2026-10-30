package app.logdate.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins how `LOGDATE_ENV` selects the server's profile. A value the server doesn't recognise, such
 * as a typo of `production`, must stop startup rather than quietly select the permissive
 * development profile.
 */
class RuntimeProfileTest {
    private fun profileFor(value: String?) = RuntimeProfile.fromEnvironment { name -> value.takeIf { name == RuntimeProfile.ENV_VAR } }

    @Test
    fun `known names select their profile`() {
        assertEquals(RuntimeProfile.PRODUCTION, profileFor("production"))
        assertEquals(RuntimeProfile.PRODUCTION, profileFor(" Prod "))
        assertEquals(RuntimeProfile.TEST, profileFor("test"))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("development"))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("dev"))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("local"))
    }

    @Test
    fun `an unset or blank value selects development for local runs`() {
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor(null))
        assertEquals(RuntimeProfile.DEVELOPMENT, profileFor("   "))
    }

    @Test
    fun `an unrecognised value stops startup instead of selecting development`() {
        listOf("prodution", "staging", "live", "true").forEach { value ->
            val error = assertFailsWith<IllegalStateException>(value) { profileFor(value) }
            assertTrue(error.message.orEmpty().contains(RuntimeProfile.ENV_VAR), error.message)
        }
    }

    @Test
    fun `an unrecognised profile also stops profile-aware settings from choosing a default`() {
        assertFailsWith<IllegalStateException> {
            profileAwareBoolEnv(
                name = "REQUIRE_HTTPS",
                productionDefault = true,
                devDefault = false,
                readEnv = { name -> if (name == RuntimeProfile.ENV_VAR) "prodution" else null },
            )
        }
    }
}
