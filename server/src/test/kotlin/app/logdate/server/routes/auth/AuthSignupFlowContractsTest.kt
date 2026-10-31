package app.logdate.server.routes.auth

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TemporarySession
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.passkeys.WebAuthnPasskeyService.VerificationOutcome
import app.logdate.server.passkeys.WebAuthnPasskeyService.VerifiedRegistration
import app.logdate.server.routes.support.googleAuthBody
import app.logdate.server.routes.support.googleClaims
import app.logdate.server.routes.support.googleClaimsByToken
import app.logdate.server.routes.support.signupPasskeyCompleteBody
import app.logdate.server.routes.support.signupPasskeyCompleteBodyWithBindingSource
import app.logdate.shared.model.PasskeyInfo
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Comprehensive contract tests for Google and Passkey account registration flows.
 *
 * This suite validates the complex signup logic, including identity provider linking,
 * automatic username generation and collision resolution, unverified email rejection,
 * and session-based account creation. It ensures that the system maintains integrity
 * across multiple authentication providers and handles edge cases in the registration
 * pipeline.
 */
@OptIn(ExperimentalUuidApi::class)
class AuthSignupFlowContractsTest {
    @Test
    fun `google auth rejects invalid signup token and unverified signin email`() =
        testApplication {
            configureAuthV1TestApp(
                googleClaimsByToken =
                    googleClaimsByToken(
                        "signin-unverified" to
                            GoogleIdTokenClaims(
                                subject = "signin-unverified-sub",
                                email = "signin-unverified@example.com",
                                emailVerified = false,
                                name = "Signin Unverified",
                                issuer = "https://accounts.google.com",
                                audience = "client",
                                expiresAtEpochSeconds = Clock.System.now().epochSeconds + 3600,
                                issuedAtEpochSeconds = Clock.System.now().epochSeconds,
                            ),
                    ),
            )

            val signupInvalidToken =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("missing-token"))
                }
            assertEquals(HttpStatusCode.Unauthorized, signupInvalidToken.status)
            assertTrue(signupInvalidToken.bodyAsText().contains("GOOGLE_TOKEN_INVALID"))

            val signinUnverified =
                client.post("/api/v1/auth/signin/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("signin-unverified"))
                }
            assertEquals(HttpStatusCode.BadRequest, signinUnverified.status)
            assertTrue(signinUnverified.bodyAsText().contains("GOOGLE_EMAIL_UNVERIFIED"))
        }

    @Test
    fun `google signup chooses suffixed username when requested username is already taken`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    googleClaimsByToken =
                        googleClaimsByToken(
                            "suffix-token" to
                                googleClaims(
                                    subject = "suffix-subject",
                                    email = "suffix@example.com",
                                    name = "Suffix User",
                                ),
                        ),
                )
            runBlocking {
                env.accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "takenbase",
                        displayName = "Taken Base",
                        email = "takenbase@example.com",
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val response =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"idToken":"suffix-token","username":"takenbase","requestedOwnerId":"${Uuid.random()}"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("takenbase_1001"))
        }

    @Test
    fun `google signup returns conflict when no unique username can be generated`() =
        testApplication {
            val accountRepository = mockk<AccountRepository>(relaxed = true)
            coEvery { accountRepository.findById(any()) } returns null
            coEvery { accountRepository.findByVerifiedEmail(any()) } returns emptyList()
            coEvery { accountRepository.usernameExists(any()) } returns true

            configureAuthV1TestApp(
                accountRepository = accountRepository,
                googleClaimsByToken =
                    googleClaimsByToken(
                        "exhaust-token" to
                            googleClaims(
                                subject = "exhaust-subject",
                                email = "exhaust@example.com",
                                name = "Exhaust User",
                            ),
                    ),
            )

            val response =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"idToken":"exhaust-token","username":"takenbase","requestedOwnerId":"${Uuid.random()}"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("ACCOUNT_LINK_CONFLICT"))
        }

    @Test
    fun `passkey signup completion rejects failed passkey verification`() =
        testApplication {
            val sessionToken = "s-sgn-a"
            val accountId = Uuid.random()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } returns
                accountCreationSession(sessionToken, accountId)

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistration(any(), any(), any()) } returns
                WebAuthnPasskeyService.RegistrationResult(success = false, error = "verification-nope")

            configureAuthV1TestApp(
                sessionManager = sessionManager,
                webAuthnPasskeyService = passkeyService,
            )

            val verifyFailed =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-a"))
                }
            assertEquals(HttpStatusCode.BadRequest, verifyFailed.status)
            assertTrue(verifyFailed.bodyAsText().contains("PASSKEY_VERIFICATION_FAILED"))
        }

    @Test
    fun `passkey signup completion rejects a claimed canonical owner ID without mutating it`() =
        testApplication {
            val sessionToken = "s-owner-collision"
            val ownerId = Uuid.random()
            val existing =
                Account(
                    id = ownerId,
                    username = "existing-owner",
                    displayName = "Existing Owner",
                    createdAt = Clock.System.now(),
                )
            val accountRepository = InMemoryAccountRepository()
            runBlocking { accountRepository.save(existing) }

            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } returns
                accountCreationSession(sessionToken, ownerId)
            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistrationOnly(any(), any(), any()) } returns
                VerificationOutcome.Success(verifiedRegistration("cred-owner-collision"))

            val env =
                configureAuthV1TestApp(
                    accountRepository = accountRepository,
                    sessionManager = sessionManager,
                    webAuthnPasskeyService = passkeyService,
                )

            val response =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-owner-collision"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("CANONICAL_OWNER_ID_TAKEN"))
            runBlocking {
                assertEquals(existing, accountRepository.findById(ownerId))
                assertTrue(env.identityRepository.findByAccountId(ownerId).isEmpty())
            }
        }

    @Test
    fun `passkey signup completion handles session failures and invalid email binding`() =
        testApplication {
            val sessionToken = "s-catch"
            val accountId = Uuid.random()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } throws
                IllegalStateException("session-boom") andThen
                accountCreationSession(sessionToken, accountId)

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistrationOnly(any(), any(), any()) } returns
                VerificationOutcome.Success(verifiedRegistration("cred-catch"))
            coEvery { passkeyService.storeVerifiedPasskey(any(), any()) } returns true
            coEvery { passkeyService.getPasskeysForUser(any()) } returns emptyList()
            configureAuthV1TestApp(
                sessionManager = sessionManager,
                webAuthnPasskeyService = passkeyService,
                googleClaimsByToken =
                    googleClaimsByToken(
                        "b-unvfy" to
                            GoogleIdTokenClaims(
                                subject = "binding-sub",
                                email = "binding@example.com",
                                emailVerified = false,
                                name = "Binding",
                                issuer = "https://accounts.google.com",
                                audience = "client",
                                expiresAtEpochSeconds = Clock.System.now().epochSeconds + 3600,
                                issuedAtEpochSeconds = Clock.System.now().epochSeconds,
                            ),
                    ),
            )

            val caughtFailure =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-catch"))
                }
            assertEquals(HttpStatusCode.InternalServerError, caughtFailure.status)

            val unverifiedBinding =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        signupPasskeyCompleteBodyWithBindingSource(
                            sessionToken = sessionToken,
                            credentialId = "cred-catch",
                            source = "google_id_token",
                            bindingToken = "b-unvfy",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, unverifiedBinding.status)
            assertTrue(unverifiedBinding.bodyAsText().contains("EMAIL_BINDING_INVALID"))
        }

    @Test
    fun `passkey signup completion rejects conflicting google link and falls back to request credential id`() =
        testApplication {
            val sessionToken = "s-sgn-b"
            val accountId = Uuid.random()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } returns
                accountCreationSession(sessionToken, accountId)
            coEvery { sessionManager.markSessionUsed(sessionToken) } returns true

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistrationOnly(any(), any(), any()) } returns
                VerificationOutcome.Success(verifiedRegistration("cred-request-fallback"))
            coEvery { passkeyService.storeVerifiedPasskey(any(), any()) } returns true
            coEvery { passkeyService.getPasskeysForUser(any()) } returns emptyList()

            val env =
                configureAuthV1TestApp(
                    sessionManager = sessionManager,
                    webAuthnPasskeyService = passkeyService,
                    googleClaimsByToken =
                        googleClaimsByToken(
                            "b-tok" to
                                googleClaims(
                                    subject = "google-conflict-sub",
                                    email = "conflict@example.com",
                                    name = "Conflict User",
                                ),
                        ),
                )

            runBlocking {
                env.identityRepository.save(
                    AccountIdentity(
                        id = Uuid.random(),
                        accountId = Uuid.random(),
                        provider = IdentityProvider.GOOGLE,
                        providerSubject = "google-conflict-sub",
                        email = "conflict@example.com",
                        emailVerified = true,
                        createdAt = Clock.System.now(),
                    ),
                )
            }

            val conflict =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        signupPasskeyCompleteBodyWithBindingSource(
                            sessionToken = sessionToken,
                            credentialId = "cred-conflict",
                            source = "google_id_token",
                            bindingToken = "b-tok",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertTrue(conflict.bodyAsText().contains("ACCOUNT_LINK_CONFLICT"))

            val success =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-request-fallback"))
                }
            assertEquals(HttpStatusCode.Created, success.status)
            runBlocking {
                val identities = env.identityRepository.findByAccountId(accountId)
                assertTrue(
                    identities.any {
                        it.provider == IdentityProvider.PASSKEY && it.providerSubject == "cred-request-fallback"
                    },
                )
            }
        }

    @Test
    fun `google signup and signin return server error when verifier crashes`() =
        testApplication {
            val verifier = mockk<GoogleIdTokenVerifier>()
            every { verifier.isConfigured() } returns true
            coEvery { verifier.verify(any(), any()) } throws IllegalStateException("google-verifier-boom")
            configureAuthV1TestApp(googleIdTokenVerifier = verifier)

            val signup =
                client.post("/api/v1/auth/signup/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("token-boom"))
                }
            assertEquals(HttpStatusCode.InternalServerError, signup.status)

            val signin =
                client.post("/api/v1/auth/signin/google") {
                    contentType(ContentType.Application.Json)
                    setBody(googleAuthBody("token-boom"))
                }
            assertEquals(HttpStatusCode.InternalServerError, signin.status)
        }

    @Test
    fun `passkey signup leaves no orphan account when WebAuthn verification fails`() =
        testApplication {
            val sessionToken = "s-verify-fail"
            val accountId = Uuid.random()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } returns
                accountCreationSession(sessionToken, accountId)

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistrationOnly(any(), any(), any()) } returns
                VerificationOutcome.Failure("attestation rejected")

            val env =
                configureAuthV1TestApp(
                    sessionManager = sessionManager,
                    webAuthnPasskeyService = passkeyService,
                )

            val response =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-verify-fail"))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("PASSKEY_VERIFICATION_FAILED"))
            runBlocking {
                assertNull(
                    env.accountRepository.findById(accountId),
                    "verification failure must not leave an account row behind",
                )
            }
        }

    @Test
    fun `passkey signup deletes the account row when storeVerifiedPasskey fails`() =
        testApplication {
            val sessionToken = "s-store-fail"
            val accountId = Uuid.random()
            val sessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { sessionManager.validateSession(eq(sessionToken), eq(SessionType.ACCOUNT_CREATION)) } returns
                accountCreationSession(sessionToken, accountId)

            val passkeyService = mockk<WebAuthnPasskeyService>(relaxed = true)
            coEvery { passkeyService.verifyRegistrationOnly(any(), any(), any()) } returns
                VerificationOutcome.Success(verifiedRegistration("cred-store-fail"))
            coEvery { passkeyService.storeVerifiedPasskey(any(), any()) } returns false

            val env =
                configureAuthV1TestApp(
                    sessionManager = sessionManager,
                    webAuthnPasskeyService = passkeyService,
                )

            val response =
                client.post("/api/v1/auth/signup/passkey/complete") {
                    contentType(ContentType.Application.Json)
                    setBody(signupPasskeyCompleteBody(sessionToken = sessionToken, credentialId = "cred-store-fail"))
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.bodyAsText().contains("PASSKEY_STORE_FAILED"))
            runBlocking {
                assertNull(
                    env.accountRepository.findById(accountId),
                    "passkey-store failure must roll the account row back",
                )
            }
        }

    private fun verifiedRegistration(credentialId: String): VerifiedRegistration {
        val now = Clock.System.now()
        return VerifiedRegistration(
            credentialId = credentialId,
            publicKey = ByteArray(0),
            signCount = 0L,
            passkey =
                PasskeyInfo(
                    id = Uuid.random(),
                    credentialId = credentialId,
                    nickname = "test",
                    deviceType = "platform",
                    createdAt = now,
                    lastUsedAt = null,
                    isActive = true,
                ),
        )
    }

    private fun accountCreationSession(
        token: String,
        userId: Uuid,
    ): TemporarySession {
        val now = Clock.System.now()
        return TemporarySession(
            id = token,
            temporaryUserId = userId,
            challenge = "challenge",
            username = "signup_user",
            displayName = "Signup User",
            bio = "bio",
            deviceInfo = null,
            sessionType = SessionType.ACCOUNT_CREATION,
            createdAt = now,
            expiresAt = now + 10.minutes,
            isUsed = false,
        )
    }
}
