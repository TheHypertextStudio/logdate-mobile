@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionLifecycle
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionAbandonRequest
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionLifecycleRequest
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionLifecycleRoutesPostgresTest {
    @Test fun signedHoldRenewCancelPreserveIdentityAndRecoverLostResponses() = exercise(Scenario.JOURNEY)

    @Test fun anUnconfirmedProposalCannotGetAMutationNonce() = exercise(Scenario.NO_AUTH)

    @Test fun anUnconfirmedProposalCanBeAbandonedWithoutChangingJournalAuthority() = exercise(Scenario.ABANDON)

    @Test fun anAuthorizedProposalCannotUseUnsignedAbandon() = exercise(Scenario.AUTH_ABANDON)

    @Test fun authorizationRacingAbandonCannotBypassTheDeviceProof() = exercise(Scenario.ABANDON_RACE)

    @Test fun anotherAccountCannotGetAnActionNonce() = exercise(Scenario.FOREIGN)

    @Test fun linkedDevicesCannotGetAnActionNonce() = exercise(Scenario.LINKED)

    @Test fun anonymousRequestsCannotGetAnActionNonce() = exercise(Scenario.ANONYMOUS)

    @Test fun disabledLifecycleDoesNotCreateAnActionNonce() = exercise(Scenario.DISABLED)

    @Test fun staleRevisionCannotGetAnActionNonce() = exercise(Scenario.STALE)

    @Test fun changedSourceEpochCannotGetAnActionNonce() = exercise(Scenario.EPOCH)

    @Test fun aWrongSignatureCannotConsumeTheNonce() = exercise(Scenario.BAD_SIGNATURE)

    @Test fun aDifferentNonceCannotConsumeTheChallenge() = exercise(Scenario.BAD_NONCE)

    @Test fun aPayloadDigestCannotBeSmuggledIntoALifecycleAction() = exercise(Scenario.BAD_DIGEST)

    @Test fun anExpiredNonceCannotStartTheHold() = exercise(Scenario.EXPIRED_NONCE)

    @Test fun anExpiredHoldIsReleasedWithoutOfferingAStaleRenewal() = exercise(Scenario.EXPIRED_LEASE)

    @Test fun commitCannotBeRequestedThroughTheLifecycleEndpoint() = exercise(Scenario.FORBIDDEN_ACTION)

    private enum class Scenario {
        JOURNEY,
        NO_AUTH,
        ABANDON,
        AUTH_ABANDON,
        ABANDON_RACE,
        FOREIGN,
        LINKED,
        ANONYMOUS,
        DISABLED,
        STALE,
        EPOCH,
        BAD_SIGNATURE,
        BAD_NONCE,
        BAD_DIGEST,
        EXPIRED_NONCE,
        EXPIRED_LEASE,
        FORBIDDEN_ACTION,
    }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val f = this
            if (scenario !in setOf(Scenario.NO_AUTH, Scenario.ABANDON, Scenario.ABANDON_RACE)) authorize()
            val accounts = PostgreSQLAccountRepository()
            val tokens = JwtTokenService("synthetic-lifecycle-secret")
            val owner = operation.binding.accountId
            val backend = PostgreSQLIdentityAdoptionLifecycle(source, clock)
            val lifecycle: IdentityAdoptionLifecycle =
                if (scenario == Scenario.ABANDON_RACE) {
                    object : IdentityAdoptionLifecycle by backend {
                        override suspend fun abandon(
                            operation: IdentityAdoptionOperation,
                        ): app.logdate.server.identity.adoption.IdentityAdoptionOutcome<IdentityAdoptionOperation> {
                            f.authorize()
                            return backend.abandon(operation)
                        }
                    }
                } else {
                    backend
                }
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
            if (scenario == Scenario.EPOCH) {
                source.blueskyTransaction {
                    prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                        it.setObject(1, owner.toJavaUUID())
                        it.executeUpdate()
                    }
                }
            }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            accounts,
                            tokens,
                            f.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            lifecycle = lifecycle,
                        )
                    }
                }
                val base = "/account/identity-adoption/${operation.id}"

                suspend fun send(
                    path: String,
                    body: String,
                ) = client.post(base + path) {
                    token?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                if (scenario in setOf(Scenario.ABANDON, Scenario.AUTH_ABANDON, Scenario.ABANDON_RACE)) {
                    val body = Json.encodeToString(IdentityAdoptionAbandonRequest(expected(operation)))
                    val response = send("/abandon", body)
                    val expectedStatus = if (scenario == Scenario.ABANDON) HttpStatusCode.OK else HttpStatusCode.Conflict
                    assertEquals(expectedStatus, response.status)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    if (scenario == Scenario.ABANDON) {
                        val canceled = Json.decodeFromString<IdentityAdoptionStatusResponse>(response.bodyAsText())
                        assertEquals(IdentityAdoptionPhaseView.CANCELED, canceled.data.operation.phase)
                        assertEquals(2, canceled.data.operation.revision)
                        val replay = send("/abandon", body)
                        assertEquals(HttpStatusCode.OK, replay.status)
                        assertEquals(canceled, Json.decodeFromString<IdentityAdoptionStatusResponse>(replay.bodyAsText()))
                    } else {
                        assertEquals(operation, f.operations.get(owner, operation.id))
                    }
                    return@testApplication
                }
                var current = operation

                suspend fun start(action: IdentityAdoptionActionView) =
                    send(
                        "/action-challenge",
                        Json.encodeToString(
                            IdentityAdoptionActionStartRequest(
                                expected(current).let {
                                    if (scenario == Scenario.STALE) it.copy(expectedRevision = it.expectedRevision + 1) else it
                                },
                                IdentityAdoptionPhaseView.valueOf(current.phase.name),
                                action,
                            ),
                        ),
                    )

                suspend fun perform(action: IdentityAdoptionActionView): IdentityAdoptionOperation {
                    val started = start(action)
                    assertEquals(HttpStatusCode.OK, started.status)
                    val challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(started.bodyAsText()).data.challenge
                    assertEquals(EMPTY_BODY_DIGEST, challenge.bodyDigest)
                    val domain =
                        IdentityAdoptionActionChallenge(
                            Uuid.parse(challenge.id),
                            challenge.nonce,
                            IdentityAdoptionAction.valueOf(challenge.action.name),
                            challenge.bodyDigest,
                        )
                    val signature = f.actionSignature(domain, current)
                    val request =
                        IdentityAdoptionLifecycleRequest(
                            expected(current),
                            IdentityAdoptionPhaseView.valueOf(current.phase.name),
                            challenge,
                            signature,
                        )
                    val body = Json.encodeToString(request)
                    val response = send("/lifecycle", body)
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    val status = Json.decodeFromString<IdentityAdoptionStatusResponse>(response.bodyAsText())
                    assertNull(status.data.receipt)
                    assertEquals(current.revision + 1, status.data.operation.revision)
                    val replay = send("/lifecycle", body)
                    assertEquals(HttpStatusCode.Conflict, replay.status)
                    return assertNotNull(f.operations.get(owner, operation.id))
                }
                if (scenario == Scenario.JOURNEY || scenario == Scenario.EXPIRED_LEASE) {
                    current = perform(IdentityAdoptionActionView.HOLD)
                    assertEquals(IdentityAdoptionPhase.FENCED, current.phase)
                    if (scenario == Scenario.JOURNEY) {
                        clock.time += 10.seconds
                        current = perform(IdentityAdoptionActionView.RENEW)
                        assertEquals(clock.now() + 120.seconds, current.leaseExpiresAt)
                        current = perform(IdentityAdoptionActionView.CANCEL)
                        assertEquals(IdentityAdoptionPhase.CANCELED, current.phase)
                        assertEquals(3, f.countConsumed())
                        return@testApplication
                    }
                    clock.time += 121.seconds
                }
                val action =
                    when (scenario) {
                        Scenario.EXPIRED_LEASE -> IdentityAdoptionActionView.RENEW
                        Scenario.FORBIDDEN_ACTION -> IdentityAdoptionActionView.COMMIT
                        else -> IdentityAdoptionActionView.HOLD
                    }
                val started = start(action)
                val startStatus =
                    when (scenario) {
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.FOREIGN, Scenario.DISABLED -> HttpStatusCode.NotFound
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        Scenario.STALE,
                        Scenario.EPOCH,
                        Scenario.NO_AUTH,
                        Scenario.EXPIRED_LEASE,
                        -> HttpStatusCode.Conflict
                        Scenario.FORBIDDEN_ACTION -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.OK
                    }
                assertEquals(startStatus, started.status)
                assertEquals("no-store", started.headers[HttpHeaders.CacheControl])
                if (startStatus != HttpStatusCode.OK) {
                    if (scenario == Scenario.EXPIRED_LEASE) {
                        val reset = assertNotNull(f.operations.get(owner, operation.id))
                        assertEquals(IdentityAdoptionPhase.PREPARING, reset.phase)
                        assertEquals(current.revision + 1, reset.revision)
                    } else {
                        assertFalse(started.bodyAsText().contains(operation.binding.targetDid))
                        assertEquals(operation, f.operations.get(owner, operation.id))
                    }
                    return@testApplication
                }
                var challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(started.bodyAsText()).data.challenge
                val domain =
                    IdentityAdoptionActionChallenge(
                        Uuid.parse(challenge.id),
                        challenge.nonce,
                        IdentityAdoptionAction.valueOf(challenge.action.name),
                        challenge.bodyDigest,
                    )
                val signature = if (scenario == Scenario.BAD_SIGNATURE) "A".repeat(86) else f.actionSignature(domain, current)
                if (scenario == Scenario.BAD_NONCE) challenge = challenge.copy(nonce = "A".repeat(43))
                if (scenario == Scenario.BAD_DIGEST) challenge = challenge.copy(bodyDigest = "a".repeat(64))
                if (scenario == Scenario.EXPIRED_NONCE) clock.time += 31.seconds
                val response =
                    send(
                        "/lifecycle",
                        Json.encodeToString(
                            IdentityAdoptionLifecycleRequest(
                                expected(current),
                                IdentityAdoptionPhaseView.valueOf(current.phase.name),
                                challenge,
                                signature,
                            ),
                        ),
                    )
                val refused = if (scenario == Scenario.BAD_DIGEST) HttpStatusCode.BadRequest else HttpStatusCode.Conflict
                assertEquals(refused, response.status)
                assertEquals(0, f.countConsumed())
                assertEquals(operation, f.operations.get(owner, operation.id))
            }
            assertEquals(operation.binding.sourceDid, accounts.findById(owner)?.did)
            assertNull(operations.receipt(owner, operation.id))
        }

    private fun expected(operation: IdentityAdoptionOperation) =
        operation.binding.let {
            IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, operation.revision)
        }

    private companion object {
        const val EMPTY_BODY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
