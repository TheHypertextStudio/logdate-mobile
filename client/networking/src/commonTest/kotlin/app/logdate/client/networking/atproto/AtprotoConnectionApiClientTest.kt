package app.logdate.client.networking.atproto

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AtprotoConnectionApiClientTest {
    @Test
    fun `all authenticated operations stay on the fixed origin and own their request pipeline`() =
        runTest {
            val paths = mutableListOf<String>()
            val parent =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("cloud.logdate.app", request.url.host)
                        assertTrue(request.url.parameters.isEmpty())
                        assertEquals("Bearer account-token", request.headers[HttpHeaders.Authorization])
                        assertEquals(null, request.headers[HttpHeaders.Cookie])
                        assertEquals(null, request.headers["X-Ambient-Credential"])
                        paths += request.url.encodedPath
                        val body =
                            when {
                                request.url.encodedPath.endsWith("step-up") ->
                                    """
{
  "decisionId": "$ID",
  "expectedRevision": 4,
  "authenticationOptions": {
    "challenge": "$ID",
    "rpId": "logdate.app",
    "allowCredentials": [
      "AQ"
    ]
  },
  "expiresAt": "2026-10-27T00:05:00Z"
}
"""
                                request.url.encodedPath.endsWith("reconnect/start") ->
                                    """{"attemptId":"$ID","authorizationUrl":"https://pds.example/authorize"}"""
                                request.method.value == "DELETE" ->
                                    """
{
  "receipt": {
    "decisionId": "$ID",
    "disconnectedRevision": 3,
    "completedAt": "2026-10-27T00:00:00Z",
    "remoteConfirmed": false
  },
  "current": $SNAPSHOT
}
"""
                                else -> SNAPSHOT
                            }
                        respond(body, HttpStatusCode.OK, JSON)
                    },
                ) {
                    install(DefaultRequest) {
                        header("X-Ambient-Credential", "ambient-secret")
                        header(HttpHeaders.Cookie, "ambient-cookie")
                    }
                }
            val api = AtprotoConnectionApiClient(parent, ORIGIN)
            try {
                assertEquals(4L, api.snapshot("account-token").getOrThrow().revision)
                assertEquals(4L, api.beginDisconnect("account-token", 4).getOrThrow().expectedRevision)
                assertEquals(
                    4L,
                    api
                        .disconnect("account-token", PROOF)
                        .getOrThrow()
                        .current.revision,
                )
                assertEquals(ID, api.startReconnect("account-token", ID).getOrThrow().attemptId)
                assertEquals(4L, api.completeReconnect("account-token", ID, ID).getOrThrow().revision)
                assertEquals(
                    listOf("", "/step-up", "", "/reconnect/start", "/reconnect/complete").map { "/api/v1/auth/atproto/connection$it" },
                    paths,
                )
            } finally {
                api.close()
                parent.close()
            }
        }

    @Test
    fun `redirects cannot forward bearer or action proof and parent remains usable`() =
        runTest {
            val hosts = mutableListOf<String>()
            val parent =
                HttpClient(
                    MockEngine { request ->
                        hosts += request.url.host
                        if (request.url.host ==
                            "cloud.logdate.app"
                        ) {
                            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://other.example/collect"))
                        } else {
                            respond("okay")
                        }
                    },
                )
            val api = AtprotoConnectionApiClient(parent, ORIGIN)
            assertTrue(api.disconnect("account-token", PROOF).isFailure)
            assertEquals(listOf("cloud.logdate.app"), hosts)
            api.close()
            assertEquals(HttpStatusCode.OK, parent.get("https://parent.example/health").status)
            parent.close()
        }

    @Test
    fun `malformed oversized or inconsistent state is never published`() =
        runTest {
            for (body in listOf(
                "private-provider-message",
                "x".repeat(65537),
                SNAPSHOT.replace("\"revision\": 4", "\"revision\": -1"),
                SNAPSHOT.replace("connected", "future"),
            )) {
                val parent = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, JSON) })
                val api = AtprotoConnectionApiClient(parent, ORIGIN)
                try {
                    assertTrue(api.snapshot("account-token").isFailure)
                } finally {
                    api.close()
                    parent.close()
                }
            }
        }

    @Test
    fun `server decisions remain distinct and discard private provider diagnostics`() =
        runTest {
            for ((status, code, expected) in listOf(
                Triple(401, "UNAUTHORIZED", "UNAUTHORIZED"),
                Triple(403, "DEVICE_SCOPE_DENIED", "UNAUTHORIZED"),
                Triple(403, "CONNECTION_PROOF_INVALID", "PROOF_INVALID"),
                Triple(409, "CONNECTION_CHANGED", "CHANGED"),
                Triple(409, "LAST_SIGNIN_FACTOR", "LAST_SIGN_IN_FACTOR"),
                Triple(409, "CONNECTION_INELIGIBLE", "INELIGIBLE"),
                Triple(410, "CONNECTION_PROOF_EXPIRED", "PROOF_EXPIRED"),
                Triple(410, "CONNECTION_RECONNECT_INVALID", "PROOF_EXPIRED"),
                Triple(429, "RATE_LIMIT_EXCEEDED", "RATE_LIMITED"),
            )) {
                val parent =
                    HttpClient(
                        MockEngine {
                            respond(
                                """{"error":{"code":"$code","message":"private-provider-diagnostic"}}""",
                                HttpStatusCode.fromValue(status),
                                JSON,
                            )
                        },
                    )
                val api = AtprotoConnectionApiClient(parent, ORIGIN)
                try {
                    val failure = assertIs<AtprotoConnectionApiException>(api.snapshot("account-token").exceptionOrNull())
                    assertEquals(expected, failure.kind.name)
                    assertFalse(failure.toString().contains("private-provider-diagnostic"))
                    assertEquals(null, failure.cause)
                } finally {
                    api.close()
                    parent.close()
                }
            }
        }

    @Test
    fun `unsafe origins and unlock material fail before transport while cancellation propagates`() =
        runTest {
            val parent = HttpClient(MockEngine { throw CancellationException("private-token") })
            try {
                for (origin in listOf("http://cloud.logdate.app", "$ORIGIN/path", "https://name:secret@cloud.logdate.app", "$ORIGIN?x=1")) {
                    assertFailsWith<IllegalArgumentException> { AtprotoConnectionApiClient(parent, origin) }
                }
                val api = AtprotoConnectionApiClient(parent, ORIGIN)
                try {
                    assertTrue(api.disconnect("account-token", PROOF.dropLast(1) + ",\"unlockSecret\":\"journal-key\"}").isFailure)
                    assertFailsWith<CancellationException> { api.snapshot("account-token") }
                } finally {
                    api.close()
                }
            } finally {
                parent.close()
            }
        }

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
        val ID = "A".repeat(43)
        const val SNAPSHOT = """
{
  "state": "connected",
  "revision": 4,
  "observedAt": "2026-10-27T00:00:00Z",
  "eligible": true,
  "canDisconnect": true
}
"""
        val PROOF = """
{
  "decisionId": "$ID",
  "challenge": "$ID",
  "credential": {
    "id": "AQ",
    "rawId": "AQ",
    "type": "public-key",
    "response": {
      "clientDataJSON": "e30",
      "authenticatorData": "AQ",
      "signature": "AQ",
      "userHandle": ""
    }
  }
}
"""
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
