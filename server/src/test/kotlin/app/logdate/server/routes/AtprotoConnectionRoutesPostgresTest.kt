@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectBeginRequest
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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AtprotoConnectionRoutesPostgresTest {
    @Test
    fun `every connection operation requires normal first party authentication`() =
        withAtprotoConnectionHttp { _ ->
            assertEquals(HttpStatusCode.Unauthorized, client.get(BASE).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("$BASE/step-up") {
                        contentType(ContentType.Application.Json)
                        setBody("{\"expectedRevision\":1}")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .delete(BASE) {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
            )
        }

    @Test
    fun `a linked sync only device cannot inspect or mutate connection state`() =
        withAtprotoConnectionHttp { f ->
            val token = f.tokens.generateDeviceAccessToken(f.account.id.toString(), f.account.did, Uuid.random().toString())
            val response = client.get(BASE) { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("DEVICE_SCOPE_DENIED"))
        }

    @Test
    fun `connection endpoints return durable state and complete a real fresh passkey disconnect`() =
        withAtprotoConnectionHttp { f ->
            val status = client.get(BASE) { bearerAuth(f.token) }
            assertEquals(HttpStatusCode.OK, status.status)
            val snapshot = Json.decodeFromString<AtprotoConnectionSnapshot>(status.bodyAsText())
            assertEquals(AtprotoConnectionState.CONNECTED, snapshot.state)
            assertTrue(snapshot.canDisconnect)
            assertFalse(status.bodyAsText().contains(f.account.did.orEmpty()))
            val begun =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(AtprotoDisconnectBeginRequest(snapshot.revision)))
                }
            assertEquals(HttpStatusCode.OK, begun.status)
            val challenge = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
            val request =
                AtprotoDisconnectRequest(
                    challenge.decisionId,
                    challenge.authenticationOptions.challenge,
                    f.key.assert(challenge.authenticationOptions.challenge),
                )
            val completed =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(request))
                }
            assertEquals(HttpStatusCode.OK, completed.status)
            val response = Json.decodeFromString<AtprotoDisconnectResponse>(completed.bodyAsText())
            assertTrue(response.receipt.remoteConfirmed)
            assertEquals(AtprotoConnectionState.DISCONNECTED, response.current.state)
            assertNull(f.provider.sessions.get(f.oauthDid))
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth(f.token) }.status)
            assertEquals(listOf(f.key.credentialIdB64), f.webAuthn.getUserCredentials(f.account.id))
        }

    @Test
    fun `a hosted identity is ineligible without an outbound provider request`() =
        withAtprotoConnectionHttp(hosted = true) { f ->
            val before = f.provider.atmosphere.requestLog.size
            val response = client.get(BASE) { bearerAuth(f.token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = Json.decodeFromString<AtprotoConnectionSnapshot>(response.bodyAsText())
            assertFalse(snapshot.eligible)
            assertFalse(snapshot.canDisconnect)
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    @Test
    fun `a stale disconnect generation is a conflict without removing provider access`() =
        withAtprotoConnectionHttp { f ->
            val response =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":0}")
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("CONNECTION_CHANGED"))
            assertTrue(f.provider.sessions.get(f.oauthDid) != null)
        }

    @Test
    fun `provider observation errors expose no raw provider details`() =
        withAtprotoConnectionHttp { f ->
            f.provider.atmosphere.beforeRequest = { throw IOException("private-provider-response-marker") }
            val response = client.get(BASE) { bearerAuth(f.token) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("CONNECTION_UNAVAILABLE"))
            assertFalse(response.bodyAsText().contains("private-provider-response-marker"))
            assertTrue(f.provider.sessions.get(f.oauthDid) != null)
        }

    @Test
    fun `invalid operation identifiers and revisions are rejected before proof or provider work`() =
        withAtprotoConnectionHttp { f ->
            val before = f.provider.atmosphere.requestLog.size
            val invalidRevision =
                client.post("$BASE/step-up") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"expectedRevision\":-1}")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidRevision.status)
            assertTrue(invalidRevision.bodyAsText().contains("VALIDATION_ERROR"))
            val assertion = f.key.assert("unbound")
            val invalidDecision =
                client.delete(BASE) {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(AtprotoDisconnectRequest("invalid", "unbound", assertion)))
                }
            assertEquals(HttpStatusCode.BadRequest, invalidDecision.status)
            assertEquals(before, f.provider.atmosphere.requestLog.size)
        }

    @Test
    fun `caller supplied DID parameters cannot select another accounts connection`() =
        withAtprotoConnectionHttp { f ->
            val bob = f.provider.atmosphere.bob
            val account = f.account.copy(id = Uuid.random(), username = "other_owner", did = bob.did)
            assertTrue(f.accounts.create(account))
            repeat(2) {
                val request = f.oauth.authorize(bob.handle)
                f.oauth.callback(f.provider.atmosphere.approve(request.url, bob))
            }
            val response = client.get("$BASE?did=${bob.did}") { bearerAuth(f.token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1L, Json.decodeFromString<AtprotoConnectionSnapshot>(response.bodyAsText()).revision)
            assertFalse(response.bodyAsText().contains(bob.did))
        }

    private companion object {
        const val BASE = "/api/v1/auth/atproto/connection"
    }
}
