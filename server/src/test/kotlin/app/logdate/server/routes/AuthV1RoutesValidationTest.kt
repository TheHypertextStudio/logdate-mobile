package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.FakeGoogleIdTokenVerifier
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.support.googleAuthBody
import app.logdate.server.routes.support.googleClaims
import app.logdate.server.routes.support.googleClaimsByToken
import app.logdate.server.routes.support.signinPasskeyCompleteBody
import app.logdate.server.routes.support.signupPasskeyBeginBody
import app.logdate.server.routes.support.signupPasskeyCompleteBody
import app.logdate.server.routes.support.signupPasskeyCompleteBodyWithBindingSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Extensive validation and edge-case tests for the V1 Authentication routes.
 *
 * This class focuses on the rigorous validation of registration and authentication
 * inputs, including username and display name constraints, session token integrity,
 * email binding verification, and Google identity normalization. It also tests the
 * system's response to misconfigured authentication providers and unauthorized
 * access attempts across various profile and maintenance endpoints.
 */
@OptIn(ExperimentalUuidApi::class)
class AuthV1RoutesValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `signup passkey begin validates username display name and uniqueness`() =
        testApplication {
            val env = configureAuthV1TestApp()
            runBlocking {
                env.accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "taken_user",
                        displayName = "Taken",
                        email = null,
                        emailVerified = false,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val invalidUsername =
                client.post("/api/v1/auth/signup/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyBeginBody(username = "ab", displayName = "Name"))
                }
            assertEquals(HttpStatusCode.BadRequest, invalidUsername.status)

            val invalidDisplayName =
                client.post("/api/v1/auth/signup/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyBeginBody(username = "valid_user", displayName = ""))
                }
            assertEquals(HttpStatusCode.BadRequest, invalidDisplayName.status)

            val takenUsername =
                client.post("/api/v1/auth/signup/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyBeginBody(username = "taken_user", displayName = "Taken"))
                }
            assertEquals(HttpStatusCode.Conflict, takenUsername.status)
            assertTrue(takenUsername.bodyAsText().contains("USERNAME_TAKEN"))
        }

    @Test
    fun `signup passkey complete validates session token and email binding`() =
        testApplication {
            configureAuthV1TestApp(
                googleClaimsByToken =
                    googleClaimsByToken(
                        "good-google" to
                            googleClaims(
                                subject = "sub",
                                email = "user@example.com",
                                name = "User",
                            ),
                    ),
            )

            val invalidSession =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = "missing", credentialId = "cred-1"))
                }
            assertEquals(HttpStatusCode.Unauthorized, invalidSession.status)

            val begin =
                client.post("/api/v1/auth/signup/passkey/begin") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyBeginBody(username = "bind_user", displayName = "Bind User"))
                }
            assertEquals(HttpStatusCode.OK, begin.status)
            val beginPayload = json.parseToJsonElement(begin.bodyAsText()).jsonObject
            val sessionToken =
                beginPayload["data"]
                    ?.jsonObject
                    ?.get("sessionToken")
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(sessionToken)

            val invalidBinding =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        signupPasskeyCompleteBodyWithBindingSource(
                            sessionToken = sessionToken,
                            credentialId = "cred-2",
                            source = "unsupported",
                            bindingToken = "g-goog",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, invalidBinding.status)
            assertTrue(invalidBinding.bodyAsText().contains("EMAIL_BINDING_INVALID"))
        }

    @Test
    fun `google signup handles unverified tokens and duplicate verified-email conflicts`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    googleClaimsByToken =
                        googleClaimsByToken(
                            "token-unverified" to
                                GoogleIdTokenClaims(
                                    subject = "sub-unverified",
                                    email = "notverified@example.com",
                                    emailVerified = false,
                                    name = "No Verify",
                                    issuer = "https://accounts.google.com",
                                    audience = "client",
                                    expiresAtEpochSeconds = Clock.System.now().epochSeconds + 3600,
                                    issuedAtEpochSeconds = Clock.System.now().epochSeconds,
                                ),
                            "token-duplicate-email" to
                                googleClaims(
                                    subject = "sub-dup",
                                    email = "dup@example.com",
                                    name = "Dup",
                                ),
                        ),
                )

            runBlocking {
                env.accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "dup1",
                        displayName = "Dup1",
                        email = "dup@example.com",
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
                env.accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "dup2",
                        displayName = "Dup2",
                        email = "dup@example.com",
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val unverified =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("token-unverified"))
                }
            assertEquals(HttpStatusCode.BadRequest, unverified.status)

            val duplicate =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("token-duplicate-email"))
                }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("ACCOUNT_LINK_CONFLICT"))
        }

    @Test
    fun `google signup returns a configuration error when google auth is disabled`() =
        testApplication {
            configureAuthV1TestApp(
                googleIdTokenVerifier = FakeGoogleIdTokenVerifier(tokens = emptyMap(), configured = false),
            )

            val response =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("disabled-google"))
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("GOOGLE_AUTH_NOT_CONFIGURED"))
        }

    @Test
    fun `google signup normalizes matched account email casing`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    googleClaimsByToken =
                        googleClaimsByToken(
                            "token-link" to
                                googleClaims(
                                    subject = "sub-link",
                                    email = "case.user@example.com",
                                    name = "Case User",
                                ),
                        ),
                )

            val existing =
                Account(
                    id = Uuid.random(),
                    username = "case_user",
                    displayName = "Case User",
                    email = "CASE.USER@EXAMPLE.COM",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )
            runBlocking { env.accountRepository.save(existing) }

            val response =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody(idToken = "token-link", requestedOwnerId = existing.id.toString()))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val account = payload["data"]?.jsonObject?.get("account")?.jsonObject
            assertNotNull(account)
            assertEquals("case.user@example.com", account["email"]?.jsonPrimitive?.content)
        }

    @Test
    fun `signin passkey complete handles auth failure and missing account`() =
        testApplication {
            val webAuthnService = mockk<WebAuthnPasskeyService>()
            coEvery { webAuthnService.verifyAuthentication(any(), any()) } returns
                WebAuthnPasskeyService.AuthenticationResult(success = false, error = "bad assertion")

            configureAuthV1TestApp(webAuthnPasskeyService = webAuthnService)

            val failed =
                client.post("/api/v1/auth/signin/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signinPasskeyCompleteBody(challenge = "challenge", credentialId = "cred"))
                }
            assertEquals(HttpStatusCode.Unauthorized, failed.status)

            coEvery { webAuthnService.verifyAuthentication(any(), any()) } returns
                WebAuthnPasskeyService.AuthenticationResult(success = true, userId = Uuid.random(), credentialId = "cred")

            val missingAccount =
                client.post("/api/v1/auth/signin/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signinPasskeyCompleteBody(challenge = "challenge", credentialId = "cred"))
                }
            assertEquals(HttpStatusCode.NotFound, missingAccount.status)
            assertTrue(missingAccount.bodyAsText().contains("ACCOUNT_NOT_FOUND"))
        }

    @Test
    fun `token refresh and profile endpoints enforce request validation`() =
        testApplication {
            val env = configureAuthV1TestApp()
            val account =
                Account(
                    id = Uuid.random(),
                    username = "profile_user",
                    displayName = "Profile User",
                    email = "profile@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )
            runBlocking {
                env.accountRepository.save(account)
                env.accountRepository.save(account.copy(id = Uuid.random(), username = "already_taken"))
            }
            val access = env.tokenService.generateAccessToken(account.id.toString())
            val refresh = env.tokenService.generateRefreshToken(account.id.toString())

            val missingRefresh =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"refreshToken\":\"\"}")
                }
            assertEquals(HttpStatusCode.Unauthorized, missingRefresh.status)

            val badRefresh =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"refreshToken\":\"bad\"}")
                }
            assertEquals(HttpStatusCode.Unauthorized, badRefresh.status)

            val goodRefresh =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"refreshToken\":\"$refresh\"}")
                }
            assertEquals(HttpStatusCode.OK, goodRefresh.status)

            val noFields =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, noFields.status)

            val usernameTaken =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                    contentType(ContentType.Application.Json)
                    setBody("{\"username\":\"already_taken\"}")
                }
            assertEquals(HttpStatusCode.Conflict, usernameTaken.status)

            for (reservedUsername in listOf("about", "delete_account", "xn__lookalike", "terms_")) {
                val reservedChange =
                    client.put("/api/v1/auth/me") {
                        header(HttpHeaders.Authorization, "Bearer $access")
                        contentType(ContentType.Application.Json)
                        setBody("{\"username\":\"$reservedUsername\"}")
                    }
                assertEquals(HttpStatusCode.BadRequest, reservedChange.status, reservedUsername)
            }

            val blankDisplay =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                    contentType(ContentType.Application.Json)
                    setBody("{\"displayName\":\"\"}")
                }
            assertEquals(HttpStatusCode.BadRequest, blankDisplay.status)

            val deleteMissingCredential =
                client.delete("/api/v1/auth/me/passkeys/%20") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                }
            assertEquals(HttpStatusCode.BadRequest, deleteMissingCredential.status)

            val deleteNotFound =
                client.delete("/api/v1/auth/me/passkeys/not-owned") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                }
            assertEquals(HttpStatusCode.NotFound, deleteNotFound.status)
        }

    @Test
    fun `metrics and identities endpoints require auth and return payload when authorized`() =
        testApplication {
            val env = configureAuthV1TestApp()
            val account =
                Account(
                    id = Uuid.random(),
                    username = "metrics_user",
                    displayName = "Metrics User",
                    email = "metrics@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )
            runBlocking {
                env.accountRepository.save(account)
                env.identityRepository.save(
                    app.logdate.server.auth.AccountIdentity(
                        id = Uuid.random(),
                        accountId = account.id,
                        provider = IdentityProvider.PASSKEY,
                        providerSubject = "cred-subject",
                        email = account.email,
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val unauthorized = client.get("/api/v1/auth/metrics")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val access = env.tokenService.generateAccessToken(account.id.toString())
            val metrics =
                client.get("/api/v1/auth/metrics") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                }
            assertEquals(HttpStatusCode.OK, metrics.status)

            val metricsProm =
                client.get("/api/v1/auth/metrics/prometheus") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                }
            assertEquals(HttpStatusCode.OK, metricsProm.status)
            assertTrue(metricsProm.bodyAsText().contains("logdate_auth_operation_success_total"))

            val identities =
                client.get("/api/v1/auth/me/identities") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                }
            assertEquals(HttpStatusCode.OK, identities.status)
            assertTrue(identities.bodyAsText().contains("passkey"))
        }
}
