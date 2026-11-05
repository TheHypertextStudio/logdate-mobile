@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionCommitRequest
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionFinalizationStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionStatusResponse
import app.logdate.shared.model.identity.IdentityAdoptionVerifyRequest
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class IdentityAdoptionFinalizationRoutesPostgresTest {
    @Test fun signedVerificationAndCommitCanRecoverTheExactLostResponse() = exercise(Scenario.JOURNEY)

    @Test fun disabledFinalizationCannotIssueAChallenge() = exercise(Scenario.DISABLED)

    @Test fun anonymousFinalizationCannotIssueAChallenge() = exercise(Scenario.ANONYMOUS)

    @Test fun anotherAccountCannotFinalizeThePrivateCopy() = exercise(Scenario.FOREIGN)

    @Test fun linkedDeviceSessionsCannotFinalizeTheAccountIdentity() = exercise(Scenario.LINKED)

    @Test fun staleRevisionsCannotIssueFinalizationChallenges() = exercise(Scenario.STALE)

    @Test fun lifecycleActionsCannotUseTheFinalizationEndpoint() = exercise(Scenario.ACTION)

    @Test fun invalidSignaturesCannotConsumeVerificationChallenges() = exercise(Scenario.SIGNATURE)

    @Test fun malformedManifestTransportCannotConsumeVerificationChallenges() = exercise(Scenario.MANIFEST)

    private enum class Scenario { JOURNEY, DISABLED, ANONYMOUS, FOREIGN, LINKED, STALE, ACTION, SIGNATURE, MANIFEST }

    private fun exercise(scenario: Scenario) =
        withAdoptionVerification {
            val fixture = this
            val tokens = JwtTokenService("synthetic-finalization-secret")
            val account = operation.binding.accountId
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.FOREIGN ->
                        tokens.generateAccessToken(
                            createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb").id.toString(),
                        )
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(account.toString(), operation.binding.sourceDid, "linked")
                    else -> tokens.generateAccessToken(account.toString())
                }
            val request = request()
            val manifestDigest = stagingHash(request.manifest)
            val finalizer = PostgreSQLIdentityAdoptionFinalization(auth.source, { Long.MAX_VALUE }, auth.clock)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            PostgreSQLAccountRepository(),
                            tokens,
                            fixture.auth.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            finalization = finalizer,
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
                val expected =
                    expected(operation).let {
                        if (scenario == Scenario.STALE) it.copy(expectedRevision = it.expectedRevision + 1) else it
                    }
                val start =
                    IdentityAdoptionFinalizationStartRequest(
                        expected,
                        IdentityAdoptionPhaseView.FENCED,
                        if (scenario == Scenario.ACTION) IdentityAdoptionActionView.HOLD else IdentityAdoptionActionView.VERIFY,
                        manifestDigest,
                        coverage,
                    )
                val challengeResponse = send("/finalization-challenge", Json.encodeToString(start))
                val expectedStatus =
                    when (scenario) {
                        Scenario.DISABLED, Scenario.FOREIGN -> HttpStatusCode.NotFound
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        Scenario.STALE -> HttpStatusCode.Conflict
                        Scenario.ACTION -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.OK
                    }
                assertEquals(expectedStatus, challengeResponse.status)
                assertEquals("no-store", challengeResponse.headers[HttpHeaders.CacheControl])
                if (expectedStatus != HttpStatusCode.OK) return@testApplication
                val challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(challengeResponse.bodyAsText()).data.challenge
                assertEquals(verificationDigest(request), challenge.bodyDigest)
                val signature = auth.actionSignature(domain(challenge), operation)
                val consumed = auth.countConsumed()
                val verify =
                    IdentityAdoptionVerifyRequest(
                        expected,
                        challenge,
                        if (scenario == Scenario.SIGNATURE) invalidSignature() else signature,
                        transportManifest(scenario, request.manifest),
                        coverage,
                    )
                val verifiedResponse = send("/verify", Json.encodeToString(verify))
                if (scenario in setOf(Scenario.SIGNATURE, Scenario.MANIFEST)) {
                    assertEquals(
                        if (scenario ==
                            Scenario.MANIFEST
                        ) {
                            HttpStatusCode.BadRequest
                        } else {
                            HttpStatusCode.Conflict
                        },
                        verifiedResponse.status,
                    )
                    assertEquals(consumed, auth.countConsumed())
                    return@testApplication
                }
                assertEquals(HttpStatusCode.OK, verifiedResponse.status)
                val verified = Json.decodeFromString<IdentityAdoptionStatusResponse>(verifiedResponse.bodyAsText())
                assertEquals(IdentityAdoptionPhaseView.VERIFIED, verified.data.operation.phase)
                val current = assertNotNull(auth.operations.get(account, operation.id))
                val commitStart =
                    IdentityAdoptionFinalizationStartRequest(
                        expected(current),
                        IdentityAdoptionPhaseView.VERIFIED,
                        IdentityAdoptionActionView.COMMIT,
                        manifestDigest,
                        coverage,
                    )
                val commitChallengeResponse = send("/finalization-challenge", Json.encodeToString(commitStart))
                assertEquals(HttpStatusCode.OK, commitChallengeResponse.status)
                val ticket = Json.decodeFromString<IdentityAdoptionActionResponse>(commitChallengeResponse.bodyAsText()).data.challenge
                val commit =
                    IdentityAdoptionCommitRequest(
                        expected(current),
                        ticket,
                        auth.actionSignature(domain(ticket), current),
                        manifestDigest,
                        coverage,
                    )
                val body = Json.encodeToString(commit)
                val committed = send("/commit", body)
                assertEquals(HttpStatusCode.OK, committed.status)
                val receipt = Json.decodeFromString<IdentityAdoptionStatusResponse>(committed.bodyAsText())
                assertEquals(IdentityAdoptionPhaseView.COMMITTED, receipt.data.operation.phase)
                assertEquals(1L, assertNotNull(receipt.data.receipt).identityEpoch)
                val replay = send("/commit", body)
                assertEquals(HttpStatusCode.OK, replay.status)
                assertEquals(receipt, Json.decodeFromString<IdentityAdoptionStatusResponse>(replay.bodyAsText()))
                assertFalse(committed.bodyAsText().contains(signature))
                assertFalse(committed.bodyAsText().contains(verify.manifest))
            }
        }

    private fun invalidSignature(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))

    private fun transportManifest(
        scenario: Scenario,
        bytes: ByteArray,
    ): String = if (scenario == Scenario.MANIFEST) "invalid!" else Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun expected(operation: IdentityAdoptionOperation) =
        operation.binding.let {
            IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, operation.revision)
        }

    private fun domain(challenge: IdentityAdoptionActionChallengeView) =
        IdentityAdoptionActionChallenge(
            Uuid.parse(challenge.id),
            challenge.nonce,
            IdentityAdoptionAction.valueOf(challenge.action.name),
            challenge.bodyDigest,
        )
}
