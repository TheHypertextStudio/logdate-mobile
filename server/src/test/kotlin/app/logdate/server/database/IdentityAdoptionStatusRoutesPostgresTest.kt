@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModeration
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionStatus
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionStatusRoutesPostgresTest {
    @Test fun accountStatusUsesTheSharedWireContractWithoutChangingAuthority() = exercise(Scenario.PREPARING)

    @Test fun committedStatusReturnsOnlyTheOriginalDurableReceipt() = exercise(Scenario.COMMITTED)

    @Test fun defaultDisabledStatusDoesNotReadTheRepository() = exercise(Scenario.DISABLED)

    @Test fun anonymousStatusIsRefused() = exercise(Scenario.ANONYMOUS)

    @Test fun anotherAccountCannotDiscoverThePendingIdentity() = exercise(Scenario.FOREIGN_ACCOUNT)

    @Test fun aLinkedDeviceCannotReadTheAccountOperation() = exercise(Scenario.LINKED_DEVICE)

    @Test fun moderatedAccountsCannotReadPendingIdentityData() = exercise(Scenario.MODERATED)

    @Test fun malformedOperationIdentifiersAreRefused() = exercise(Scenario.MALFORMED)

    @Test fun storageFailuresDoNotRevealPrivateDiagnostics() = exercise(Scenario.UNAVAILABLE)

    @Test fun repeatedStatusReadsAreBoundedWithoutChangingTheOperation() = exercise(Scenario.RATE_LIMIT)

    private enum class Scenario {
        PREPARING,
        COMMITTED,
        DISABLED,
        ANONYMOUS,
        FOREIGN_ACCOUNT,
        LINKED_DEVICE,
        MODERATED,
        MALFORMED,
        UNAVAILABLE,
        RATE_LIMIT,
    }

    private fun exercise(scenario: Scenario) =
        withBlueskyOAuthDatabase { source ->
            val fixture = Fixture(source, createOwner(source))
            val operation = fixture.begin()
            if (scenario == Scenario.COMMITTED) fixture.commitMetadata(operation)
            if (scenario == Scenario.MODERATED) {
                source.blueskyTransaction {
                    prepareStatement("UPDATE accounts SET moderation = ? WHERE id = ?").use {
                        it.setString(1, AccountModeration.SUSPENDED.wireValue)
                        it.setObject(2, fixture.owner.id.toJavaUUID())
                        assertEquals(1, it.executeUpdate())
                    }
                }
            }
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.FOREIGN_ACCOUNT -> fixture.tokens.generateAccessToken(createOwner(source).id.toString())
                    Scenario.LINKED_DEVICE ->
                        fixture.tokens.generateDeviceAccessToken(
                            fixture.owner.id.toString(),
                            fixture.owner.did,
                            "linked-device",
                        )
                    else -> fixture.tokens.generateAccessToken(fixture.owner.id.toString())
                }
            val repository: IdentityAdoptionRepository =
                if (scenario in setOf(Scenario.DISABLED, Scenario.UNAVAILABLE)) {
                    object : IdentityAdoptionRepository by fixture.repository {
                        override suspend fun status(
                            accountId: Uuid,
                            operationId: String,
                        ): IdentityAdoptionStatus? = error("Synthetic private storage diagnostic")
                    }
                } else {
                    fixture.repository
                }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        route("/api/v1") {
                            if (scenario == Scenario.DISABLED) {
                                identityAdoptionRoutes(fixture.accounts, fixture.tokens, repository, AuthMetricsRegistry())
                            } else {
                                identityAdoptionRoutes(fixture.accounts, fixture.tokens, repository, AuthMetricsRegistry(), enabled = true)
                            }
                        }
                    }
                }
                val id = if (scenario == Scenario.MALFORMED) "not-a-uuid" else operation.id
                if (scenario == Scenario.RATE_LIMIT) {
                    repeat(10) {
                        val allowed = client.get("/api/v1/account/identity-adoption/$id") { if (token != null) bearerAuth(token) }
                        assertEquals(HttpStatusCode.OK, allowed.status)
                    }
                }
                val response = client.get("/api/v1/account/identity-adoption/$id") { if (token != null) bearerAuth(token) }
                val expected =
                    when (scenario) {
                        Scenario.PREPARING, Scenario.COMMITTED -> HttpStatusCode.OK
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.FOREIGN_ACCOUNT, Scenario.DISABLED -> HttpStatusCode.NotFound
                        Scenario.LINKED_DEVICE, Scenario.MODERATED -> HttpStatusCode.Forbidden
                        Scenario.MALFORMED -> HttpStatusCode.BadRequest
                        Scenario.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                        Scenario.RATE_LIMIT -> HttpStatusCode.TooManyRequests
                    }
                assertEquals(expected, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = response.bodyAsText()
                assertFalse(body.contains("Synthetic private storage diagnostic"))
                if (scenario in setOf(Scenario.PREPARING, Scenario.COMMITTED)) {
                    val decoded = Json.decodeFromString<IdentityAdoptionStatusResponse>(body)
                    assertTrue(decoded.success)
                    assertEquals(operation.id, decoded.data.operation.id)
                    assertEquals(fixture.owner.id.toString(), decoded.data.operation.binding.accountId)
                    assertEquals(operation.binding.sourceDid, decoded.data.operation.binding.sourceDid)
                    assertEquals(operation.binding.targetDid, decoded.data.operation.binding.targetDid)
                    assertEquals(0L, decoded.data.operation.binding.sourceEpoch)
                    assertEquals(operation.binding.deviceId, decoded.data.operation.binding.deviceId)
                    assertEquals(operation.leaseExpiresAt.toString(), decoded.data.operation.leaseExpiresAt)
                    if (scenario == Scenario.COMMITTED) {
                        assertEquals(IdentityAdoptionPhaseView.COMMITTED, decoded.data.operation.phase)
                        assertEquals(1L, assertNotNull(decoded.data.receipt).identityEpoch)
                        assertEquals(operation.id, decoded.data.receipt?.operationId)
                    } else {
                        assertEquals(IdentityAdoptionPhaseView.PREPARING, decoded.data.operation.phase)
                        assertNull(decoded.data.receipt)
                    }
                } else {
                    val code =
                        Json
                            .parseToJsonElement(body)
                            .jsonObject
                            .getValue("error")
                            .jsonObject
                            .getValue("code")
                            .jsonPrimitive.content
                    assertEquals(
                        when (scenario) {
                            Scenario.DISABLED -> "FEATURE_DISABLED"
                            Scenario.ANONYMOUS -> "INVALID_TOKEN"
                            Scenario.FOREIGN_ACCOUNT -> "IDENTITY_ADOPTION_NOT_FOUND"
                            Scenario.LINKED_DEVICE -> "DEVICE_SCOPE_DENIED"
                            Scenario.MODERATED -> "ACCOUNT_UNAVAILABLE"
                            Scenario.MALFORMED -> "VALIDATION_ERROR"
                            Scenario.RATE_LIMIT -> "RATE_LIMIT_EXCEEDED"
                            else -> "IDENTITY_ADOPTION_UNAVAILABLE"
                        },
                        code,
                    )
                    assertFalse(body.contains(operation.binding.sourceDid))
                    assertFalse(body.contains(operation.binding.targetDid))
                }
                assertEquals(operation.binding.sourceDid, fixture.accounts.findById(fixture.owner.id)?.did)
                assertEquals(0L, fixture.accounts.findById(fixture.owner.id)?.identityEpoch)
                assertEquals(
                    if (scenario == Scenario.COMMITTED) operation.revision + 1 else operation.revision,
                    fixture.repository.get(fixture.owner.id, operation.id)?.revision,
                )
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val repository = PostgreSQLIdentityAdoptionRepository(source)
        val tokens = JwtTokenService("synthetic-adoption-status-secret")

        suspend fun begin(): IdentityAdoptionOperation {
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.example", "synthetic-device", 0)
            val outcome = repository.begin(binding, now)
            return assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(outcome).value
        }

        suspend fun commitMetadata(operation: IdentityAdoptionOperation) =
            source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET phase = 'COMMITTED', revision = revision + 1 WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
                prepareStatement(
                    "INSERT INTO identity_adoption_receipts(operation_id, account_id, source_did, target_did, " +
                        "identity_epoch, committed_at) VALUES (?, ?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, operation.id)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setString(4, operation.binding.targetDid)
                    it.setTimestamp(5, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
    }

    private companion object {
        val now = Instant.parse("2026-10-29T00:00:00Z")

        suspend fun createOwner(source: DataSource): Account {
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Synthetic HTTP owner",
                    did = "did:web:source-${id.toHexString()}.example",
                    createdAt = now,
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            return owner
        }
    }
}
