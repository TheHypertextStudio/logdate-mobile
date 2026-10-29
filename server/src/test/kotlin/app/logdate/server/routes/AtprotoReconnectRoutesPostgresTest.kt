package app.logdate.server.routes

import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.BlueskyStartResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AtprotoReconnectRoutesPostgresTest {
    @Test
    fun `authenticated reconnect returns only current connection state`() =
        withAtprotoConnectionHttp { f ->
            val verifier = "v".repeat(64)
            val challenge =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
                )
            val start =
                client.post("$BASE/start") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"challenge\":\"$challenge\"}")
                }
            assertEquals(HttpStatusCode.OK, start.status)
            val begun = Json.decodeFromString<BlueskyStartResponse>(start.bodyAsText())
            f.provider.service(f.oauth).callback(f.provider.atmosphere.approve(begun.authorizationUrl))
            val complete =
                client.post("$BASE/complete") {
                    bearerAuth(f.token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"attemptId\":\"${begun.attemptId}\",\"verifier\":\"$verifier\"}")
                }
            assertEquals(HttpStatusCode.OK, complete.status)
            assertEquals(AtprotoConnectionState.CONNECTED, Json.decodeFromString<AtprotoConnectionSnapshot>(complete.bodyAsText()).state)
            assertFalse(complete.bodyAsText().contains("accessToken"))
            assertFalse(complete.bodyAsText().contains("refreshToken"))
        }

    @Test
    fun `reconnect routes require ordinary authentication and bounded app proof`() =
        withAtprotoConnectionHttp { f ->
            for (path in listOf("start", "complete")) {
                assertEquals(HttpStatusCode.Unauthorized, client.post("$BASE/$path").status)
                val invalid =
                    client.post("$BASE/$path") {
                        bearerAuth(f.token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
            }
        }

    private companion object {
        const val BASE = "/api/v1/auth/atproto/connection/reconnect"
    }
}
