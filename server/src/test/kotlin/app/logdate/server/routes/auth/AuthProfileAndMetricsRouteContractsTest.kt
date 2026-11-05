package app.logdate.server.routes.auth

import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.support.googleClaimsByToken
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
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Contract and validation tests for user profile and authentication metrics endpoints.
 *
 * This suite verifies the robustness of the `/auth/me` and `/auth/metrics` routes,
 * ensuring they correctly handle invalid tokens, enforce input constraints (such as
 * username length and availability), and gracefully report internal service or
 * repository failures.
 */
@OptIn(ExperimentalUuidApi::class)
class AuthProfileAndMetricsRouteContractsTest {
    @Test
    fun `token refresh and profile update validation return expected errors`() =
        testApplication {
            val accountId = Uuid.random()
            val accountRepository = mockk<AccountRepository>(relaxed = true)
            val identityRepository = mockk<AccountIdentityRepository>(relaxed = true)
            val tokenService = mockk<JwtTokenService>(relaxed = true)
            val webAuthn = mockk<WebAuthnPasskeyService>(relaxed = true)
            val metrics = AuthMetricsRegistry()

            val account =
                Account(
                    id = accountId,
                    username = "profile_user",
                    displayName = "Profile User",
                    email = "profile@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )

            coEvery { accountRepository.findById(accountId) } returns account
            every { tokenService.validateAccessToken("access-ok") } returns accountId.toString()
            every { tokenService.validateRefreshToken(any()) } throws IllegalStateException("refresh-boom")
            every { tokenService.decodeAccessToken(any()) } returns null
            every { tokenService.decodeAccessToken("access-ok") } returns AccessTokenClaims.full(accountId.toString(), account.did)
            every { tokenService.decodeRefreshToken(any()) } returns null
            every { webAuthn.getPasskeysForUser(any()) } returns emptyList()
            coEvery { accountRepository.usernameExists("taken_username") } returns true

            configureAuthV1TestApp(
                tokenService = tokenService,
                accountRepository = accountRepository,
                identityRepository = identityRepository,
                webAuthnPasskeyService = webAuthn,
                metrics = metrics,
            )

            val refresh =
                client.post("/api/v1/auth/token/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"refreshToken":"anything"}""")
                }
            assertEquals(HttpStatusCode.InternalServerError, refresh.status)

            val invalidUsernameUpdate =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"ab"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidUsernameUpdate.status)

            val takenUsernameUpdate =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"taken_username"}""")
                }
            assertEquals(HttpStatusCode.Conflict, takenUsernameUpdate.status)

            val longDisplayName = "x".repeat(101)
            val longDisplayNameUpdate =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"$longDisplayName"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, longDisplayNameUpdate.status)
        }

    @Test
    fun `metrics and identity endpoints return errors for invalid token and repository failures`() =
        testApplication {
            val accountId = Uuid.random()
            val accountRepository = mockk<AccountRepository>(relaxed = true)
            val identityRepository = mockk<AccountIdentityRepository>(relaxed = true)
            val tokenService = mockk<JwtTokenService>(relaxed = true)
            val metrics = mockk<AuthMetricsRegistry>(relaxed = true)
            val webAuthn = mockk<WebAuthnPasskeyService>(relaxed = true)

            val account =
                Account(
                    id = accountId,
                    username = "metrics_user",
                    displayName = "Metrics User",
                    email = "metrics@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )

            coEvery { accountRepository.findById(accountId) } returns account
            every { tokenService.validateAccessToken("access-ok") } returns accountId.toString()
            val missingAccountId = Uuid.random().toString()
            every { tokenService.validateAccessToken("missing-account") } returns missingAccountId
            every { tokenService.decodeAccessToken(any()) } returns null
            every { tokenService.decodeAccessToken("access-ok") } returns AccessTokenClaims.full(accountId.toString(), account.did)
            every { tokenService.decodeRefreshToken(any()) } returns null
            every { tokenService.decodeAccessToken("missing-account") } returns AccessTokenClaims.full(missingAccountId)
            every { metrics.snapshot() } throws IllegalStateException("metrics-boom")
            coEvery { identityRepository.findByAccountId(any()) } throws IllegalStateException("identities-boom")
            coEvery { accountRepository.findById(any()) } throws IllegalStateException("me-boom")

            configureAuthV1TestApp(
                tokenService = tokenService,
                accountRepository = accountRepository,
                identityRepository = identityRepository,
                metrics = metrics,
                webAuthnPasskeyService = webAuthn,
                googleClaimsByToken =
                    googleClaimsByToken(
                        "noop" to
                            GoogleIdTokenClaims(
                                subject = "s",
                                email = "e@example.com",
                                emailVerified = true,
                                name = "N",
                                issuer = "https://accounts.google.com",
                                audience = "client",
                                expiresAtEpochSeconds = Clock.System.now().epochSeconds + 3600,
                                issuedAtEpochSeconds = Clock.System.now().epochSeconds,
                            ),
                    ),
            )

            val metricsJson =
                client.get("/api/v1/auth/metrics") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                }
            assertEquals(HttpStatusCode.InternalServerError, metricsJson.status)

            val metricsProm =
                client.get("/api/v1/auth/metrics/prometheus") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                }
            assertEquals(HttpStatusCode.InternalServerError, metricsProm.status)

            val blankToken =
                client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer   ")
                }
            assertEquals(HttpStatusCode.Unauthorized, blankToken.status)

            val invalidToken =
                client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer missing-account")
                }
            assertTrue(
                invalidToken.status == HttpStatusCode.NotFound ||
                    invalidToken.status == HttpStatusCode.InternalServerError,
            )

            val identitiesError =
                client.get("/api/v1/auth/me/identities") {
                    header(HttpHeaders.Authorization, "Bearer access-ok")
                }
            assertEquals(HttpStatusCode.InternalServerError, identitiesError.status)
        }

    @Test
    fun `profile endpoints return expected unauthorized success and exception responses`() =
        testApplication {
            val accountId = Uuid.random()
            val account =
                Account(
                    id = accountId,
                    username = "profile_success_user",
                    displayName = "Profile Success User",
                    did = "did:web:profile-success-user.logdate.app",
                    handle = "profile-success-user.logdate.app",
                    signingKeyPublic = "zProfileKey",
                    email = "profile-success@example.com",
                    emailVerified = true,
                    createdAt = Clock.System.now(),
                )

            val accountRepository = mockk<AccountRepository>(relaxed = true)
            coEvery { accountRepository.findById(accountId) } returns account
            coEvery { accountRepository.save(any()) } throws IllegalStateException("save-profile-boom")

            val tokenService = mockk<JwtTokenService>(relaxed = true)
            every { tokenService.validateAccessToken("access-good") } returns accountId.toString()
            every { tokenService.validateAccessToken("invalid-token") } returns null
            every { tokenService.decodeAccessToken(any()) } returns null
            every { tokenService.decodeAccessToken("access-good") } returns AccessTokenClaims.full(accountId.toString(), account.did)
            every { tokenService.decodeRefreshToken(any()) } returns null

            val webAuthn = mockk<WebAuthnPasskeyService>(relaxed = true)
            every { webAuthn.getPasskeysForUser(any()) } returns emptyList()
            every { webAuthn.credentialBelongsToUser(any(), any()) } throws IllegalStateException("delete-passkey-boom")

            configureAuthV1TestApp(
                accountRepository = accountRepository,
                tokenService = tokenService,
                webAuthnPasskeyService = webAuthn,
            )

            val meSuccess =
                client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer access-good")
                }
            assertEquals(HttpStatusCode.OK, meSuccess.status)
            assertTrue(meSuccess.bodyAsText().contains("profile_success_user"))

            val meInvalidToken =
                client.get("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer invalid-token")
                }
            assertEquals(HttpStatusCode.Unauthorized, meInvalidToken.status)

            val putUnauthorized = client.put("/api/v1/auth/me")
            assertEquals(HttpStatusCode.Unauthorized, putUnauthorized.status)

            val putCatch =
                client.put("/api/v1/auth/me") {
                    header(HttpHeaders.Authorization, "Bearer access-good")
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Updated"}""")
                }
            assertEquals(HttpStatusCode.InternalServerError, putCatch.status)

            val deleteUnauthorized = client.delete("/api/v1/auth/me/passkeys/cred-any")
            assertEquals(HttpStatusCode.Unauthorized, deleteUnauthorized.status)

            val deleteCatch =
                client.delete("/api/v1/auth/me/passkeys/cred-any") {
                    header(HttpHeaders.Authorization, "Bearer access-good")
                }
            assertEquals(HttpStatusCode.InternalServerError, deleteCatch.status)

            val metricsPromUnauthorized = client.get("/api/v1/auth/metrics/prometheus")
            assertEquals(HttpStatusCode.Unauthorized, metricsPromUnauthorized.status)
        }
}
