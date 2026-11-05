@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionMediaService
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.server.space.SpaceBlob
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.identity.IdentityAdoptionActionResponse
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionCopyStartRequest
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionMediaResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaReuseRequest
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.space.SpaceRef
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DagCborCodec
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class IdentityAdoptionMediaRoutesPostgresTest {
    @Test fun privateMediaIsVerifiedThroughTheAuthenticatedDeviceSignedTransport() = exercise(Scenario.HELD)

    @Test fun preparationCanVerifyMediaBeforeHoldingWrites() = exercise(Scenario.PREPARING)

    @Test fun anonymousRequestsCannotIssueMediaAuthority() = exercise(Scenario.ANONYMOUS)

    @Test fun linkedDeviceSessionsCannotIssueMediaAuthority() = exercise(Scenario.LINKED)

    @Test fun disabledTransportCannotIssueMediaAuthority() = exercise(Scenario.DISABLED)

    @Test fun invalidSignaturesCannotReadProviderObjects() = exercise(Scenario.SIGNATURE)

    @Test fun changedCiphertextCannotBecomeVerifiedMedia() = exercise(Scenario.CORRUPT)

    @Test fun unrelatedBlobsCannotUseTheSameMediaChallenge() = exercise(Scenario.CID)

    private enum class Scenario { HELD, PREPARING, ANONYMOUS, LINKED, DISABLED, SIGNATURE, CORRUPT, CID }

    private fun exercise(scenario: Scenario) =
        withIdentityAdoptionAuthorization {
            val auth = this
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    check(it.executeUpdate() == 1)
                }
            }
            val storage = InMemoryLogDateBlobStorage()
            val bytes = ByteArray(65_537) { (it * 13).toByte() }
            val cid = Cid.rawSha256(bytes).value
            val path =
                storage.putBlob(
                    LogDateBlobWriteRequest(
                        operation.binding.accountId.toJavaUUID(),
                        LogDateBlobNamespace.SPACE,
                        Uuid.random().toString(),
                        contentType = "application/octet-stream",
                        bytes = bytes,
                    ),
                )
            val space = SpaceRef.personal(operation.binding.sourceDid).uri
            val spaces = PostgreSQLSpaceStore()
            spaces.putBlob(
                operation.binding.accountId.toJavaUUID(),
                space,
                SpaceBlob(cid, "application/octet-stream", bytes.size.toLong(), path),
            )
            authorize()
            val current =
                if (scenario == Scenario.PREPARING) {
                    operation
                } else {
                    (
                        operations.transition(
                            operation.binding.accountId,
                            operation.id,
                            operation.revision,
                            IdentityAdoptionPhase.FENCED,
                            clock.now(),
                        )
                            as IdentityAdoptionOutcome.Accepted
                    ).value
                }
            var opens = 0
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        opens++
                        return if (scenario == Scenario.CORRUPT) {
                            LogDateBlobStream(
                                bytes.size.toLong(),
                                ByteArrayInputStream(ByteArray(bytes.size)),
                            )
                        } else {
                            storage.openBlob(storagePath)
                        }
                    }
                }
            val access =
                PostgreSQLIdentityAdoptionCopyAccess(
                    source,
                    { Long.MAX_VALUE },
                    clock,
                    IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(source), guarded, clock),
                )
            val tokens = JwtTokenService("synthetic-media-secret")
            val token =
                when (scenario) {
                    Scenario.ANONYMOUS -> null
                    Scenario.LINKED ->
                        tokens.generateDeviceAccessToken(
                            current.binding.accountId.toString(),
                            current.binding.sourceDid,
                            "linked",
                        )
                    else -> tokens.generateAccessToken(current.binding.accountId.toString())
                }
            val expected =
                current.binding.let {
                    IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, current.revision)
                }
            val phase = IdentityAdoptionPhaseView.valueOf(current.phase.name)
            val digest =
                stagingHash(
                    DagCborCodec.encode(
                        buildJsonObject {
                            put("version", 1)
                            put("cid", cid)
                        },
                    ),
                )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            PostgreSQLAccountRepository(),
                            tokens,
                            auth.operations,
                            AuthMetricsRegistry(),
                            enabled = scenario != Scenario.DISABLED,
                            copyAccess = access,
                        )
                    }
                }

                suspend fun send(
                    action: String,
                    body: String,
                ) = client.post("/account/identity-adoption/${current.id}/$action") {
                    token?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val started =
                    send(
                        "copy-challenge",
                        Json.encodeToString(
                            IdentityAdoptionCopyStartRequest(
                                expected,
                                phase,
                                IdentityAdoptionActionView.BEGIN_BLOB,
                                digest,
                            ),
                        ),
                    )
                val code =
                    when (scenario) {
                        Scenario.DISABLED -> HttpStatusCode.NotFound
                        Scenario.ANONYMOUS -> HttpStatusCode.Unauthorized
                        Scenario.LINKED -> HttpStatusCode.Forbidden
                        else -> HttpStatusCode.OK
                    }
                assertEquals(code, started.status)
                assertEquals("no-store", started.headers[HttpHeaders.CacheControl])
                if (code != HttpStatusCode.OK) {
                    assertEquals(0, opens)
                    return@testApplication
                }
                val challenge = Json.decodeFromString<IdentityAdoptionActionResponse>(started.bodyAsText()).data.challenge
                val proof =
                    IdentityAdoptionActionChallenge(
                        Uuid.parse(challenge.id),
                        challenge.nonce,
                        IdentityAdoptionAction.BEGIN_BLOB,
                        challenge.bodyDigest,
                    )
                val signature = if (scenario == Scenario.SIGNATURE) "invalid" else auth.actionSignature(proof, current)
                val request =
                    IdentityAdoptionMediaReuseRequest(
                        expected,
                        phase,
                        challenge,
                        signature,
                        if (scenario == Scenario.CID) Cid.rawSha256(byteArrayOf(0)).value else cid,
                    )
                val result = send("verify-source-blob", Json.encodeToString(request))
                val resultCode =
                    when (scenario) {
                        Scenario.SIGNATURE -> HttpStatusCode.BadRequest
                        Scenario.CORRUPT, Scenario.CID -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.OK
                    }
                assertEquals(resultCode, result.status)
                assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
                if (resultCode == HttpStatusCode.OK) {
                    val raw = result.bodyAsText()
                    val media = Json.decodeFromString<IdentityAdoptionMediaResponse>(raw).data.media
                    assertEquals(current.id, media.operationId)
                    assertEquals(current.revision, media.generation)
                    assertEquals(cid, media.cid)
                    assertEquals(bytes.size.toLong(), media.size)
                    assertEquals("application/octet-stream", media.mimeType)
                    assertFalse(raw.contains(path))
                    assertEquals(1, opens)
                    assertNull(spaces.getBlob(SpaceRef.personal(current.binding.targetDid).uri, cid))
                    assertEquals(bytes.size.toLong(), spaces.storedBytes(current.binding.accountId.toJavaUUID()))
                } else if (scenario != Scenario.CORRUPT) {
                    assertEquals(0, opens)
                }
            }
        }
}
