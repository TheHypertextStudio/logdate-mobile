package app.logdate.server.routes

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.connection.AtprotoConnectionRuntime
import app.logdate.server.di.installServerKoin
import app.logdate.server.passkeys.WebAuthnPasskeyService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtprotoConnectionRegistrationTest {
    @Test
    fun `application advertises and authenticates its enabled connection routes`() =
        testApplication {
            val runtime = mockk<BlueskyOAuthRuntime>(relaxed = true)
            every { runtime.connection } returns AtprotoConnectionRuntime(mockk(), mockk(), mockk(), mockk())
            application {
                installServerKoin(false) { runtime }
                getKoin().loadModules(
                    listOf(
                        module {
                            single { ServerDescriptorConfig(encryptedAccountKeysEnabled = true, personalSpaceEnabled = true) }
                            single { WebAuthnPasskeyService(strictVerificationEnabled = true) }
                        },
                    ),
                )
                install(ContentNegotiation) { json() }
                accountApiRoutes()
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/atproto/connection").status)
            val info = client.get("/api/v1/server/info")
            assertEquals(HttpStatusCode.OK, info.status)
            assertTrue(info.bodyAsText().contains("atprotoConnectionV1"))
        }
}
