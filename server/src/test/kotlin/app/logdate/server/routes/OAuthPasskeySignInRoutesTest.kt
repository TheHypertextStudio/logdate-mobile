package app.logdate.server.routes

import app.logdate.server.AuthV1TestEnvironment
import app.logdate.server.auth.Account
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.installForwardedClientAddress
import app.logdate.server.oauth.OAuthClientMetadataResolver
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.oauth.clientMetadataJson
import app.logdate.server.oauth.createDpopProof
import app.logdate.server.oauth.generateP256KeyPair
import app.logdate.server.passkeys.InMemoryPasskeyRepository
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.PasskeyAssertionResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The browser side of AT Protocol OAuth: a person who only has a passkey opens the authorization
 * page another app sent them to, signs in with the passkey, and the app receives a code.
 */
@OptIn(ExperimentalUuidApi::class)
class OAuthPasskeySignInRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `browser sign-in stays closed while the flag is off`() =
        testApplication {
            configureApp(passkeySignInEnabled = false)
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val page = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }
            val options = postJson("/oauth/authorize/passkey/options", buildJsonObject { put("request_uri", requestUri) })

            assertFalse(page.status.isSuccess(), page.status.toString())
            assertFalse(page.contentType()?.match(ContentType.Text.Html) == true)
            assertEquals(HttpStatusCode.NotImplemented, options.status)
        }

    @Test
    fun `a browser without a LogDate session gets the passkey page`() =
        testApplication {
            val env = configureApp()
            createAccount(env, "alice")
            val requestUri = pushAuthorizationRequest(loginHint = "alice.logdate.app").requestUri

            val page = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }

            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
            val body = page.bodyAsText()
            assertTrue("<h1>Sign in to $CLIENT_ID</h1>" in body, body)
            assertFalse("Journal Viewer" in body, "an untrusted app's self-reported name is never shown")
            assertTrue("@alice.logdate.app" in body, body)
            assertTrue("learns which LogDate account you are" in body, body)
            assertFalse("journal data" in body, body)
            assertTrue("Continue with passkey" in body, body)
            val csp = assertNotNull(page.headers["Content-Security-Policy"])
            assertTrue("frame-ancestors 'none'" in csp, csp)
            assertTrue("script-src 'nonce-" in csp, csp)
            assertFalse("unsafe-inline" in csp, csp)
            assertEquals("DENY", page.headers["X-Frame-Options"])
            assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
            assertEquals("no-referrer", page.headers["Referrer-Policy"])
        }

    @Test
    fun `the page escapes a client name it does not control`() =
        testApplication {
            configureApp(clientName = "<script>alert(1)</script>", trusted = true)
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val body = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }.bodyAsText()

            assertFalse("<script>alert(1)</script>" in body, body)
            assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in body, body)
        }

    @Test
    fun `a trusted app is named by its own name and address`() =
        testApplication {
            configureApp(trusted = true)
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val body = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }.bodyAsText()

            assertTrue("<h1>Sign in to Journal Viewer</h1>" in body, body)
            assertTrue("viewer.example.com" in body, body)
        }

    @Test
    fun `the page promises no more than the identity even when the app asks for more`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = null, scope = "atproto transition:generic").requestUri

            val body = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }.bodyAsText()

            assertTrue("learns which LogDate account you are" in body, body)
            assertFalse("upload" in body, body)
        }

    @Test
    fun `a challenge for one request cannot approve another`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val first = pushAuthorizationRequest(loginHint = null).requestUri
            val second = pushAuthorizationRequest(loginHint = null).requestUri

            val challenge = passkeyOptions(first).getValue("challenge").jsonPrimitive.content
            val crossed = approveWithPasskey(second, challenge, passkey.assert(challenge))

            assertEquals(HttpStatusCode.Unauthorized, crossed.status)
            val ownChallenge = passkeyOptions(second).getValue("challenge").jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, approveWithPasskey(second, ownChallenge, passkey.assert(ownChallenge)).status)
        }

    @Test
    fun `an app sign-in challenge cannot approve an authorization request`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val appChallenge = env.webAuthn.generateAuthenticationOptions(userId = alice.id).challenge
            val rejected = approveWithPasskey(requestUri, appChallenge, passkey.assert(appChallenge))

            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        }

    @Test
    fun `an approved request cannot be approved again`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri
            val first = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val second = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content

            assertEquals(HttpStatusCode.OK, approveWithPasskey(requestUri, first, passkey.assert(first)).status)
            val again = approveWithPasskey(requestUri, second, passkey.assert(second))

            assertEquals(HttpStatusCode.BadRequest, again.status)
            assertEquals(
                "invalid_request",
                json
                    .parseToJsonElement(again.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `passkey attempts are rate limited per network`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val statuses =
                (1..11).map {
                    postJson("/oauth/authorize/passkey/options", buildJsonObject { put("request_uri", requestUri) })
                }

            assertTrue(statuses.take(10).all { it.status == HttpStatusCode.OK })
            val limited = statuses.last()
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `deleting a passkey revokes every app the account approved`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val spare = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val grant = approveAndExchange(passkey)

            val delete =
                client.delete("/api/v1/auth/me/passkeys/${spare.credentialIdB64}") {
                    header(HttpHeaders.Authorization, "Bearer ${env.base.tokenService.generateAccessToken(alice.id.toString())}")
                }
            assertEquals(HttpStatusCode.NoContent, delete.status, delete.bodyAsText())

            assertRefreshRefused(grant)
        }

    @Test
    fun `deleting the account revokes every app it approved`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val grant = approveAndExchange(passkey)

            val delete =
                client.delete("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer ${env.base.tokenService.generateAccessToken(alice.id.toString())}")
                }
            assertEquals(HttpStatusCode.NoContent, delete.status, delete.bodyAsText())

            assertRefreshRefused(grant)
        }

    private class AppGrant(
        val pushed: PushedRequest,
        val refreshToken: String,
        val nonce: String?,
    )

    /** Signs in through the page with [passkey] and exchanges the code, as the app would. */
    private suspend fun ApplicationTestBuilder.approveAndExchange(passkey: PasskeyTestAuthenticator): AppGrant {
        val pushed = pushAuthorizationRequest(loginHint = null)
        val challenge = passkeyOptions(pushed.requestUri).getValue("challenge").jsonPrimitive.content
        val redirect =
            Url(
                json
                    .parseToJsonElement(approveWithPasskey(pushed.requestUri, challenge, passkey.assert(challenge)).bodyAsText())
                    .jsonObject
                    .getValue("redirect_uri")
                    .jsonPrimitive.content,
            )
        val token =
            client.post("/oauth/token") {
                header("DPoP", createDpopProof(pushed.dpopKey, "POST", "https://logdate.app/oauth/token", nonce = pushed.nonce))
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", assertNotNull(redirect.parameters["code"]))
                            append("redirect_uri", REDIRECT_URI)
                            append("client_id", CLIENT_ID)
                            append("code_verifier", pushed.verifier)
                        },
                    ),
                )
            }
        check(token.status == HttpStatusCode.OK) { "Token exchange failed: ${token.bodyAsText()}" }
        val refreshToken =
            json
                .parseToJsonElement(token.bodyAsText())
                .jsonObject
                .getValue("refresh_token")
                .jsonPrimitive.content
        return AppGrant(pushed, refreshToken, token.headers["DPoP-Nonce"])
    }

    private suspend fun ApplicationTestBuilder.assertRefreshRefused(grant: AppGrant) {
        val refresh =
            client.post("/oauth/token") {
                header("DPoP", createDpopProof(grant.pushed.dpopKey, "POST", "https://logdate.app/oauth/token", nonce = grant.nonce))
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("grant_type", "refresh_token")
                            append("refresh_token", grant.refreshToken)
                            append("client_id", CLIENT_ID)
                        },
                    ),
                )
            }
        assertEquals(HttpStatusCode.BadRequest, refresh.status, refresh.bodyAsText())
        assertEquals(
            "invalid_grant",
            json
                .parseToJsonElement(refresh.bodyAsText())
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `passkey attempts are limited per client address, not per proxy`() =
        testApplication {
            configureApp()
            application { installForwardedClientAddress() }
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            suspend fun options(forwardedFor: String) =
                client.post("/oauth/authorize/passkey/options") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.XForwardedFor, forwardedFor)
                    setBody(buildJsonObject { put("request_uri", requestUri) }.toString())
                }

            repeat(10) { assertEquals(HttpStatusCode.OK, options("203.0.113.5").status) }
            val spoofed = options("198.51.100.77, 203.0.113.5")
            val otherClient = options("203.0.113.6")

            assertEquals(HttpStatusCode.TooManyRequests, spoofed.status, "an invented leading address must not reset the limit")
            assertEquals(HttpStatusCode.OK, otherClient.status, "another client has its own limit")
        }

    @Test
    fun `app sign-in attempts are limited per client address, not per proxy`() =
        testApplication {
            configureApp()
            application { installForwardedClientAddress() }

            suspend fun begin(forwardedFor: String) =
                client.post("/api/v1/auth/signin/passkey/begin") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.XForwardedFor, forwardedFor)
                    setBody("{}")
                }

            repeat(10) { assertEquals(HttpStatusCode.OK, begin("203.0.113.5").status) }

            assertEquals(HttpStatusCode.TooManyRequests, begin("198.51.100.77, 203.0.113.5").status)
            assertEquals(HttpStatusCode.OK, begin("203.0.113.6").status)
        }

    @Test
    fun `the page refuses a client_id that does not match the pushed request`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val page =
                client.get("/oauth/authorize?client_id=https://other.example.com/client.json&request_uri=$requestUri") { acceptHtml() }

            assertEquals(HttpStatusCode.BadRequest, page.status)
            assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
            assertTrue("start signing in again" in page.bodyAsText(), page.bodyAsText())
        }

    @Test
    fun `the page explains an unknown or expired request`() =
        testApplication {
            configureApp()

            val page = client.get("/oauth/authorize?request_uri=urn:ietf:params:oauth:request_uri:missing") { acceptHtml() }

            assertEquals(HttpStatusCode.BadRequest, page.status)
            assertTrue("expired" in page.bodyAsText(), page.bodyAsText())
        }

    @Test
    fun `the page says when the app asked for an account that is not here`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = "bob.example.com").requestUri

            val page = client.get("/oauth/authorize?client_id=$CLIENT_ID&request_uri=$requestUri") { acceptHtml() }

            assertEquals(HttpStatusCode.BadRequest, page.status)
            assertTrue("@bob.example.com" in page.bodyAsText(), page.bodyAsText())
            assertFalse("Continue with passkey" in page.bodyAsText())
        }

    @Test
    fun `signing in with a passkey hands the app a code for that account`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val authenticator = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val pushed = pushAuthorizationRequest(loginHint = "alice.logdate.app")

            val options = passkeyOptions(pushed.requestUri)
            assertEquals("logdate.app", options["rp_id"]?.jsonPrimitive?.content)
            assertEquals("required", options["user_verification"]?.jsonPrimitive?.content)
            assertEquals(emptyList(), options["allow_credentials"]?.jsonArray?.map { it.jsonPrimitive.content })
            val challenge = options.getValue("challenge").jsonPrimitive.content

            val approval = approveWithPasskey(pushed.requestUri, challenge, authenticator.assert(challenge))

            assertEquals(HttpStatusCode.OK, approval.status, approval.bodyAsText())
            val redirect =
                Url(
                    json
                        .parseToJsonElement(approval.bodyAsText())
                        .jsonObject
                        .getValue("redirect_uri")
                        .jsonPrimitive.content,
                )
            assertEquals(REDIRECT_URI, "${redirect.protocol.name}://${redirect.host}${redirect.encodedPath}")
            assertEquals("state-123", redirect.parameters["state"])
            assertEquals("https://logdate.app", redirect.parameters["iss"])
            val code = assertNotNull(redirect.parameters["code"])

            val token =
                client.post("/oauth/token") {
                    header("DPoP", createDpopProof(pushed.dpopKey, "POST", "https://logdate.app/oauth/token", nonce = pushed.nonce))
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", REDIRECT_URI)
                                append("client_id", CLIENT_ID)
                                append("code_verifier", pushed.verifier)
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, token.status, token.bodyAsText())
            assertEquals(
                alice.did,
                json
                    .parseToJsonElement(token.bodyAsText())
                    .jsonObject["sub"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `a passkey for a different account leaves the request usable`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val bob = createAccount(env, "bob")
            val alicePasskey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val bobPasskey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, bob.id, "bob") }
            val requestUri = pushAuthorizationRequest(loginHint = "bob.logdate.app").requestUri

            val firstChallenge = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val mismatch = approveWithPasskey(requestUri, firstChallenge, alicePasskey.assert(firstChallenge))

            assertEquals(HttpStatusCode.Forbidden, mismatch.status)
            val error = json.parseToJsonElement(mismatch.bodyAsText()).jsonObject
            assertEquals("access_denied", error["error"]?.jsonPrimitive?.content)
            val description = error.getValue("error_description").jsonPrimitive.content
            assertTrue("@alice.logdate.app" in description && "@bob.logdate.app" in description, description)

            val secondChallenge = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val approval = approveWithPasskey(requestUri, secondChallenge, bobPasskey.assert(secondChallenge))
            assertEquals(HttpStatusCode.OK, approval.status, approval.bodyAsText())
        }

    @Test
    fun `an assertion that does not verify is rejected and the request stays usable`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            val passkey = PasskeyTestAuthenticator().also { it.registerWith(env.webAuthn, alice.id, "alice") }
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val firstChallenge = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val rejected = approveWithPasskey(requestUri, firstChallenge, passkey.assert(firstChallenge, tamperSignature = true))
            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
            assertEquals(
                "access_denied",
                json
                    .parseToJsonElement(rejected.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content,
            )

            val secondChallenge = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val approval = approveWithPasskey(requestUri, secondChallenge, passkey.assert(secondChallenge))
            assertEquals(HttpStatusCode.OK, approval.status, approval.bodyAsText())
        }

    @Test
    fun `a passkey from another origin is rejected`() =
        testApplication {
            val env = configureApp()
            val alice = createAccount(env, "alice")
            PasskeyTestAuthenticator().registerWith(env.webAuthn, alice.id, "alice")
            val phishing = PasskeyTestAuthenticator(origin = "https://logdate-login.example.com")
            phishing.registerWithoutOriginCheck(env, alice)
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val challenge = passkeyOptions(requestUri).getValue("challenge").jsonPrimitive.content
            val rejected = approveWithPasskey(requestUri, challenge, phishing.assert(challenge))

            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        }

    @Test
    fun `cancelling sends the browser back to the app with access_denied`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val cancel =
                createClient { followRedirects = false }.post("/oauth/authorize") {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("request_uri", requestUri)
                                append("decision", "deny")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Found, cancel.status)
            val location = Url(assertNotNull(cancel.headers[HttpHeaders.Location]))
            assertEquals("access_denied", location.parameters["error"])
            assertEquals("state-123", location.parameters["state"])
            assertEquals("https://logdate.app", location.parameters["iss"])
            assertEquals(null, location.parameters["code"])
        }

    @Test
    fun `approving without a session still needs a passkey`() =
        testApplication {
            configureApp()
            val requestUri = pushAuthorizationRequest(loginHint = null).requestUri

            val approve =
                createClient { followRedirects = false }.post("/oauth/authorize") {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("request_uri", requestUri)
                                append("decision", "approve")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, approve.status)
        }

    @Test
    fun `turning the page on without strict passkey verification fails at startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                createPasskeySignInService(issuer = "https://logdate.app", relyingPartyId = "logdate.app", strict = false)
            }

        assertTrue("WEBAUTHN_STRICT_VERIFICATION" in assertNotNull(failure.message), failure.message)
    }

    @Test
    fun `turning the page on for an issuer outside the relying party fails at startup`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                createPasskeySignInService(issuer = "https://cloud-staging.logdate.app", relyingPartyId = "staging.logdate.app")
            }

        val message = assertNotNull(failure.message)
        assertTrue("cloud-staging.logdate.app" in message && "staging.logdate.app" in message, message)
    }

    private fun createPasskeySignInService(
        issuer: String,
        relyingPartyId: String,
        strict: Boolean = true,
    ) {
        val accounts =
            app.logdate.server.auth
                .InMemoryAccountRepository()
        val config =
            app.logdate.server.oauth
                .OAuthConfig(issuer = issuer, passkeySignInEnabled = true)
        app.logdate.server.oauth.OAuthPasskeySignInService(
            config = config,
            authorizationService = io.mockk.mockk(),
            webAuthnService =
                WebAuthnPasskeyService(
                    relyingPartyId = relyingPartyId,
                    origins = setOf("https://app.$relyingPartyId", "https://$relyingPartyId"),
                    strictVerificationEnabled = strict,
                ),
            accountRepository = accounts,
            identityService = io.mockk.mockk(),
        )
    }

    private class PushedRequest(
        val requestUri: String,
        val nonce: String,
        val dpopKey: java.security.KeyPair,
        val verifier: String,
    )

    private class PasskeyEnv(
        val base: AuthV1TestEnvironment,
        val webAuthn: WebAuthnPasskeyService,
        val passkeys: InMemoryPasskeyRepository,
    )

    private fun TestApplicationBuilder.configureApp(
        passkeySignInEnabled: Boolean = true,
        clientName: String = "Journal Viewer",
        trusted: Boolean = false,
    ): PasskeyEnv {
        val passkeys = InMemoryPasskeyRepository()
        val webAuthn =
            WebAuthnPasskeyService(
                passkeyRepository = passkeys,
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
            )
        val metadataResolver =
            OAuthClientMetadataResolver(
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            respond(
                                content =
                                    clientMetadataJson(
                                        clientId = request.url.toString(),
                                        redirectUri = REDIRECT_URI,
                                        scope = "atproto transition:generic",
                                    ).replace("\"Journal Viewer\"", Json.encodeToJsonElement(clientName).toString()),
                                status = HttpStatusCode.OK,
                                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                            )
                        },
                    ),
            )
        val base =
            configureAuthV1TestApp(
                webAuthnPasskeyService = webAuthn,
                oauthClientMetadataResolver = metadataResolver,
                oauthPasskeySignInEnabled = passkeySignInEnabled,
                oauthConfig =
                    app.logdate.server.oauth.OAuthConfig(
                        issuer = "https://logdate.app",
                        trustedClientIds = if (trusted) setOf(CLIENT_ID) else emptySet(),
                    ),
            )
        return PasskeyEnv(base, webAuthn, passkeys)
    }

    private fun createAccount(
        env: PasskeyEnv,
        username: String,
    ): Account =
        runBlocking {
            val saved =
                env.base.accountRepository.save(
                    Account(id = Uuid.random(), username = username, displayName = username, createdAt = Clock.System.now()),
                )
            env.base.atprotoIdentityService.ensureIdentity(saved)
        }

    private suspend fun PasskeyTestAuthenticator.registerWithoutOriginCheck(
        env: PasskeyEnv,
        account: Account,
    ) {
        // Registration goes through a relying party that accepts this origin, so only the
        // authorization page's verification is left to reject it.
        val lenient =
            WebAuthnPasskeyService(
                passkeyRepository = env.passkeys,
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate-login.example.com"),
                strictVerificationEnabled = true,
            )
        registerWith(lenient, account.id, account.username)
    }

    private suspend fun ApplicationTestBuilder.pushAuthorizationRequest(
        loginHint: String?,
        scope: String = "atproto",
    ): PushedRequest {
        // Each pushed request needs its own PKCE verifier; the server refuses a reused challenge.
        val verifier = "example-code-verifier-${Uuid.random()}"
        val dpopKey = generateP256KeyPair()
        val response =
            client.post("/oauth/par") {
                header("DPoP", createDpopProof(dpopKey, "POST", "https://logdate.app/oauth/par"))
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", CLIENT_ID)
                            append("response_type", "code")
                            append("scope", scope)
                            append("redirect_uri", REDIRECT_URI)
                            append("code_challenge", pkceChallenge(verifier))
                            append("code_challenge_method", "S256")
                            append("state", "state-123")
                            loginHint?.let { append("login_hint", it) }
                        },
                    ),
                )
            }
        check(response.status == HttpStatusCode.Created) { "PAR failed: ${response.bodyAsText()}" }
        return PushedRequest(
            requestUri =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("request_uri")
                    .jsonPrimitive.content,
            nonce = assertNotNull(response.headers["DPoP-Nonce"]),
            dpopKey = dpopKey,
            verifier = verifier,
        )
    }

    private suspend fun ApplicationTestBuilder.passkeyOptions(requestUri: String): JsonObject {
        val response = postJson("/oauth/authorize/passkey/options", buildJsonObject { put("request_uri", requestUri) })
        check(response.status == HttpStatusCode.OK) { "Passkey options failed: ${response.bodyAsText()}" }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.approveWithPasskey(
        requestUri: String,
        challenge: String,
        credential: PasskeyAssertionResponse,
    ): HttpResponse =
        postJson(
            "/oauth/authorize/passkey",
            buildJsonObject {
                put("request_uri", requestUri)
                put("challenge", challenge)
                put("credential", json.encodeToJsonElement(credential))
            },
        )

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: JsonObject,
    ): HttpResponse =
        client.post(path) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body.toString())
        }

    private fun io.ktor.client.request.HttpRequestBuilder.acceptHtml() {
        header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
    }

    private companion object {
        const val CLIENT_ID = "https://viewer.example.com/client.json"
        const val REDIRECT_URI = "https://viewer.example.com/callback"
    }
}

private fun pkceChallenge(verifier: String): String =
    java.util.Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray()),
        )
