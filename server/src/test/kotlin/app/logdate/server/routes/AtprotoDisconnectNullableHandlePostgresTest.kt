@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.shared.model.AtprotoConnectionSnapshot
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AtprotoDisconnectNullableHandlePostgresTest {
    @Test
    fun `null user handle disconnects with a verified registered passkey`() = disconnect("null")

    @Test
    fun `omitted user handle disconnects with a verified registered passkey`() = disconnect("absent")

    @Test
    fun `null user handle does not bypass signature verification`() = disconnect("null", tampered = true)

    private fun disconnect(
        handle: String,
        tampered: Boolean = false,
    ) = withAtprotoConnectionHttp { f ->
        val status = client.get(BASE) { bearerAuth(f.token) }
        val snapshot = Json.decodeFromString<AtprotoConnectionSnapshot>(status.bodyAsText())
        val begun =
            client.post("$BASE/step-up") {
                bearerAuth(f.token)
                contentType(ContentType.Application.Json)
                setBody("{\"expectedRevision\":${snapshot.revision}}")
            }
        assertEquals(HttpStatusCode.OK, begun.status)
        val decision = Json.decodeFromString<AtprotoDisconnectChallenge>(begun.bodyAsText())
        val credential = f.key.assert(decision.authenticationOptions.challenge, tamperSignature = tampered)
        val request = AtprotoDisconnectRequest(decision.decisionId, decision.authenticationOptions.challenge, credential)
        val fields = Json.parseToJsonElement(Json.encodeToString(request)).jsonObject.toMutableMap()
        val assertion = fields.getValue("credential").jsonObject.toMutableMap()
        val response = assertion.getValue("response").jsonObject.toMutableMap()
        if (handle == "absent") response.remove("userHandle") else response["userHandle"] = JsonNull
        assertion["response"] = JsonObject(response)
        fields["credential"] = JsonObject(assertion)
        val completed =
            client.delete(BASE) {
                bearerAuth(f.token)
                contentType(ContentType.Application.Json)
                setBody(JsonObject(fields).toString())
            }
        if (tampered) {
            assertEquals(HttpStatusCode.Forbidden, completed.status)
            assertNotNull(f.provider.sessions.get(f.oauthDid))
        } else {
            assertEquals(HttpStatusCode.OK, completed.status)
            val result = Json.decodeFromString<AtprotoDisconnectResponse>(completed.bodyAsText())
            assertEquals(AtprotoConnectionState.DISCONNECTED, result.current.state)
            assertNull(f.provider.sessions.get(f.oauthDid))
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth(f.token) }.status)
        assertEquals(listOf(f.key.credentialIdB64), f.webAuthn.getUserCredentials(f.account.id))
    }

    private companion object {
        const val BASE = "/api/v1/auth/atproto/connection"
    }
}
