@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.crypto.EncryptionService
import app.logdate.server.di.installServerKoin
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.routes.accountApiRoutes
import app.logdate.server.transfer.TransferSlots
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class IdentityAdoptionMaintenancePostgresTest {
    @Test fun mountedWorkerRetiresCanceledPrivateBytesAndReleasesRealAccountQuota() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            val path = assertNotNull(row()).path
            transition(IdentityAdoptionPhase.CANCELED)
            requireIdentityAdoptionSchema(auth.source)
            val slots = TransferSlots()
            val runtime =
                composeIdentityAdoptionRuntime(
                    auth.source,
                    mockk(),
                    mockk(),
                    auth.web,
                    "https://logdate.app",
                    storage,
                    { limit },
                    EncryptionService.fromEnvironment(),
                    slots,
                    auth.clock,
                )
            val maintenance = runtime.maintenance
            assertSame(slots, assertNotNull(runtime.copyAccess.privateMedia).transfers)
            try {
                testApplication {
                    application {
                        installServerKoin(true, adoptionRuntimeFactory = { _, _, _ -> runtime }) { null }
                        install(ContentNegotiation) { json() }
                        accountApiRoutes()
                    }
                    assertEquals(HttpStatusCode.OK, client.get("/api/v1/server/info").status)
                    withTimeout(3000) { while (row()?.phase != "RETIRED") delay(10) }
                    assertEquals(0L, used())
                    assertNull(storage.openBlob(path))
                }
                assertFailsWith<IllegalStateException> { maintenance.start() }
            } finally {
                maintenance.close()
            }
        }
}
