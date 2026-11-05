@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.auth.TokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.di.installServerKoin
import app.logdate.server.routes.identityAdoptionApiRoutes
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IdentityAdoptionCurrentReceiptRoutesPostgresTest {
    @Test fun anotherFullSessionCanDiscoverTheRealCommittedReceiptWithoutAnOperationId() = exercise(Scenario.COMMITTED)

    @Test fun aRetiredDidSessionCanRecoverTheAccountReceipt() = exercise(Scenario.OLD_TOKEN)

    @Test fun disablingNewAdoptionDoesNotStrandCommittedDeviceRecovery() = exercise(Scenario.DISABLED)

    @Test fun disablingNewAdoptionKeepsTheProductionReceiptRoute() = exercise(Scenario.DISABLED_RUNTIME)

    @Test fun anUncommittedOperationIsNotExposedAsCurrentAuthority() = exercise(Scenario.PREPARING)

    @Test fun anUnrelatedAccountCannotDiscoverTheReceipt() = exercise(Scenario.FOREIGN)

    @Test fun linkedDeviceCredentialsCannotDiscoverAccountAuthority() = exercise(Scenario.LINKED)

    @Test fun anonymousRequestsCannotDiscoverTheReceipt() = exercise(Scenario.ANONYMOUS)

    @Test fun historicalReceiptsCannotReplaceANewerAccountEpoch() = exercise(Scenario.NEWER_EPOCH)

    private enum class Scenario { COMMITTED, OLD_TOKEN, DISABLED, DISABLED_RUNTIME, PREPARING, FOREIGN, LINKED, ANONYMOUS, NEWER_EPOCH }

    private fun exercise(scenario: Scenario) =
        withAdoptionCommit {
            val tokens = JwtTokenService("synthetic-current-receipt-secret")
            val accountId = operation.binding.accountId
            val old = tokens.generateAccessToken(accountId.toString(), operation.binding.sourceDid)
            val receipt = if (scenario == Scenario.PREPARING) null else assertNotNull(commit())
            val accounts = PostgreSQLAccountRepository()
            if (scenario == Scenario.NEWER_EPOCH) {
                auth.source.blueskyTransaction {
                    prepareStatement("UPDATE accounts SET identity_epoch=identity_epoch+1 WHERE id=?").use {
                        it.setObject(1, accountId.toJavaUUID())
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            val before = assertNotNull(accounts.findById(accountId))
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.OLD_TOKEN -> old
                    Scenario.FOREIGN -> {
                        val foreign = createTestAccount(Uuid.random().toJavaUUID(), "did:web:foreign.example")
                        tokens.generateAccessToken(foreign.id.toString(), foreign.did)
                    }
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(accountId.toString(), before.did, "second-device")
                    else -> tokens.generateAccessToken(accountId.toString(), before.did)
                }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    if (scenario == Scenario.DISABLED_RUNTIME) {
                        installServerKoin(true, adoptionRuntimeFactory = { _, _, _ -> null }, blueskyRuntimeFactory = { null })
                        getKoin().loadModules(
                            listOf(
                                module {
                                    single<TokenService> { tokens }
                                    single<AccountRepository> { accounts }
                                },
                            ),
                        )
                        identityAdoptionApiRoutes()
                    } else {
                        routing {
                            route("/api/v1") {
                                identityAdoptionRoutes(
                                    accounts,
                                    tokens,
                                    PostgreSQLIdentityAdoptionRepository(auth.source),
                                    AuthMetricsRegistry(),
                                    enabled = scenario != Scenario.DISABLED,
                                )
                            }
                        }
                    }
                }
                val response = client.get("/api/v1/account/identity-adoption/current") { if (token != null) bearerAuth(token) }
                if (scenario == Scenario.DISABLED_RUNTIME) {
                    assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/account/identity-adoption/begin").status)
                }
                val expected =
                    when (scenario) {
                        Scenario.COMMITTED, Scenario.OLD_TOKEN, Scenario.DISABLED, Scenario.DISABLED_RUNTIME -> HttpStatusCode.OK
                        Scenario.PREPARING, Scenario.FOREIGN, Scenario.NEWER_EPOCH -> HttpStatusCode.NotFound
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                    }
                assertEquals(expected, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = response.bodyAsText()
                if (expected == HttpStatusCode.OK) {
                    val status = Json.decodeFromString<IdentityAdoptionStatusResponse>(body)
                    assertEquals(IdentityAdoptionPhaseView.COMMITTED, status.data.operation.phase)
                    assertEquals(operation.id, status.data.operation.id)
                    assertEquals(accountId.toString(), status.data.operation.binding.accountId)
                    val observed = assertNotNull(status.data.receipt)
                    assertEquals(receipt?.operationId, observed.operationId)
                    assertEquals(receipt?.targetDid, observed.targetDid)
                    assertEquals(receipt?.identityEpoch, observed.identityEpoch)
                    assertEquals(receipt?.committedAt.toString(), observed.committedAt)
                    assertNull(status.data.authorization)
                } else {
                    assertFalse(body.contains(operation.id))
                    assertFalse(body.contains(operation.binding.sourceDid))
                    assertFalse(body.contains(operation.binding.targetDid))
                }
                assertEquals(before, accounts.findById(accountId))
                assertEquals(receipt, auth.operations.receipt(accountId, operation.id))
            }
        }
}
