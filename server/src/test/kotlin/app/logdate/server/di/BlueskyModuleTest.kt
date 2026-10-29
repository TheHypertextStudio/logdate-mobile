package app.logdate.server.di

import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.BlueskySignInService
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.AtprotoConnectionRuntime
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import app.logdate.server.passkeys.WebAuthnPasskeyService
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class BlueskyModuleTest {
    @Test
    fun `enabled connection runtime supplies one service using application credential coordination`() {
        val runtime = mockk<BlueskyOAuthRuntime>()
        every { runtime.signIn } returns mockk()
        every { runtime.connection } returns AtprotoConnectionRuntime(mockk(), mockk(), mockk(), mockk())
        val app =
            koinApplication {
                modules(
                    blueskyModule(runtime),
                    module {
                        single<AccountRepository> { mockk() }
                        single<AccountIdentityRepository> { mockk() }
                        single<GoogleIdTokenVerifier> { mockk() }
                        single { WebAuthnPasskeyService() }
                        single<AccountCredentialMutationLock> { InProcessAccountCredentialMutationLock() }
                    },
                )
            }
        try {
            val service = kotlin.test.assertNotNull(app.koin.getOrNull<AtprotoConnectionService>())
            assertSame(service, app.koin.get<AtprotoConnectionService>())
            kotlin.test.assertNotNull(app.koin.getOrNull<AtprotoReconnectService>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `credential coordination is shared even when external sign in is disabled`() {
        testApplication {
            application {
                installServerKoin(false)
                routing {
                    get("/coordination") {
                        val first = getKoin().getOrNull<AccountCredentialMutationLock>()
                        kotlin.test.assertNotNull(first)
                        assertSame(first, getKoin().get<AccountCredentialMutationLock>())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            assertEquals(HttpStatusCode.NoContent, client.get("/coordination").status)
        }
    }

    @Test
    fun `disabled runtime contributes no sign-in bindings`() {
        val app = koinApplication { modules(blueskyModule(null)) }
        try {
            assertNull(app.koin.getOrNull<BlueskyOAuthRuntime>())
            assertNull(app.koin.getOrNull<BlueskySignInService>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `enabled module supplies the single application-owned service`() {
        val runtime = mockk<BlueskyOAuthRuntime>()
        val service = mockk<BlueskySignInService>()
        every { runtime.signIn } returns service
        every { runtime.connection } returns null
        val app = koinApplication { modules(blueskyModule(runtime)) }
        try {
            assertSame(runtime, app.koin.get<BlueskyOAuthRuntime>())
            assertSame(service, app.koin.get<BlueskySignInService>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `the application closes its runtime when the server stops`() {
        val runtime = mockk<BlueskyOAuthRuntime>(relaxed = true)
        testApplication {
            application {
                installServerKoin(false) { available ->
                    assertFalse(available)
                    runtime
                }
                routing { get("/ready") { call.respond(HttpStatusCode.NoContent) } }
            }
            assertEquals(HttpStatusCode.NoContent, client.get("/ready").status)
        }
        verify(exactly = 1) { runtime.close() }
    }
}
