@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.bluesky.BlueskySignInProvider
import app.logdate.server.bluesky.VerifiedBlueskyCallback
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionProposals
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionBrowserCancelResponse
import app.logdate.shared.model.identity.IdentityAdoptionOAuthExchangeRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionBrowserCancelRoutesPostgresTest {
    @Test fun cancelBeforeExchangeReturnsADurableBoundReceipt() = exercise(Scenario.BEFORE)

    @Test fun aLostExchangeResponseCanBeCanceledWithoutKnowingTheTargetDid() = exercise(Scenario.EXCHANGED)

    @Test fun expiredProviderStateDoesNotPreventExplicitCancellation() = exercise(Scenario.EXPIRED)

    @Test fun authorizedOperationsRequireTheDeviceCancellationPath() = exercise(Scenario.AUTHORIZED)

    @Test fun anonymousCancellationCannotClaimAReceipt() = exercise(Scenario.ANONYMOUS)

    @Test fun linkedDeviceCancellationCannotClaimAReceipt() = exercise(Scenario.LINKED)

    @Test fun anotherAccountCannotCancelTheBrowserProposal() = exercise(Scenario.FOREIGN)

    @Test fun theWrongVerifierCannotCancelTheBrowserProposal() = exercise(Scenario.BAD_PROOF)

    @Test fun theWrongDeviceCannotCancelTheBrowserProposal() = exercise(Scenario.BAD_DEVICE)

    @Test fun unknownProposalCannotClaimAReceipt() = exercise(Scenario.BAD_PROPOSAL)

    @Test fun disabledCancellationCannotClaimAReceipt() = exercise(Scenario.DISABLED)

    @Test fun oversizedCancellationCannotConsumeTheProof() = exercise(Scenario.OVERSIZED)

    @Test fun malformedJsonCannotConsumeTheProof() = exercise(Scenario.BAD_JSON)

    private enum class Scenario {
        BEFORE,
        EXCHANGED,
        EXPIRED,
        AUTHORIZED,
        ANONYMOUS,
        LINKED,
        FOREIGN,
        BAD_PROOF,
        BAD_DEVICE,
        BAD_PROPOSAL,
        DISABLED,
        OVERSIZED,
        BAD_JSON,
    }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val f = this
            source.blueskyTransaction {
                prepareStatement("DELETE FROM identity_adoption_operations WHERE id=?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val owner = operation.binding.accountId
            val binding = IdentityAdoptionOAuthBinding(owner, operation.binding.sourceDid, 0, "device-a", Uuid.random())
            val attempts = PostgreSQLBlueskySignInAttemptStore(source, AtRestKeyCipher("synthetic-cancel-http-secret"), clock)
            val backend = PostgreSQLIdentityAdoptionBrowserCancellation(source)
            val service = IdentityAdoptionOAuthService(Provider(), attempts, operations, backend, clock)
            val verifier = "v".repeat(64)
            val challenge =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                )
            val started = assertNotNull(service.start(binding, Handle.require("target.example"), challenge))
            if (scenario == Scenario.EXCHANGED || scenario == Scenario.AUTHORIZED) {
                assertEquals(true, attempts.complete(started.attemptId, AtprotoDid.require("did:web:target.example")))
                val proposed = IdentityAdoptionProposals(attempts, operations, clock).exchange(binding, started.attemptId, verifier)
                val op = assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(proposed).value
                if (scenario == Scenario.AUTHORIZED) IdentityAdoptionAuthorizedFixture(source, clock, op).authorize()
            }
            if (scenario == Scenario.EXPIRED) clock.time += 601.seconds
            val tokens = JwtTokenService("synthetic-browser-cancel-http-jwt")
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(owner.toString(), binding.sourceDid, "linked")
                    Scenario.FOREIGN -> {
                        val other = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
                        tokens.generateAccessToken(other.id.toString())
                    }
                    else -> tokens.generateAccessToken(owner.toString())
                }
            val request =
                IdentityAdoptionOAuthExchangeRequest(
                    if (scenario == Scenario.BAD_PROPOSAL) Uuid.random().toString() else binding.proposalId.toString(),
                    started.attemptId,
                    if (scenario == Scenario.BAD_PROOF) "z".repeat(64) else verifier,
                    if (scenario == Scenario.BAD_DEVICE) "other" else binding.deviceId,
                    binding.sourceDid,
                    binding.sourceEpoch,
                )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            PostgreSQLAccountRepository(),
                            tokens,
                            f.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            oauth = service,
                        )
                    }
                }

                suspend fun send() =
                    client.post("/account/identity-adoption/cancel-browser") {
                        token?.let { bearerAuth(it) }
                        contentType(ContentType.Application.Json)
                        val body =
                            when (scenario) {
                                Scenario.OVERSIZED -> "x".repeat(70000)
                                Scenario.BAD_JSON -> "{"
                                else -> Json.encodeToString(request)
                            }
                        setBody(body)
                    }
                val response = send()
                val expected =
                    when (scenario) {
                        Scenario.BEFORE, Scenario.EXCHANGED, Scenario.EXPIRED -> HttpStatusCode.OK
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        Scenario.FOREIGN, Scenario.AUTHORIZED -> HttpStatusCode.Conflict
                        Scenario.DISABLED -> HttpStatusCode.NotFound
                        Scenario.OVERSIZED -> HttpStatusCode.PayloadTooLarge
                        else -> HttpStatusCode.BadRequest
                    }
                assertEquals(expected, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val body = response.bodyAsText()
                assertFalse(body.contains(verifier))
                if (expected == HttpStatusCode.OK) {
                    val canceled = Json.decodeFromString<IdentityAdoptionBrowserCancelResponse>(body)
                    assertEquals(true, canceled.success)
                    assertEquals(binding.proposalId.toString(), canceled.data.proposalId)
                    assertEquals(owner.toString(), canceled.data.accountId)
                    assertEquals(binding.deviceId, canceled.data.deviceId)
                    assertEquals(binding.sourceDid, canceled.data.sourceDid)
                    assertEquals(binding.sourceEpoch, canceled.data.sourceEpoch)
                    val replay = send()
                    assertEquals(HttpStatusCode.OK, replay.status)
                    assertEquals(canceled, Json.decodeFromString<IdentityAdoptionBrowserCancelResponse>(replay.bodyAsText()))
                    assertFalse(attempts.complete(started.attemptId, AtprotoDid.require("did:web:target.example")))
                    val op = f.operations.get(owner, binding.proposalId.toString())
                    if (scenario == Scenario.EXCHANGED) {
                        assertEquals(IdentityAdoptionPhase.CANCELED, assertNotNull(op).phase)
                    } else {
                        assertNull(op)
                    }
                } else {
                    assertFalse(body.contains(binding.sourceDid))
                    assertFalse(body.contains("synthetic-cancel-http-secret"))
                }
            }
            assertEquals(binding.sourceDid, PostgreSQLAccountRepository().findById(owner)?.did)
            assertNull(operations.receipt(owner, binding.proposalId.toString()))
        }

    private class Provider : BlueskySignInProvider {
        override suspend fun authorize(
            handle: Handle,
            attemptId: String,
        ) = "https://provider.example/authorize"

        override suspend fun callback(
            parameters: Map<String, String>,
            acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
        ) { }
    }
}
