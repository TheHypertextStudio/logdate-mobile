package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoPasswordService
import app.logdate.server.atproto.AtprotoPdsSessionService
import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InMemoryAtprotoPasswordCredentialRepository
import app.logdate.server.atproto.InMemoryAtprotoSessionRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.pds.CreateAccountRequest
import studio.hypertext.atproto.pds.CreateSessionRequest
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.SessionInfoResponse
import studio.hypertext.atproto.pds.SessionResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Sign-in is passkey-only, so `createAccount` and `createSession` refuse every password request
 * before any account or credential work, and no session route answers with exception text.
 */
@OptIn(ExperimentalUuidApi::class)
class XrpcPasswordRefusalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `createAccount with a password is refused and creates nothing`() =
        testApplication {
            val environment = configureApp()

            val response = client.postJson("/xrpc/com.atproto.server.createAccount", """{"handle":"newcomer.logdate.app","password":"x"}""")

            assertRefusal(response, "Accounts cannot be created with a password. Create your account in the LogDate app.")
            assertEquals(null, environment.accountRepository.findByHandle("newcomer.logdate.app"))
            assertEquals(emptyList(), environment.accountRepository.getAllAccounts())
        }

    @Test
    fun `createSession with a password is refused for an account that has a password hash`() =
        testApplication {
            val environment = configureApp()
            environment.sessionService
                .createAccount(CreateAccountRequest(handle = "casey.logdate.app", password = "correct-password"))
                .getOrThrow()
            val casey = requireNotNull(environment.accountRepository.findByHandle("casey.logdate.app"))
            assertTrue(environment.passwordService.hasPassword(casey.id))

            listOf("correct-password", "wrong-password").forEach { password ->
                val response =
                    client.postJson(
                        "/xrpc/com.atproto.server.createSession",
                        """{"identifier":"casey.logdate.app","password":"$password"}""",
                    )

                assertRefusal(response, "Password sign-in is not supported. Sign in with a passkey.")
                assertFalse(response.bodyAsText().contains("accessJwt"))
            }
        }

    @Test
    fun `both methods refuse before touching the session service, whatever the body holds`() =
        testApplication {
            val failing = FailingSessionService("PSQLException: duplicate key value violates unique constraint \"accounts_handle_key\"")
            configureApp { failing }

            listOf(
                """{"handle":"newcomer.logdate.app","password":"x"}""",
                """{"handle":"newcomer.logdate.app"}""",
                """{"not":"a session request"}""",
                "this is not json",
                "",
            ).forEach { body ->
                listOf("createAccount", "createSession").forEach { method ->
                    val response = client.postJson("/xrpc/com.atproto.server.$method", body)

                    assertEquals(HttpStatusCode.BadRequest, response.status, "$method $body")
                    assertEquals("InvalidRequest", errorOf(response.bodyAsText()), "$method $body")
                    assertFalse(response.bodyAsText().contains("duplicate key"), "$method $body")
                }
            }
            assertEquals(0, failing.calls)
        }

    @Test
    fun `both methods refuse even when no session service is configured`() =
        testApplication {
            configureApp { null }

            listOf("createAccount", "createSession").forEach { method ->
                val response = client.postJson("/xrpc/com.atproto.server.$method", """{"handle":"a.logdate.app","password":"x"}""")

                assertEquals(HttpStatusCode.BadRequest, response.status, method)
                assertEquals("InvalidRequest", errorOf(response.bodyAsText()), method)
            }
        }

    @Test
    fun `an internal failure on a session route answers a generic body without exception text`() =
        testApplication {
            val failing =
                FailingSessionService(
                    "org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint " +
                        "\"accounts_username_key\" at 10.0.0.5:5432 while running SELECT * FROM accounts",
                )
            configureApp { failing }

            val responses =
                listOf(
                    client.get("/xrpc/com.atproto.server.getSession") { header(HttpHeaders.Authorization, "Bearer token") },
                    client.post("/xrpc/com.atproto.server.refreshSession") { header(HttpHeaders.Authorization, "Bearer token") },
                    client.post("/xrpc/com.atproto.server.deleteSession") { header(HttpHeaders.Authorization, "Bearer token") },
                )

            responses.forEach { response ->
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.InternalServerError, response.status, body)
                assertEquals("InternalServerError", errorOf(body))
                assertEquals("Session request failed", messageOf(body))
                listOf("PSQL", "postgres", "constraint", "SELECT", "10.0.0.5").forEach { leaked ->
                    assertFalse(body.contains(leaked, ignoreCase = true), "$leaked leaked in $body")
                }
            }
            assertEquals(3, failing.calls)
        }

    @Test
    fun `a rejected token still answers InvalidToken`() =
        testApplication {
            configureApp { FailingSessionService("InvalidToken") }

            val response = client.post("/xrpc/com.atproto.server.refreshSession") { header(HttpHeaders.Authorization, "Bearer stale") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("InvalidToken", errorOf(response.bodyAsText()))
        }

    @Test
    fun `fixture exchanges a generated app password without allowing password account creation`() =
        testApplication {
            val credential =
                app.logdate.server.atproto.AtprotoAppPassword
                    .generate()
            val issued =
                SessionResponse(
                    accessJwt = "test-access",
                    refreshJwt = "test-refresh",
                    handle = "casey.logdate.app",
                    did =
                        studio.hypertext.atproto.identity.AtprotoDid
                            .require("did:plc:abcdefghijklmnopqrstuvwx"),
                )
            var attempts = 0
            configureApp(appPasswordLogin = { identifier, password ->
                attempts++
                issued.takeIf { identifier == "casey.logdate.app" && password == credential.value }
            })

            val request = """{"identifier":"CASEY.LOGDATE.APP","password":"${credential.value}"}"""
            val response = client.postJson("/xrpc/com.atproto.server.createSession", request)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("test-access", body["accessJwt"]?.jsonPrimitive?.content)
            assertFalse(response.bodyAsText().contains(credential.value))

            val refused = client.postJson("/xrpc/com.atproto.server.createAccount", request)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertEquals(1, attempts)
        }

    @Test
    fun `the fixture rate limits password attempts without exposing credential eligibility`() =
        testApplication {
            var attempts = 0
            configureApp(appPasswordLogin = { _, _ ->
                attempts++
                null
            })
            val path = "/xrpc/com.atproto.server.createSession"
            val invalid = """{"identifier":"casey.logdate.app","password":"wrong-password"}"""
            val missing = """{"identifier":"missing.logdate.app","password":"wrong-password"}"""

            val invalidResponse = client.postJson(path, invalid)
            val missingResponse = client.postJson(path, missing)
            assertEquals(HttpStatusCode.Unauthorized, invalidResponse.status)
            assertEquals(invalidResponse.bodyAsText(), missingResponse.bodyAsText())
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.postJson(path, invalid).status) }
            assertEquals(HttpStatusCode.TooManyRequests, client.postJson(path, invalid).status)
            assertEquals(12, attempts)
        }

    private suspend fun assertRefusal(
        response: HttpResponse,
        message: String,
    ) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, response.status, body)
        assertEquals("InvalidRequest", errorOf(body))
        assertEquals(message, messageOf(body))
    }

    private fun errorOf(body: String): String? =
        json
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content

    private fun messageOf(body: String): String? =
        json
            .parseToJsonElement(body)
            .jsonObject["message"]
            ?.jsonPrimitive
            ?.content

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
    ): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun ApplicationTestBuilder.configureApp(
        appPasswordLogin: (suspend (String, String) -> SessionResponse?)? = null,
        routedService: (AtprotoPdsSessionService) -> PdsSessionService? = { it },
    ): AppEnvironment {
        val accountRepository = InMemoryAccountRepository()
        val passwordService = AtprotoPasswordService(InMemoryAtprotoPasswordCredentialRepository())
        val identityService =
            AtprotoIdentityService(
                accountRepository = accountRepository,
                signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "password-refusal-kek"),
                config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
            )
        val realService =
            AtprotoPdsSessionService(
                accountRepository = accountRepository,
                identityService = identityService,
                passwordService = passwordService,
                sessionTokenService = AtprotoSessionTokenService(InMemoryAtprotoSessionRepository(), secret = "test"),
            )
        application {
            install(ContentNegotiation) { json(json) }
            routing {
                xrpcRoutes(
                    identityService = identityService,
                    accountRepository = accountRepository,
                    sessionService = routedService(realService),
                    appPasswordLogin = appPasswordLogin,
                )
            }
        }
        return AppEnvironment(accountRepository, passwordService, realService)
    }

    private class AppEnvironment(
        val accountRepository: InMemoryAccountRepository,
        val passwordService: AtprotoPasswordService,
        val sessionService: AtprotoPdsSessionService,
    )

    private class FailingSessionService(
        private val reason: String,
    ) : PdsSessionService {
        var calls = 0

        private fun <T> fail(): Result<T> {
            calls++
            return Result.failure(IllegalStateException(reason))
        }

        override suspend fun createAccount(request: CreateAccountRequest): Result<SessionResponse> = fail()

        override suspend fun createSession(request: CreateSessionRequest): Result<SessionResponse> = fail()

        override suspend fun getSession(accessJwt: String): Result<SessionInfoResponse> = fail()

        override suspend fun refreshSession(refreshJwt: String): Result<SessionResponse> = fail()

        override suspend fun deleteSession(refreshJwt: String): Result<Unit> = fail()
    }
}
