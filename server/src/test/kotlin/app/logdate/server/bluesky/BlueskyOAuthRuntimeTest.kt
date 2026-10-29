package app.logdate.server.bluesky

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.oauth.client.JsonWebKeySet
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueskyOAuthRuntimeTest {
    @Test
    fun `connection lifecycle runtime is enabled only by its explicit flag`() {
        for (enabled in listOf(false, true)) {
            val env = environment() + ("LOGDATE_ATPROTO_CONNECTION_ENABLED" to enabled.toString())
            val locks = HikariDataSource()
            val http = HttpClient(MockEngine { respond("unused") })
            val runtime = assertNotNull(createBlueskyOAuthRuntime(true, env::get, { mockk() }, { locks }, { http }, {}, {}))
            try {
                if (enabled) assertNotNull(runtime.connection) else assertNull(runtime.connection)
            } finally {
                runtime.close()
            }
        }
    }

    @Test
    fun `disabled configuration allocates no resources and needs no database`() {
        assertNull(
            createBlueskyOAuthRuntime(false, { null }, { error("source accessed") }, { error("lock created") }, { error("HTTP created") }),
        )
    }

    @Test
    fun `enabled configuration refuses fallback storage before allocating resources`() {
        val env = environment()
        assertFailsWith<IllegalArgumentException> {
            createBlueskyOAuthRuntime(false, env::get, { error("source accessed") }, { error("lock created") }, { error("HTTP created") })
        }
        assertFailsWith<IllegalArgumentException> {
            createBlueskyOAuthRuntime(true, env::get, { null }, { error("lock created") }, { error("HTTP created") })
        }
    }

    @Test
    fun `schema failure never opens HTTP or a lock pool`() {
        val env = environment()
        assertFailsWith<IllegalStateException> {
            createBlueskyOAuthRuntime(
                true,
                env::get,
                { mockk() },
                { error("lock created") },
                { error("HTTP created") },
                { error("schema missing") },
            )
        }
    }

    @Test
    fun `runtime owns HTTP and the bounded lock pool but not the application database`() =
        runBlocking<Unit> {
            val env = environment()
            val source = mockk<DataSource>()
            val locks = HikariDataSource()
            val http = HttpClient(MockEngine { respond("unused") })
            val runtime =
                assertNotNull(createBlueskyOAuthRuntime(true, env::get, { source }, { locks }, { http }, { assertSame(source, it) }))
            try {
                assertEquals(4, locks.maximumPoolSize)
                assertEquals(5000L, locks.connectionTimeout)
                assertEquals("atproto", runtime.client.clientMetadata.scope)
                assertTrue(
                    runtime.client.clientMetadata.jwks!!
                        .keys
                        .none { it.isPrivate },
                )
                verify(exactly = 0) { source.connection }
            } finally {
                runtime.close()
                runtime.close()
            }
            assertTrue(locks.isClosed)
            withTimeout(2000) { http.coroutineContext[Job]!!.join() }
        }

    @Test
    fun `partial construction closes the lock pool when HTTP setup fails`() {
        val env = environment()
        val locks = HikariDataSource()
        assertFailsWith<IllegalStateException> {
            createBlueskyOAuthRuntime(true, env::get, { mockk() }, { locks }, { error("HTTP unavailable") }, {})
        }
        assertTrue(locks.isClosed)
    }

    private fun environment() =
        mapOf(
            "LOGDATE_BLUESKY_SIGN_IN_ENABLED" to "true",
            "LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED" to "true",
            "LOGDATE_PERSONAL_SPACE_ENABLED" to "true",
            "LOGDATE_PUBLIC_ORIGIN" to "https://cloud.logdate.app",
            "DATABASE_URL" to "jdbc:postgresql://localhost/fixture",
            "ATPROTO_SIGNING_KEY_KEK" to "fixture-only-explicit-encryption-key-123456789",
            "BLUESKY_OAUTH_CLIENT_JWKS" to
                Json.encodeToString(JsonWebKeySet(listOf(JvmOAuthCrypto.generateClientKey("fixture-key").privateJwk))),
        )
}
