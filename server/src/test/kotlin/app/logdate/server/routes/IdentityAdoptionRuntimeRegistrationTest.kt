package app.logdate.server.routes

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.di.installServerKoin
import app.logdate.server.identity.adoption.IdentityAdoptionMaintenance
import app.logdate.server.identity.adoption.IdentityAdoptionRuntime
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityAdoptionRuntimeRegistrationTest {
    @Test fun enabledRuntimeMountsEveryProtectedSurfaceAndAdvertisesExactNativeFeature() =
        testApplication {
            val runtime =
                IdentityAdoptionRuntime(
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    IdentityAdoptionMaintenance({ emptyList() }, { true }),
                )
            application {
                installServerKoin(false) { null }
                getKoin().loadModules(
                    listOf(
                        module {
                            single { runtime }
                            single { ServerDescriptorConfig(encryptedAccountKeysEnabled = true, personalSpaceEnabled = true) }
                        },
                    ),
                )
                install(ContentNegotiation) { json() }
                accountApiRoutes()
            }
            try {
                val prefix = "/api/v1/account/identity-adoption"
                assertEquals(HttpStatusCode.Unauthorized, client.post("$prefix/begin").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("$prefix/11111111-1111-4111-8111-111111111111").status)
                for (route in listOf("begin-private-blob", "verify-private-blob", "discard-private-blob", "read-source-media")) {
                    assertEquals(HttpStatusCode.Unauthorized, client.post("$prefix/11111111-1111-4111-8111-111111111111/$route").status)
                }
                assertTrue(client.get("/api/v1/server/info").bodyAsText().contains("identityAdoptionV1"))
            } finally {
                runtime.close()
            }
        }

    @Test fun absentRuntimeLeavesDiscoveryAndRoutesDisabled() =
        testApplication {
            application {
                installServerKoin(false) { null }
                install(ContentNegotiation) { json() }
                accountApiRoutes()
            }
            assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/account/identity-adoption/begin").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/account/identity-adoption/current").status)
            assertFalse(client.get("/api/v1/server/info").bodyAsText().contains("identityAdoptionV1"))
        }
}
