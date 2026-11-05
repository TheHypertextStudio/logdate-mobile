@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionCopiedResponse
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageResponse
import app.logdate.shared.model.identity.IdentityAdoptionCopyDispositionView
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingResponse
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingView
import app.logdate.shared.model.identity.IdentityAdoptionCopyReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyRecordRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyRecordView
import app.logdate.shared.model.identity.IdentityAdoptionCopyStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyTargetView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionMappingReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionSealedCopyView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReferenceView
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IdentityAdoptionCopyRoutesPostgresTest {
    @Test fun authenticatedCopyReadbackMappingAndCoverageUseTheExistingPrivateAccount() = exercise(Scenario.JOURNEY)

    @Test fun preparationStagesWithoutAnIdentityCutoverOrFinalHold() = exercise(Scenario.PREPARATION)

    @Test fun deletedSourceRecordsRemainTombstonesAcrossTheTransport() = exercise(Scenario.DELETED)

    @Test fun largeSealedRecordsUseTheExistingRecordRequestAllowance() = exercise(Scenario.LARGE)

    @Test fun disabledCopyTransportCannotIssueAChallenge() = exercise(Scenario.DISABLED)

    @Test fun anonymousCopyRequestsCannotIssueAChallenge() = exercise(Scenario.ANONYMOUS)

    @Test fun linkedDeviceSessionsCannotPrepareAnAccountIdentityCopy() = exercise(Scenario.LINKED)

    @Test fun anotherAccountCannotPrepareAnIdentityCopy() = exercise(Scenario.FOREIGN)

    @Test fun aWrongSignatureDoesNotConsumeCopyAuthority() = exercise(Scenario.SIGNATURE)

    @Test fun insufficientQuotaDoesNotConsumeCopyAuthority() = exercise(Scenario.QUOTA)

    @Test fun oversizedCopyBodiesAreRefusedBeforeMutation() = exercise(Scenario.OVERSIZED)

    private enum class Scenario { JOURNEY, PREPARATION, DELETED, LARGE, DISABLED, ANONYMOUS, LINKED, FOREIGN, SIGNATURE, QUOTA, OVERSIZED }

    private fun exercise(scenario: Scenario) =
        withGenericAdoption(deleted = scenario == Scenario.DELETED) {
            if (scenario != Scenario.PREPARATION) hold()
            val f = this
            val request = request(target = if (scenario == Scenario.DELETED) null else genericSealed("target")).largeIfNeeded(scenario)
            val tokens = JwtTokenService("synthetic-copy-secret")
            val account = current.binding.accountId
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.LINKED -> tokens.generateDeviceAccessToken(account.toString(), current.binding.sourceDid, "linked")
                    Scenario.FOREIGN ->
                        tokens.generateAccessToken(
                            createTestAccount(
                                Uuid.random().toJavaUUID(),
                                "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb",
                            ).id.toString(),
                        )
                    else -> tokens.generateAccessToken(account.toString())
                }
            val access =
                PostgreSQLIdentityAdoptionCopyAccess(auth.source, {
                    if (scenario ==
                        Scenario.QUOTA
                    ) {
                        0L
                    } else {
                        Long.MAX_VALUE
                    }
                }, auth.clock)
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
                            copyAccess = access,
                        )
                    }
                }
                val expected =
                    current.binding.let {
                        IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, current.revision)
                    }
                val phase = IdentityAdoptionPhaseView.valueOf(current.phase.name)

                suspend fun send(
                    path: String,
                    body: String,
                ) = client.post("/account/identity-adoption/${current.id}" + path) {
                    token?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                suspend fun ticket(
                    action: IdentityAdoptionActionView,
                    digest: String,
                ) = send(
                    "/copy-challenge",
                    Json.encodeToString(IdentityAdoptionCopyStartRequest(expected, phase, action, digest)),
                )
                val started = ticket(IdentityAdoptionActionView.STAGE_RECORDS, genericRequestDigest(request))
                val status =
                    when (scenario) {
                        Scenario.DISABLED, Scenario.FOREIGN -> HttpStatusCode.NotFound
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        else -> HttpStatusCode.OK
                    }
                assertEquals(status, started.status)
                assertEquals("no-store", started.headers[HttpHeaders.CacheControl])
                if (status != HttpStatusCode.OK) return@testApplication
                val challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(started.bodyAsText()).data.challenge

                fun signature(value: app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView): String =
                    auth.actionSignature(
                        IdentityAdoptionActionChallenge(
                            Uuid.parse(value.id),
                            value.nonce,
                            IdentityAdoptionAction.valueOf(value.action.name),
                            value.bodyDigest,
                        ),
                        current,
                    )
                val consumed = auth.countConsumed()
                val copyRequest =
                    IdentityAdoptionCopyRecordRequest(
                        expected,
                        phase,
                        challenge,
                        if (scenario ==
                            Scenario.SIGNATURE
                        ) {
                            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
                        } else {
                            signature(challenge)
                        },
                        request.wire(),
                    )
                val body = if (scenario == Scenario.OVERSIZED) " ".repeat(6 * 1024 * 1024 + 1) else Json.encodeToString(copyRequest)
                val result = send("/copy-record", body)
                if (scenario in setOf(Scenario.SIGNATURE, Scenario.QUOTA, Scenario.OVERSIZED)) {
                    val refused = if (scenario == Scenario.OVERSIZED) HttpStatusCode.PayloadTooLarge else HttpStatusCode.Conflict
                    assertEquals(refused, result.status)
                    assertEquals(consumed, auth.countConsumed())
                    assertEquals(0L, used())
                    return@testApplication
                }
                assertEquals(HttpStatusCode.OK, result.status)
                val copied = Json.decodeFromString<IdentityAdoptionCopiedResponse>(result.bodyAsText()).data
                assertEquals(request.sourceDigest, copied.origin.sourceDigest)
                assertEquals(7L, copied.origin.sourceVersion)
                assertEquals(request.rkey, copied.record.rkey)
                assertEquals(current.revision, copied.record.generation)
                assertEquals(1L, copied.record.targetSequence)
                assertEquals(current.id, copied.operation.id)
                val readStart = ticket(IdentityAdoptionActionView.READ_STAGED, genericReadDigest(request))
                assertEquals(HttpStatusCode.OK, readStart.status)
                val readTicket = Json.decodeFromString<IdentityAdoptionActionResponse>(readStart.bodyAsText()).data.challenge
                val read =
                    IdentityAdoptionCopyReadRequest(
                        expected,
                        phase,
                        readTicket,
                        signature(readTicket),
                        request.wire().source,
                        request.sourceDigest,
                        request.rkey,
                    )
                val readResult = send("/read-copy", Json.encodeToString(read))
                assertEquals(HttpStatusCode.OK, readResult.status)
                assertEquals(copied, Json.decodeFromString<IdentityAdoptionCopiedResponse>(readResult.bodyAsText()).data)
                assertEquals(current.binding.sourceDid, PostgreSQLAccountRepository().findById(account)?.did)
                if (scenario == Scenario.DELETED) {
                    assertNull(copied.record.record)
                    assertEquals("1970-01-01T00:00:01.700Z", assertNotNull(copied.record.tombstone).deletedAt)
                }
                if (scenario in setOf(Scenario.PREPARATION, Scenario.LARGE)) return@testApplication
                val mapping =
                    IdentityAdoptionMappingRequest(
                        request.source,
                        request.sourceDigest,
                        IdentityAdoptionDisposition.CONVERTED,
                        listOf(IdentityAdoptionTargetReference(request.rkey, copied.record.targetDigest)),
                        emptyList(),
                    )
                val mapStart = ticket(IdentityAdoptionActionView.STAGE_RECORDS, prepareIdentityAdoptionMapping(mapping).bodyDigest)
                val mapTicket = Json.decodeFromString<IdentityAdoptionActionResponse>(mapStart.bodyAsText()).data.challenge
                val wireMap =
                    IdentityAdoptionCopyMappingView(
                        request.wire().source,
                        request.sourceDigest,
                        IdentityAdoptionCopyDispositionView.CONVERTED,
                        listOf(IdentityAdoptionCopyTargetView(request.rkey, copied.record.targetDigest)),
                        emptyList(),
                    )
                val mapped =
                    send(
                        "/copy-mapping",
                        Json.encodeToString(
                            IdentityAdoptionCopyMappingRequest(
                                expected,
                                phase,
                                mapTicket,
                                signature(mapTicket),
                                wireMap,
                            ),
                        ),
                    )
                assertEquals(HttpStatusCode.OK, mapped.status)
                val mapData = Json.decodeFromString<IdentityAdoptionCopyMappingResponse>(mapped.bodyAsText()).data
                val mapReadStart = ticket(IdentityAdoptionActionView.READ_STAGED, mappingReadDigest(mapping))
                val mapReadTicket = Json.decodeFromString<IdentityAdoptionActionResponse>(mapReadStart.bodyAsText()).data.challenge
                val mapRead =
                    send(
                        "/read-mapping",
                        Json.encodeToString(
                            IdentityAdoptionMappingReadRequest(
                                expected,
                                phase,
                                mapReadTicket,
                                signature(mapReadTicket),
                                request.wire().source,
                            ),
                        ),
                    )
                assertEquals(HttpStatusCode.OK, mapRead.status)
                assertEquals(mapData, Json.decodeFromString<IdentityAdoptionCopyMappingResponse>(mapRead.bodyAsText()).data)
                f.auth.mapStorageAuthority(f.current)
                val coverStart = ticket(IdentityAdoptionActionView.READ_STAGED, copyCoverageDigest())
                val coverTicket = Json.decodeFromString<IdentityAdoptionActionResponse>(coverStart.bodyAsText()).data.challenge
                val covered =
                    send(
                        "/copy-coverage",
                        Json.encodeToString(
                            IdentityAdoptionCopyCoverageRequest(
                                expected,
                                phase,
                                coverTicket,
                                signature(coverTicket),
                            ),
                        ),
                    )
                assertEquals(HttpStatusCode.OK, covered.status)
                val coverage = Json.decodeFromString<IdentityAdoptionCopyCoverageResponse>(covered.bodyAsText()).data.coverage
                assertEquals(7L, coverage.sourceSequence)
                assertEquals(1L, coverage.targetRecords)
                assertEquals(20, coverage.sources.size)
            }
        }

    private fun IdentityAdoptionGenericRequest.largeIfNeeded(scenario: Scenario): IdentityAdoptionGenericRequest {
        if (scenario != Scenario.LARGE) return this
        val ciphertext = Base64.getEncoder().encodeToString(ByteArray(33 * 65_536 + 16))
        val fields = requireNotNull(record).toMutableMap()
        fields["ciphertext"] = buildJsonObject { put("$" + "bytes", ciphertext) }
        return copy(record = JsonObject(fields))
    }

    private fun IdentityAdoptionGenericRequest.wire() =
        IdentityAdoptionCopyRecordView(
            IdentityAdoptionSourceReferenceView(IdentityAdoptionSourceKindView.valueOf(source.kind.name), source.key),
            sourceDigest,
            rkey,
            record?.let { value ->
                fun body(key: String) =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(
                        Base64.getDecoder().decode(
                            value
                                .getValue(key)
                                .jsonObject
                                .getValue("$" + "bytes")
                                .jsonPrimitive.content,
                        ),
                    )
                IdentityAdoptionSealedCopyView(1, value.getValue("keyId").jsonPrimitive.content, body("nonce"), body("ciphertext"))
            },
            blobs,
        )
}
