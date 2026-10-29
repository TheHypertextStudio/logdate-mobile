package app.logdate.client.networking.bluesky

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
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

class BlueskyApiClientTest {
    @Test
    fun `all account steps stay on the fixed backend without credentials in URLs`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    assertEquals("https://staging.logdate.app", "${request.url.protocol.name}://${request.url.host}")
                    assertTrue(request.url.parameters.isEmpty())
                    assertEquals(null, request.headers[HttpHeaders.Authorization])
                    paths.add(request.url.encodedPath)
                    val requestBody = request.body.toByteArray().decodeToString()
                    assertTrue(requestBody.startsWith("{"))
                    val body =
                        when (request.url.encodedPath.substringAfter("/auth/bluesky/")) {
                            "start" -> """{"attemptId":"${"a".repeat(
                                43,
                            )}","authorizationUrl":"https://provider.example/authorize?request_uri=opaque"}"""
                            "exchange" -> """{"continuationToken":"proof-secret","nextStep":"create_account"}"""
                            "registration/begin" ->
                                """
{
  "continuationToken":"register-secret",
  "options":{
    "challenge":"x",
    "rpId":"logdate.app",
    "rpName":"LogDate",
    "user":{
      "id":"owner",
      "name":"alice",
      "displayName":"Alice"
    }
  }
}
                                """.trimIndent()
                            "authentication/begin" ->
                                """
                                {
                                  "continuationToken":"authenticate-secret",
                                  "options":{
                                    "challenge":"x",
                                    "rpId":"logdate.app"
                                  }
                                }
                                """.trimIndent()
                            else -> SESSION
                        }
                    respond(body, HttpStatusCode.OK, JSON)
                }
            val parent = HttpClient(engine)
            val api = BlueskyApiClient(parent, "https://staging.logdate.app")
            try {
                assertTrue(api.start(BlueskyStartRequest("alice.example", "a".repeat(43))).isSuccess)
                assertEquals(
                    BlueskyAccountNextStep.CREATE_ACCOUNT,
                    api.exchange(BlueskyExchangeRequest("a".repeat(43), "private-verifier")).getOrThrow().nextStep,
                )
                assertEquals(
                    "register-secret",
                    api
                        .beginRegistration(
                            BlueskyRegistrationBeginRequest("proof-secret", OWNER, "alice", "Alice"),
                        ).getOrThrow()
                        .continuationToken,
                )
                val registration = PasskeyRegistrationResponse("id", "id", AuthenticatorAttestationResponse("client", "attestation"))
                val created = api.completeRegistration(BlueskyRegistrationCompleteRequest("register-secret", registration)).getOrThrow()
                assertEquals(AccountIdentityKind.EXTERNAL, created.account.identityKind)
                assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, created.account.storageMode)
                assertEquals(OWNER, created.account.id.toString())
                assertEquals(
                    "authenticate-secret",
                    api.beginAuthentication(BlueskyAuthenticationBeginRequest("proof-secret")).getOrThrow().continuationToken,
                )
                val assertion =
                    PasskeyAuthenticationResponse("id", "id", AuthenticatorAssertionResponse("client", "auth", "signature", null))
                assertEquals(
                    "access-secret",
                    api
                        .completeAuthentication(
                            BlueskyAuthenticationCompleteRequest("authenticate-secret", assertion),
                        ).getOrThrow()
                        .tokens.accessToken,
                )
                assertEquals(
                    listOf(
                        "start",
                        "exchange",
                        "registration/begin",
                        "registration/complete",
                        "authentication/begin",
                        "authentication/complete",
                    ).map {
                        "/api/v1/auth/bluesky/$it"
                    },
                    paths,
                )
            } finally {
                api.close()
                parent.close()
            }
        }

    @Test
    fun `redirects never forward the app proof and closing the API does not close its parent`() =
        runTest {
            val requests = mutableListOf<String>()
            val parent =
                HttpClient(
                    MockEngine { request ->
                        requests.add(request.url.host)
                        if (request.url.host ==
                            "cloud.logdate.app"
                        ) {
                            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://evil.example/collect"))
                        } else {
                            respond("okay")
                        }
                    },
                )
            val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
            val result = api.exchange(BlueskyExchangeRequest("a".repeat(43), "private-verifier"))
            assertTrue(result.isFailure)
            assertEquals(listOf("cloud.logdate.app"), requests)
            api.close()
            assertEquals(HttpStatusCode.OK, parent.get("https://parent.example/health").status)
            parent.close()
        }

    @Test
    fun `known errors are bounded categories while provider text and causes are discarded`() =
        runTest {
            for ((status, code, expected) in listOf(
                Triple(400, "BLUESKY_CANCELED", BlueskyApiFailure.CANCELED),
                Triple(401, "BLUESKY_HANDOFF_EXPIRED", BlueskyApiFailure.EXPIRED),
                Triple(409, "ACCOUNT_LINK_CONFLICT", BlueskyApiFailure.CONFLICT),
                Triple(401, "PASSKEY_VERIFICATION_FAILED", BlueskyApiFailure.PASSKEY_FAILED),
                Triple(429, "RATE_LIMIT_EXCEEDED", BlueskyApiFailure.RATE_LIMITED),
                Triple(400, "VALIDATION_ERROR", BlueskyApiFailure.INVALID_REQUEST),
                Triple(503, "BLUESKY_UNAVAILABLE", BlueskyApiFailure.UNAVAILABLE),
            )) {
                val parent =
                    HttpClient(
                        MockEngine {
                            respond(
                                """{"error":{"code":"$code","message":"private-provider-marker"}}""",
                                HttpStatusCode.fromValue(status),
                                JSON,
                            )
                        },
                    )
                val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
                val failure =
                    assertIs<BlueskyApiException>(
                        api.exchange(BlueskyExchangeRequest("a".repeat(43), "private-verifier")).exceptionOrNull(),
                    )
                assertEquals(expected, failure.kind)
                assertFalse(failure.toString().contains("private-provider-marker"))
                assertEquals(null, failure.cause)
                api.close()
                parent.close()
            }
        }

    @Test
    fun `cancellation propagates without becoming a sign-in error`() =
        runTest {
            val parent = HttpClient(MockEngine { throw CancellationException("private-marker") })
            val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
            assertFailsWith<CancellationException> { api.start(BlueskyStartRequest("alice.example", "a".repeat(43))) }
            api.close()
            parent.close()
        }

    @Test
    fun `backend origin must be a fixed HTTPS origin before any request`() {
        val parent = HttpClient(MockEngine { error("No request expected") })
        try {
            for (origin in listOf(
                "http://cloud.logdate.app",
                "https://cloud.logdate.app/path",
                "https://name:secret@cloud.logdate.app",
                "https://cloud.logdate.app?next=evil",
                "https://cloud.logdate.app#",
            )) {
                assertFailsWith<IllegalArgumentException> { BlueskyApiClient(parent, origin) }
            }
        } finally {
            parent.close()
        }
    }

    @Test
    fun `browser authorization cannot use an unsafe destination or malformed attempt`() =
        runTest {
            for ((attempt, address) in listOf(
                "short" to "https://provider.example/authorize",
                "a".repeat(43) to "javascript:alert(1)",
                "a".repeat(43) to "http://provider.example/authorize",
                "a".repeat(43) to "https://name:secret@provider.example/authorize",
                "a".repeat(43) to "https://provider.example/authorize#private-marker",
            )) {
                val parent =
                    HttpClient(
                        MockEngine { respond("""{"attemptId":"$attempt","authorizationUrl":"$address"}""", HttpStatusCode.OK, JSON) },
                    )
                val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
                val failure =
                    assertIs<BlueskyApiException>(api.start(BlueskyStartRequest("alice.example", "a".repeat(43))).exceptionOrNull())
                assertEquals(BlueskyApiFailure.INVALID_RESPONSE, failure.kind)
                api.close()
                parent.close()
            }
        }

    @Test
    fun `malformed oversized and nonexternal session responses cannot create a local account`() =
        runTest {
            val registration = PasskeyRegistrationResponse("id", "id", AuthenticatorAttestationResponse("client", "attestation"))
            for (body in listOf(
                "not JSON private-marker",
                "x".repeat(1024 * 1024 + 1),
                SESSION.replace("\"success\":true", "\"success\":false"),
                SESSION.replace("\"id\":\"$OWNER\",", ""),
                SESSION.replace("\"did\":\"did:plc:ewvi7nxzyoun6zhxrhs64oiz\"", "\"did\":null"),
                SESSION.replace("\"identityKind\":\"external\"", "\"identityKind\":\"hosted\""),
                SESSION.replace("\"identityKind\":\"external\"", "\"identityKind\":\"future\""),
                SESSION.replace("\"storageMode\":\"personalSpaceV1\"", "\"storageMode\":\"legacy\""),
            )) {
                val parent = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, JSON) })
                val api = BlueskyApiClient(parent, "https://cloud.logdate.app")
                val failure =
                    assertIs<BlueskyApiException>(
                        api.completeRegistration(BlueskyRegistrationCompleteRequest("secret", registration)).exceptionOrNull(),
                    )
                assertEquals(BlueskyApiFailure.INVALID_RESPONSE, failure.kind)
                assertFalse(failure.toString().contains("private-marker"))
                api.close()
                parent.close()
            }
        }

    private companion object {
        const val OWNER = "745d7803-013a-4bea-9605-99ac3b27dbe7"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
        val SESSION =
            """
{
  "success":true,
  "data":{
    "account":{
      "id":"$OWNER",
      "username":"alice",
      "displayName":"Alice",
      "did":"did:plc:ewvi7nxzyoun6zhxrhs64oiz",
      "createdAt":"2026-10-25T00:00:00Z",
      "updatedAt":"2026-10-25T00:00:00Z",
      "storageMode":"personalSpaceV1",
      "identityKind":"external"
    },
    "tokens":{
      "accessToken":"access-secret",
      "refreshToken":"refresh-secret"
    }
  }
}
            """.trimIndent()
    }
}
