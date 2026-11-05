@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourceQueryView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionSourceResponse
import app.logdate.shared.model.identity.IdentityAdoptionSourceStartRequest
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class IdentityAdoptionSourceRoutesPostgresTest {
    @Test fun signedSourceTransportCoversEveryRegisteredKindWithoutLosingEvidence() = exercise(Scenario.JOURNEY)

    @Test fun disabledSourceTransportDoesNotExposeAnInventory() = exercise(Scenario.DISABLED)

    @Test fun anonymousRequestsCannotGetSourceChallenges() = exercise(Scenario.ANONYMOUS)

    @Test fun anotherAccountCannotReadTheSourceInventory() = exercise(Scenario.FOREIGN)

    @Test fun linkedDeviceSessionsCannotReadTheSourceInventory() = exercise(Scenario.LINKED)

    @Test fun anotherDeviceSignatureCannotReadOrConsumeTheSourceChallenge() = exercise(Scenario.SIGNATURE)

    @Test fun invalidSourceLimitsAreRejectedBeforeChallengeAllocation() = exercise(Scenario.LIMIT)

    private enum class Scenario { JOURNEY, DISABLED, ANONYMOUS, FOREIGN, LINKED, SIGNATURE, LIMIT }

    private fun exercise(scenario: Scenario) =
        withGenericAdoption {
            hold()
            val f = this
            val tokens = JwtTokenService("synthetic-source-secret")
            val account = current.binding.accountId
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.FOREIGN ->
                        tokens.generateAccessToken(
                            createTestAccount(
                                Uuid.random().toJavaUUID(),
                                "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb",
                            ).id.toString(),
                        )
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(account.toString(), current.binding.sourceDid, "linked")
                    else -> tokens.generateAccessToken(account.toString())
                }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            PostgreSQLAccountRepository(),
                            tokens,
                            f.auth.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            sourceAccess = PostgreSQLIdentityAdoptionSourceAccess(f.auth.source, f.auth.clock),
                        )
                    }
                }
                val expected =
                    current.binding.let {
                        IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, current.revision)
                    }

                suspend fun send(
                    path: String,
                    body: String,
                ) = client.post("/account/identity-adoption/${current.id}" + path) {
                    token?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                for (kind in IdentityAdoptionSourceKindView.entries) {
                    val query = IdentityAdoptionSourceQueryView(kind, limit = if (scenario == Scenario.LIMIT) 33 else 32)
                    val challengeResponse =
                        send(
                            "/source-challenge",
                            Json.encodeToString(
                                IdentityAdoptionSourceStartRequest(
                                    expected,
                                    IdentityAdoptionPhaseView.FENCED,
                                    query,
                                ),
                            ),
                        )
                    val expectedStatus =
                        when (scenario) {
                            Scenario.DISABLED, Scenario.FOREIGN -> HttpStatusCode.NotFound
                            Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                            Scenario.LINKED -> HttpStatusCode.Forbidden
                            Scenario.LIMIT -> HttpStatusCode.BadRequest
                            else -> HttpStatusCode.OK
                        }
                    assertEquals(expectedStatus, challengeResponse.status)
                    assertEquals("no-store", challengeResponse.headers[HttpHeaders.CacheControl])
                    if (expectedStatus != HttpStatusCode.OK) return@testApplication
                    val challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(challengeResponse.bodyAsText()).data.challenge
                    assertEquals("READ_STAGED", challenge.action.name)
                    val domain =
                        IdentityAdoptionActionChallenge(
                            Uuid.parse(challenge.id),
                            challenge.nonce,
                            IdentityAdoptionAction.READ_STAGED,
                            challenge.bodyDigest,
                        )
                    val consumed = auth.countConsumed()
                    val read =
                        IdentityAdoptionSourceReadRequest(
                            expected,
                            IdentityAdoptionPhaseView.FENCED,
                            query,
                            challenge,
                            if (scenario == Scenario.SIGNATURE) {
                                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
                            } else {
                                auth.actionSignature(domain, current)
                            },
                        )
                    val response = send("/source-page", Json.encodeToString(read))
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    if (scenario == Scenario.SIGNATURE) {
                        assertEquals(HttpStatusCode.Conflict, response.status)
                        assertEquals(consumed, auth.countConsumed())
                        return@testApplication
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val value = Json.decodeFromString<IdentityAdoptionSourceResponse>(response.bodyAsText())
                    assertEquals(current.id, value.data.operation.id)
                    assertEquals(current.revision, value.data.operation.revision)
                    assertEquals(kind, value.data.page.kind)
                    assertEquals(true, value.data.page.fenced)
                    val page =
                        assertNotNull(
                            PostgreSQLIdentityAdoptionSourceInventory(auth.source).page(
                                current,
                                IdentityAdoptionSourceKind.valueOf(kind.name),
                                now = auth.clock.now(),
                            ),
                        )
                    assertEquals(page.count, value.data.page.count)
                    assertEquals(page.present, value.data.page.present)
                    assertEquals(page.items.size, value.data.page.items.size)
                    for ((raw, transported) in page.items.zip(value.data.page.items)) {
                        assertContentEquals(
                            IdentityAdoptionSourceEvidence.canonicalItem(page.kind, raw),
                            Base64.getUrlDecoder().decode(transported.evidence),
                        )
                    }
                    assertFalse(response.bodyAsText().contains("opaque source"))
                }
            }
        }
}
