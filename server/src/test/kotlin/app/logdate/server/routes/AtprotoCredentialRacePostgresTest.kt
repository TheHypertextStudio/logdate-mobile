@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AtprotoCredentialRacePostgresTest {
    @Test
    fun `passkey removal waits for disconnect across distinct account lock instances`() {
        val attempt = CompletableDeferred<Unit>()
        val acquired = CompletableDeferred<Unit>()
        withAtprotoConnectionHttp(
            onRemovalLockAttempt = { attempt.complete(Unit) },
            onRemovalLockAcquired = { acquired.complete(Unit) },
        ) { f ->
            withTimeout(10_000) {
                coroutineScope {
                    val held = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    f.provider.atmosphere.beforeRequest = { request ->
                        if (request.url.encodedPath == "/oauth/revoke") {
                            held.complete(Unit)
                            release.await()
                        }
                    }
                    val begin =
                        client.post("$BASE/step-up") {
                            bearerAuth(f.token)
                            contentType(ContentType.Application.Json)
                            setBody("{\"expectedRevision\":1}")
                        }
                    val challenge = Json.decodeFromString<AtprotoDisconnectChallenge>(begin.bodyAsText())
                    val disconnect =
                        async {
                            client.delete(BASE) {
                                bearerAuth(f.token)
                                contentType(ContentType.Application.Json)
                                setBody(
                                    Json.encodeToString(
                                        AtprotoDisconnectRequest(
                                            challenge.decisionId,
                                            challenge.authenticationOptions.challenge,
                                            f.key.assert(challenge.authenticationOptions.challenge),
                                        ),
                                    ),
                                )
                            }
                        }
                    held.await()
                    val removal =
                        async {
                            client.delete("/api/v1/auth/me/passkeys/${f.key.credentialIdB64}") { bearerAuth(f.token) }
                        }
                    try {
                        attempt.await()
                        assertNull(withTimeoutOrNull(150) { acquired.await() })
                    } finally {
                        release.complete(Unit)
                    }
                    assertEquals(HttpStatusCode.OK, disconnect.await().status)
                    assertEquals(HttpStatusCode.Conflict, removal.await().status)
                    acquired.await()
                    assertEquals(listOf(f.key.credentialIdB64), f.webAuthn.getUserCredentials(f.account.id))
                }
            }
        }
    }

    private companion object {
        const val BASE = "/api/v1/auth/atproto/connection"
    }
}
