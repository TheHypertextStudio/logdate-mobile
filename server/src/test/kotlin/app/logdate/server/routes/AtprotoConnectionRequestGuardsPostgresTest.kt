@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.database.blueskyTransaction
import app.logdate.server.database.toJavaUUID
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectRequest
import app.logdate.shared.model.AtprotoDisconnectResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AtprotoConnectionRequestGuardsPostgresTest {
    @Test
    fun `oversized or invalid UTF8 bodies are refused before provider work`() =
        withAtprotoConnectionHttp { f ->
            val before = f.provider.atmosphere.requestLog.size
            val oversized =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1,\"padding\":\"" + "x".repeat(70_000) + "\"}")
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
            val invalid =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(byteArrayOf(0xc3.toByte(), 0x28))
                }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    @Test
    fun `another account cannot consume the owners disconnect proof`() =
        withAtprotoConnectionHttp { f ->
            val bob = f.account.copy(id = Uuid.random(), username = "other_owner", did = f.provider.atmosphere.bob.did)
            assertTrue(f.accounts.create(bob))
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1}")
                }
            val challenge = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            val body =
                Json.encodeToString(
                    AtprotoDisconnectRequest(
                        challenge.decisionId,
                        challenge.authenticationOptions.challenge,
                        f.key.assert(challenge.authenticationOptions.challenge),
                    ),
                )
            val refused =
                client.delete(BASE) {
                    bearerAuth(f.tokens.generateAccessToken(bob.id.toString(), bob.did))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertTrue(refused.bodyAsText().contains("CONNECTION_PROOF_INVALID"))
            val accepted =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, accepted.status)
        }

    @Test
    fun `ordinary sign in proof cannot authorize an action decision`() =
        withAtprotoConnectionHttp { f ->
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1}")
                }
            val decision = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            val ordinary = f.webAuthn.generateAuthenticationOptions(f.account.id).challenge
            val response =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(AtprotoDisconnectRequest(decision.decisionId, ordinary, f.key.assert(ordinary))))
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("CONNECTION_PROOF_INVALID"))
            assertTrue(f.provider.sessions.get(f.oauthDid) != null)
        }

    @Test
    fun `expired action decisions have their own bounded response`() =
        withAtprotoConnectionHttp { f ->
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1}")
                }
            val decision = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            f.source.blueskyTransaction {
                prepareStatement(
                    "UPDATE atproto_disconnect_decisions SET created_at = NOW() - INTERVAL '6 minutes', " +
                        "expires_at = NOW() - INTERVAL '1 minute' WHERE account_id = ?",
                ).use {
                    it.setObject(1, f.account.id.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val response =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            AtprotoDisconnectRequest(
                                decision.decisionId,
                                decision.authenticationOptions.challenge,
                                f.key.assert(decision.authenticationOptions.challenge),
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Gone, response.status)
            assertTrue(response.bodyAsText().contains("CONNECTION_PROOF_EXPIRED"))
            assertTrue(f.provider.sessions.get(f.oauthDid) != null)
        }

    @Test
    fun `unknown identity kinds cannot authorize a provider operation`() =
        withAtprotoConnectionHttp { f ->
            f.source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_kind = 'future_identity_kind' WHERE id = ?").use {
                    it.setObject(1, f.account.id.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val before = f.provider.atmosphere.requestLog.size
            assertEquals(HttpStatusCode.Unauthorized, client.get(BASE) { bearerAuth(f.token) }.status)
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    @Test
    fun `an interrupted response can replay its receipt without revoking a newer grant`() =
        withAtprotoConnectionHttp { f ->
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1}")
                }
            val decision = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            val body =
                Json.encodeToString(
                    AtprotoDisconnectRequest(
                        decision.decisionId,
                        decision.authenticationOptions.challenge,
                        f.key.assert(decision.authenticationOptions.challenge),
                    ),
                )
            val first =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val receipt = Json.decodeFromString<AtprotoDisconnectResponse>(first.bodyAsText()).receipt
            val start = f.oauth.authorize(f.provider.atmosphere.alice.handle)
            f.oauth.callback(f.provider.atmosphere.approve(start.url))
            val before = f.provider.atmosphere.revokedTokens.size
            val retried =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, retried.status)
            val current = Json.decodeFromString<AtprotoDisconnectResponse>(retried.bodyAsText())
            assertEquals(receipt, current.receipt)
            assertEquals(3L, current.current.revision)
            assertEquals(AtprotoConnectionState.CONNECTED, current.current.state)
            assertEquals(before, f.provider.atmosphere.revokedTokens.size)
            assertTrue(f.provider.sessions.get(f.oauthDid) != null)
        }

    @Test
    fun `remote failure still returns local completion without claiming confirmation`() =
        withAtprotoConnectionHttp { f ->
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":1}")
                }
            val decision = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            f.provider.atmosphere.revocationFails = true
            val response =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        Json.encodeToString(
                            AtprotoDisconnectRequest(
                                decision.decisionId,
                                decision.authenticationOptions.challenge,
                                f.key.assert(decision.authenticationOptions.challenge),
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.decodeFromString<AtprotoDisconnectResponse>(response.bodyAsText())
            assertFalse(result.receipt.remoteConfirmed)
            assertEquals(AtprotoConnectionState.DISCONNECTED, result.current.state)
            assertNull(f.provider.sessions.get(f.oauthDid))
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth(f.token) }.status)
        }

    @Test
    fun `per account rate limits expose retry timing before further provider work`() =
        withAtprotoConnectionHttp { f ->
            repeat(10) { assertEquals(HttpStatusCode.OK, client.get(BASE) { bearerAuth(f.token) }.status) }
            val before = f.provider.atmosphere.requestLog.size
            val response = client.get(BASE) { bearerAuth(f.token) }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("RATE_LIMIT_EXCEEDED"))
            assertTrue((response.headers["Retry-After"]?.toLongOrNull() ?: 0) > 0)
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    private companion object {
        const val BASE = "/api/v1/auth/atproto/connection"
    }
}
