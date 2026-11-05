@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccountModeration
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionConfirmationService
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationRequest
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationResponse
import app.logdate.shared.model.identity.IdentityAdoptionConfirmationStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionDeviceKeyView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
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

class IdentityAdoptionConfirmationRoutesPostgresTest {
    @Test fun registeredPasskeyAndDeviceSignatureAuthorizeOnlyThisProposalWithLostResponseReplay() = exercise(Scenario.JOURNEY)

    @Test fun anonymousConfirmationCannotCreateADecision() = exercise(Scenario.ANONYMOUS)

    @Test fun linkedDeviceCannotCreateADecision() = exercise(Scenario.LINKED)

    @Test fun disabledConfirmationCannotCreateADecision() = exercise(Scenario.DISABLED)

    @Test fun anotherAccountCannotFindTheProposal() = exercise(Scenario.FOREIGN)

    @Test fun moderatedAccountCannotConfirm() = exercise(Scenario.MODERATED)

    @Test fun staleRevisionCannotCreateADecision() = exercise(Scenario.STALE_REVISION)

    @Test fun anotherDeviceCannotCreateADecision() = exercise(Scenario.WRONG_DEVICE)

    @Test fun anotherTargetCannotCreateADecision() = exercise(Scenario.WRONG_TARGET)

    @Test fun changedAccountCannotCreateADecision() = exercise(Scenario.CHANGED_START)

    @Test fun malformedDeviceKeyCannotCreateADecision() = exercise(Scenario.BAD_KEY)

    @Test fun anotherBackendOriginCannotCreateADecision() = exercise(Scenario.FOREIGN_ORIGIN)

    @Test fun badPasskeyCannotAuthorizeTheDevice() = exercise(Scenario.BAD_PASSKEY)

    @Test fun badDeviceSignatureCannotAuthorizeTheDevice() = exercise(Scenario.BAD_SIGNATURE)

    @Test fun anotherDecisionCannotAuthorizeTheDevice() = exercise(Scenario.WRONG_DECISION)

    @Test fun expiredDecisionCannotAuthorizeTheDevice() = exercise(Scenario.EXPIRED)

    @Test fun changedAccountAfterPromptCannotAuthorizeTheDevice() = exercise(Scenario.CHANGED_CONFIRM)

    @Test fun oversizedBodyCannotCreateADecision() = exercise(Scenario.OVERSIZED)

    @Test fun aRegisteredCredentialWithoutUserHandleStillConfirms() = exercise(Scenario.NULL_USER_HANDLE)

    private enum class Scenario {
        JOURNEY,
        NULL_USER_HANDLE,
        ANONYMOUS,
        LINKED,
        DISABLED,
        FOREIGN,
        MODERATED,
        STALE_REVISION,
        WRONG_DEVICE,
        WRONG_TARGET,
        CHANGED_START,
        BAD_KEY,
        FOREIGN_ORIGIN,
        BAD_PASSKEY,
        BAD_SIGNATURE,
        WRONG_DECISION,
        EXPIRED,
        CHANGED_CONFIRM,
        OVERSIZED,
    }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val fixture = this
            val tokens = JwtTokenService("synthetic-confirmation-token-secret")
            val owner = operation.binding.accountId
            val accounts = PostgreSQLAccountRepository()
            val confirmation = IdentityAdoptionConfirmationService(proofs, decisions, service, "https://cloud.example", clock)
            val foreign =
                if (scenario == Scenario.FOREIGN) {
                    createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
                } else {
                    null
                }
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(owner.toString(), operation.binding.sourceDid, "linked")
                    else -> tokens.generateAccessToken((foreign?.id ?: owner).toString())
                }
            if (scenario == Scenario.MODERATED) accounts.setModeration(owner, AccountModeration.SUSPENDED)
            if (scenario == Scenario.CHANGED_START) advanceEpoch()
            var expected =
                IdentityAdoptionExpectedOperation(
                    operation.binding.deviceId,
                    operation.binding.sourceDid,
                    operation.binding.targetDid,
                    operation.binding.sourceEpoch,
                    operation.revision,
                )
            expected =
                when (scenario) {
                    Scenario.STALE_REVISION -> expected.copy(expectedRevision = operation.revision + 1)
                    Scenario.WRONG_DEVICE -> expected.copy(deviceId = "other-device")
                    Scenario.WRONG_TARGET -> expected.copy(targetDid = "did:web:other.example")
                    else -> expected
                }
            var key = IdentityAdoptionDeviceKeyView(device.origin, device.publicKey)
            key =
                when (scenario) {
                    Scenario.BAD_KEY -> key.copy(publicKey = "invalid")
                    Scenario.FOREIGN_ORIGIN -> key.copy(origin = "https://foreign.example")
                    else -> key
                }
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            accounts,
                            tokens,
                            fixture.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            confirmation = confirmation,
                        )
                    }
                }
                val path = "/account/identity-adoption/${operation.id}"
                val start =
                    client.post("$path/confirmation") {
                        token?.let { bearerAuth(it) }
                        contentType(ContentType.Application.Json)
                        val body =
                            if (scenario == Scenario.OVERSIZED) {
                                "x".repeat(70000)
                            } else {
                                Json.encodeToString(IdentityAdoptionConfirmationStartRequest(expected, key))
                            }
                        setBody(body)
                    }
                val status =
                    when (scenario) {
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED, Scenario.MODERATED -> HttpStatusCode.Forbidden
                        Scenario.DISABLED, Scenario.FOREIGN -> HttpStatusCode.NotFound
                        Scenario.STALE_REVISION,
                        Scenario.WRONG_DEVICE,
                        Scenario.WRONG_TARGET,
                        Scenario.CHANGED_START,
                        -> HttpStatusCode.Conflict
                        Scenario.BAD_KEY, Scenario.FOREIGN_ORIGIN -> HttpStatusCode.BadRequest
                        Scenario.OVERSIZED -> HttpStatusCode.PayloadTooLarge
                        else -> HttpStatusCode.OK
                    }
                assertEquals(status, start.status)
                assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
                assertFalse(start.bodyAsText().contains("synthetic-confirmation-token-secret"))
                if (status != HttpStatusCode.OK) {
                    assertEquals(0, fixture.count("identity_adoption_decisions"))
                    assertEquals(0, fixture.count("identity_adoption_authorizations"))
                    return@testApplication
                }
                val response = Json.decodeFromString<IdentityAdoptionConfirmationResponse>(start.bodyAsText())
                assertEquals(true, response.success)
                val decision = response.data
                assertEquals(operation.id, decision.operation.id)
                assertEquals(owner.toString(), decision.operation.binding.accountId)
                assertEquals(operation.binding.targetDid, decision.operation.binding.targetDid)
                assertEquals(operation.revision, decision.operation.revision)
                assertEquals(key, decision.deviceKey)
                assertEquals((clock.now() + 300.seconds).toString(), decision.expiresAt)
                assertEquals("logdate.app", decision.options.rpId)
                val decisionId = Uuid.parse(decision.decisionId)
                var credential = fixture.passkey.assert(decision.options.challenge)
                if (scenario == Scenario.BAD_PASSKEY) {
                    credential = credential.copy(response = credential.response.copy(signature = "A".repeat(86)))
                }
                val signature =
                    if (scenario == Scenario.BAD_SIGNATURE) {
                        "A".repeat(86)
                    } else {
                        fixture.sign(decisionId, decision.options.challenge)
                    }
                val body =
                    IdentityAdoptionConfirmationRequest(
                        expected,
                        if (scenario == Scenario.WRONG_DECISION) Uuid.random().toString() else decision.decisionId,
                        decision.options.challenge,
                        credential.authentication(),
                        signature,
                    )
                if (scenario == Scenario.EXPIRED) clock.time += 301.seconds
                if (scenario == Scenario.CHANGED_CONFIRM) fixture.advanceEpoch()

                suspend fun confirm() =
                    client.post("$path/confirm") {
                        bearerAuth(requireNotNull(token))
                        contentType(ContentType.Application.Json)
                        val encoded = Json.encodeToString(body)
                        setBody(
                            if (scenario == Scenario.NULL_USER_HANDLE) {
                                encoded.replace("\"userHandle\":\"${credential.response.userHandle}\"", "\"userHandle\":null")
                            } else {
                                encoded
                            },
                        )
                    }
                val confirmed = confirm()
                val expectedStatus =
                    when (scenario) {
                        Scenario.BAD_PASSKEY,
                        Scenario.BAD_SIGNATURE,
                        Scenario.WRONG_DECISION,
                        Scenario.EXPIRED,
                        -> HttpStatusCode.BadRequest
                        Scenario.CHANGED_CONFIRM -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.OK
                    }
                assertEquals(expectedStatus, confirmed.status)
                assertEquals("no-store", confirmed.headers[HttpHeaders.CacheControl])
                if (scenario == Scenario.JOURNEY || scenario == Scenario.NULL_USER_HANDLE) {
                    val result = Json.decodeFromString<IdentityAdoptionStatusResponse>(confirmed.bodyAsText())
                    assertEquals(operation.id, result.data.operation.id)
                    assertEquals(operation.revision, result.data.operation.revision)
                    assertNull(result.data.receipt)
                    assertNotNull(fixture.repository.get(operation, clock.now()))
                    val replay = confirm()
                    assertEquals(HttpStatusCode.OK, replay.status)
                    assertEquals(result, Json.decodeFromString<IdentityAdoptionStatusResponse>(replay.bodyAsText()))
                    assertEquals(1, fixture.count("identity_adoption_authorizations"))
                } else {
                    assertEquals(0, fixture.count("identity_adoption_authorizations"))
                }
                assertEquals(operation.binding.sourceDid, accounts.findById(owner)?.did)
                assertEquals(0, fixture.count("identity_adoption_receipts"))
                assertEquals(0, fixture.count("identity_adoption_staged_records"))
            }
        }

    private fun PasskeyAssertionResponse.authentication() =
        PasskeyAuthenticationResponse(
            id,
            rawId,
            AuthenticatorAssertionResponse(response.clientDataJSON, response.authenticatorData, response.signature, response.userHandle),
            type,
        )

    private suspend fun IdentityAdoptionAuthorizedFixture.count(table: String): Long =
        source.blueskyTransaction {
            createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use {
                    check(it.next())
                    it.getLong(1)
                }
            }
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.advanceEpoch() =
        source.blueskyTransaction {
            prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.executeUpdate()
            }
        }
}
