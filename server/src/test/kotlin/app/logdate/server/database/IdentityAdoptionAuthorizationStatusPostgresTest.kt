@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionAuthorizationStatusPostgresTest {
    @Test fun restartedStatusRecoversTheOriginalDeviceAfterPasskeyAndProviderProofsExpire() = exercise(Scenario.RECOVER)

    @Test fun aProposalAloneNeverClaimsDeviceAuthorization() = exercise(Scenario.WAITING)

    @Test fun heldStatusKeepsTheOriginalAuthorizationRevisionWithoutRenewingTheHold() = exercise(Scenario.FENCED)

    @Test fun canceledStatusDoesNotOfferDeviceAuthority() = exercise(Scenario.CANCELED)

    @Test fun anotherAccountCannotReadAuthorizedDeviceMetadata() = exercise(Scenario.FOREIGN)

    @Test fun linkedDevicesCannotReadAuthorizedDeviceMetadata() = exercise(Scenario.LINKED)

    @Test fun anonymousStatusCannotReadAuthorizedDeviceMetadata() = exercise(Scenario.ANONYMOUS)

    @Test fun inconsistentStoredAuthorizationIsRefusedWithoutLeakingItsKey() = exercise(Scenario.BAD_HASH)

    private enum class Scenario { RECOVER, WAITING, FENCED, CANCELED, FOREIGN, LINKED, ANONYMOUS, BAD_HASH }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val fixture = this
            val authorizedAt = clock.now()
            if (scenario == Scenario.BAD_HASH) {
                corruptAuthorization()
            } else if (scenario != Scenario.WAITING) {
                authorize()
            }
            if (scenario == Scenario.FENCED || scenario == Scenario.CANCELED) {
                val action = if (scenario == Scenario.FENCED) IdentityAdoptionAction.HOLD else IdentityAdoptionAction.CANCEL
                val digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                val ticket = assertNotNull(actions.issue(operation, action, digest, clock.now()))
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    operations.transitionWithProof(operation, ticket, actionSignature(ticket), clock.now()),
                )
            }
            if (scenario == Scenario.RECOVER || scenario == Scenario.FENCED) clock.time += 301.seconds
            val owner = operation.binding.accountId
            val before = assertNotNull(operations.get(owner, operation.id))
            val accounts = PostgreSQLAccountRepository()
            val tokens = JwtTokenService("synthetic-authorization-status-secret")
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.FOREIGN -> {
                        val foreign = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
                        tokens.generateAccessToken(foreign.id.toString())
                    }
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(owner.toString(), operation.binding.sourceDid, "linked")
                    else -> tokens.generateAccessToken(owner.toString())
                }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            accounts,
                            tokens,
                            PostgreSQLIdentityAdoptionRepository(fixture.source),
                            AuthMetricsRegistry(),
                            enabled = true,
                        )
                    }
                }
                repeat(2) {
                    val response = client.get("/account/identity-adoption/${operation.id}") { token?.let { bearerAuth(it) } }
                    val expected =
                        when (scenario) {
                            Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                            Scenario.FOREIGN -> HttpStatusCode.NotFound
                            Scenario.LINKED -> HttpStatusCode.Forbidden
                            Scenario.BAD_HASH -> HttpStatusCode.ServiceUnavailable
                            else -> HttpStatusCode.OK
                        }
                    assertEquals(expected, response.status)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    val body = response.bodyAsText()
                    if (expected == HttpStatusCode.OK) {
                        val status = Json.decodeFromString<IdentityAdoptionStatusResponse>(body)
                        assertEquals(before.id, status.data.operation.id)
                        assertEquals(before.revision, status.data.operation.revision)
                        assertEquals(before.leaseExpiresAt.toString(), status.data.operation.leaseExpiresAt)
                        assertNull(status.data.receipt)
                        if (scenario == Scenario.WAITING || scenario == Scenario.CANCELED) {
                            assertNull(status.data.authorization)
                        } else {
                            val authorization = assertNotNull(status.data.authorization)
                            assertEquals(1, authorization.revision)
                            assertEquals(fixture.device.origin, authorization.deviceKey.origin)
                            assertEquals(fixture.device.publicKey, authorization.deviceKey.publicKey)
                            assertEquals(authorizedAt.toString(), authorization.authorizedAt)
                        }
                        assertFalse(body.contains("bindingHash"))
                        assertFalse(body.contains("challengeHash"))
                    } else {
                        assertFalse(body.contains(fixture.device.publicKey))
                        assertFalse(body.contains(operation.binding.sourceDid))
                        assertFalse(body.contains(operation.binding.targetDid))
                    }
                }
            }
            assertEquals(before, operations.get(owner, operation.id))
            assertEquals(operation.binding.sourceDid, accounts.findById(owner)?.did)
            assertEquals(0, accounts.findById(owner)?.identityEpoch)
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.corruptAuthorization() =
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO identity_adoption_authorizations(operation_id,decision_id,operation_revision," +
                    "binding_hash,challenge_hash,device_origin,device_public_key,authorized_at) VALUES (?,?,1,?,?,?,?,?)",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, Uuid.random().toJavaUUID())
                it.setString(3, "a".repeat(64))
                it.setString(4, "b".repeat(64))
                it.setString(5, device.origin)
                it.setString(6, device.publicKey)
                it.setTimestamp(7, clock.now().timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
}
