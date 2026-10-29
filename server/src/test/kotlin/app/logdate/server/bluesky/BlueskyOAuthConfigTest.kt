package app.logdate.server.bluesky

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.oauth.client.JsonWebKeySet
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlueskyOAuthConfigTest {
    @Test
    fun `disabled defaults do not require keys or permit accidental activation`() {
        assertNull(BlueskyOAuthConfig.fromEnvironment { null })
        assertNull(BlueskyOAuthConfig.fromEnvironment(mapOf("LOGDATE_BLUESKY_SIGN_IN_ENABLED" to "false")::get))
        assertFailsWith<IllegalArgumentException> {
            BlueskyOAuthConfig.fromEnvironment(mapOf("LOGDATE_BLUESKY_SIGN_IN_ENABLED" to "treu")::get)
        }
    }

    @Test
    fun `enabled configuration requires shared storage stable keys and an explicit encryption key`() {
        for (missing in listOf("DATABASE_URL", "ATPROTO_SIGNING_KEY_KEK", "BLUESKY_OAUTH_CLIENT_JWKS", "LOGDATE_PUBLIC_ORIGIN")) {
            assertFailsWith<IllegalArgumentException>(missing) {
                BlueskyOAuthConfig.fromEnvironment(environment().minus(missing)::get)
            }
        }
    }

    @Test
    fun `enabled sign in refuses missing or disabled personal journal prerequisites`() {
        for (key in listOf("LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED", "LOGDATE_PERSONAL_SPACE_ENABLED")) {
            val base = environment().minus(key)
            assertFailsWith<IllegalArgumentException>(key) { BlueskyOAuthConfig.fromEnvironment(base::get) }
            assertFailsWith<IllegalArgumentException>(key) { BlueskyOAuthConfig.fromEnvironment(base.plus(key to "false")::get) }
        }
    }

    @Test
    fun `metadata uses the environment origin minimum scope and only public keys`() {
        val environment = environment()
        val config = assertNotNull(BlueskyOAuthConfig.fromEnvironment(environment::get))
        assertEquals("https://cloud.logdate.app/oauth/client-metadata.json", config.clientId)
        assertEquals(listOf("https://cloud.logdate.app/oauth/bluesky/callback"), config.redirectUris)
        assertEquals("atproto", config.scope)
        val metadata = config.toClientMetadata()
        assertFalse(assertNotNull(metadata.jwks).keys.any { it.isPrivate })
        val staged =
            assertNotNull(
                BlueskyOAuthConfig.fromEnvironment(
                    environment.plus(
                        "LOGDATE_PUBLIC_ORIGIN" to "https://staging.logdate.app",
                    )::get,
                ),
            )
        assertEquals("https://staging.logdate.app/oauth/client-metadata.json", staged.clientId)
    }

    @Test
    fun `unsafe origins and malformed keys fail without retaining secret input`() {
        for (origin in listOf(
            "http://cloud.logdate.app",
            "https://cloud.logdate.app/path",
            "https://user@cloud.logdate.app",
            "https://cloud.logdate.app?x=y",
            "https://cloud.logdate.app:8443",
            "https://localhost",
        )) {
            assertFailsWith<IllegalArgumentException>(origin) {
                BlueskyOAuthConfig.fromEnvironment(environment().plus("LOGDATE_PUBLIC_ORIGIN" to origin)::get)
            }
        }
        val marker = "private-key-marker"
        val exception =
            assertFailsWith<IllegalArgumentException> {
                BlueskyOAuthConfig.fromEnvironment(environment().plus("BLUESKY_OAUTH_CLIENT_JWKS" to "{\"keys\":[$marker]}")::get)
            }
        assertFalse(exception.toString().contains(marker))
        assertNull(exception.cause)
    }

    @Test
    fun `existing PostgreSQL and Cloud SQL connection configurations are supported`() {
        val base = environment()
        assertNotNull(BlueskyOAuthConfig.fromEnvironment(base.plus("DATABASE_URL" to "postgresql://localhost/test")::get))
        assertNotNull(
            BlueskyOAuthConfig.fromEnvironment(
                base.minus("DATABASE_URL").plus(
                    "INSTANCE_CONNECTION_NAME" to "test:region:instance",
                )::get,
            ),
        )
    }

    @Test
    fun `client keys must be usable matching key pairs`() {
        val first = JvmOAuthCrypto.generateClientKey("one").privateJwk
        val other = JvmOAuthCrypto.generateClientKey("other").privateJwk
        for (key in listOf(first.copy(d = ""), first.copy(d = other.d), first.copy(x = "invalid"))) {
            val encoded = Json.encodeToString(JsonWebKeySet(listOf(key)))
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    BlueskyOAuthConfig.fromEnvironment(environment().plus("BLUESKY_OAUTH_CLIENT_JWKS" to encoded)::get)
                }
            assertNull(exception.cause)
            assertFalse(exception.toString().contains(encoded))
        }
    }

    private fun environment(): Map<String, String> =
        mapOf(
            "LOGDATE_BLUESKY_SIGN_IN_ENABLED" to "true",
            "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED" to "true",
            "LOGDATE_PERSONAL_SPACE_ENABLED" to "true",
            "LOGDATE_PUBLIC_ORIGIN" to "https://cloud.logdate.app",
            "DATABASE_URL" to "jdbc:postgresql://localhost/test",
            "ATPROTO_SIGNING_KEY_KEK" to "test-only-explicit-encryption-key-value",
            "BLUESKY_OAUTH_CLIENT_JWKS" to
                Json.encodeToString(JsonWebKeySet(listOf(JvmOAuthCrypto.generateClientKey("test-client").privateJwk))),
        )
}
