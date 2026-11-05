@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.routes.identityAdoptionRoutes
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionActionView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import app.logdate.shared.model.identity.IdentityAdoptionMediaBeginRequest
import app.logdate.shared.model.identity.IdentityAdoptionMediaResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaSourceRequest
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadProof
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadView
import app.logdate.shared.model.identity.IdentityAdoptionPhaseView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReferenceView
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlin.test.assertNotNull

class IdentityAdoptionPrivateMediaRoutesPostgresTest {
    @Test fun realAccountRoutesStreamPrivateCiphertextAndExposeOnlyTheVerifiedReceipt() =
        withPrivateAdoptionMedia {
            val fixture = this
            val tokens = JwtTokenService("synthetic-private-media-secret")
            val token = tokens.generateAccessToken(current.binding.accountId.toString())
            val access = PostgreSQLIdentityAdoptionCopyAccess(auth.source, { limit }, auth.clock, privateMedia = ingress())
            val expected =
                current.binding.let {
                    IdentityAdoptionExpectedOperation(it.deviceId, it.sourceDid, it.targetDid, it.sourceEpoch, current.revision)
                }
            val phase = IdentityAdoptionPhaseView.valueOf(current.phase.name)

            suspend fun proof(action: String) =
                ticket(action).let {
                    IdentityAdoptionMediaUploadProof(
                        expected,
                        phase,
                        IdentityAdoptionActionChallengeView(
                            it.id.toString(),
                            it.nonce,
                            IdentityAdoptionActionView.valueOf(it.action.name),
                            it.bodyDigest,
                        ),
                        auth.actionSignature(it, current),
                        upload.uploadId,
                    )
                }
            val beginTicket =
                assertNotNull(
                    auth.actions.issue(
                        current,
                        app.logdate.server.identity.adoption.IdentityAdoptionAction.BEGIN_BLOB,
                        mediaUploadDigest(upload),
                        auth.clock.now(),
                    ),
                )
            val begin =
                IdentityAdoptionMediaBeginRequest(
                    expected,
                    phase,
                    IdentityAdoptionActionChallengeView(
                        beginTicket.id.toString(),
                        beginTicket.nonce,
                        IdentityAdoptionActionView.BEGIN_BLOB,
                        beginTicket.bodyDigest,
                    ),
                    auth.actionSignature(beginTicket, current),
                    IdentityAdoptionMediaUploadView(
                        upload.uploadId,
                        IdentityAdoptionSourceReferenceView(IdentityAdoptionSourceKindView.SYNC_MEDIA, upload.source.key),
                        upload.sourceDigest,
                        upload.cid,
                        upload.size,
                    ),
                )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        identityAdoptionRoutes(
                            PostgreSQLAccountRepository(),
                            tokens,
                            fixture.auth.operations,
                            AuthMetricsRegistry(),
                            enabled = true,
                            copyAccess = access,
                        )
                    }
                }
                val prefix = "/account/identity-adoption/${fixture.current.id}"
                val sourceChallenge =
                    assertNotNull(
                        fixture.auth.actions.issue(
                            fixture.current,
                            app.logdate.server.identity.adoption.IdentityAdoptionAction.READ_STAGED,
                            mediaSourceDigest(upload.source, upload.sourceDigest),
                            fixture.auth.clock.now(),
                        ),
                    )
                val sourceProof =
                    IdentityAdoptionMediaSourceRequest(
                        expected,
                        phase,
                        IdentityAdoptionActionChallengeView(
                            sourceChallenge.id.toString(),
                            sourceChallenge.nonce,
                            IdentityAdoptionActionView.READ_STAGED,
                            sourceChallenge.bodyDigest,
                        ),
                        fixture.auth.actionSignature(sourceChallenge, fixture.current),
                        IdentityAdoptionSourceReferenceView(IdentityAdoptionSourceKindView.SYNC_MEDIA, upload.source.key),
                        upload.sourceDigest,
                    )
                val original =
                    client.post("$prefix/read-source-media") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(sourceProof))
                    }
                assertEquals(HttpStatusCode.OK, original.status, original.bodyAsText())
                assertEquals("stored", original.headers["X-LogDate-Adoption-Media-Encoding"])
                assertEquals(upload.sourceDigest, original.headers["X-LogDate-Adoption-Source-Digest"])
                val originalHash =
                    java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(byteArrayOf(1, 2, 3)),
                    )
                assertEquals(originalHash, original.headers["X-LogDate-Adoption-Media-SHA256"])
                assertEquals(listOf<Byte>(1, 2, 3), original.bodyAsBytes().toList())
                val admitted =
                    client.post("$prefix/begin-private-blob") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(begin))
                    }
                assertEquals(HttpStatusCode.OK, admitted.status, admitted.bodyAsText())
                assertEquals(
                    "ADMITTED",
                    Json
                        .decodeFromString<IdentityAdoptionMediaUploadResponse>(admitted.bodyAsText())
                        .data.upload.state,
                )
                val writeProof = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(proof("write")).toByteArray())
                val stored =
                    client.put("$prefix/private-blob") {
                        bearerAuth(token)
                        contentType(ContentType.Application.OctetStream)
                        header("X-LogDate-Adoption-Proof", writeProof)
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
                assertEquals(
                    "STORED",
                    Json
                        .decodeFromString<IdentityAdoptionMediaUploadResponse>(stored.bodyAsText())
                        .data.upload.state,
                )
                val verified =
                    client.post("$prefix/verify-private-blob") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(proof("verify")))
                    }
                assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
                assertEquals(
                    upload.cid,
                    Json
                        .decodeFromString<IdentityAdoptionMediaResponse>(verified.bodyAsText())
                        .data.media.cid,
                )
                val read =
                    client.post("$prefix/read-private-blob") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(proof("read")))
                    }
                assertEquals(HttpStatusCode.OK, read.status)
                assertEquals(bytes.toList(), read.bodyAsBytes().toList())
                val bad =
                    client.put("$prefix/private-blob") {
                        bearerAuth(token)
                        header("X-LogDate-Adoption-Proof", "a".repeat(16385))
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.BadRequest, bad.status)
            }
        }
}
