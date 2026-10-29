@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.TokenService
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.oauth.OAuthGrantRevoker
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountStorageMode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.authV1Routes(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    restoreCredentialService: RestoreCredentialService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    refreshTokenRevocationRepository: RefreshTokenRevocationRepository,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    accountDeletionService: AccountDeletionService? = null,
    entitlementService: EntitlementService? = null,
    emailVerificationService: EmailVerificationService? = null,
    deviceLinkService: DeviceLinkService? = null,
    accessTokenVerifier: AccessTokenVerifier = AccessTokenVerifier(tokenService, DeviceSessionGuard.Disabled),
    oauthGrantRevoker: OAuthGrantRevoker? = null,
    newAccountStorageMode: AccountStorageMode = AccountStorageMode.LEGACY,
    credentialMutationLock: AccountCredentialMutationLock = InProcessAccountCredentialMutationLock(),
) {
    val rateLimiter = InMemoryAuthRateLimiter()

    route("/auth") {
        registerAuthEntryRoutes(
            accountRepository,
            identityRepository,
            sessionManager,
            webAuthnService,
            restoreCredentialService,
            atprotoIdentityService,
            tokenService,
            googleIdTokenVerifier,
            metrics,
            newAccountStorageMode,
            rateLimiter,
        )

        route("/token") {
            registerTokenRefreshRoute(accountRepository, tokenService, refreshTokenRevocationRepository, metrics, deviceLinkService)
        }

        registerLogoutRoute(refreshTokenRevocationRepository, metrics)

        registerMetricsRoute(accountRepository, tokenService, metrics)

        registerMetricsPrometheusRoute(accountRepository, tokenService, metrics)

        registerCurrentAccountRoute(
            accountRepository,
            identityRepository,
            webAuthnService,
            atprotoIdentityService,
            tokenService,
            metrics,
            accessTokenVerifier,
        )

        registerUpdateProfileRoute(accountRepository, webAuthnService, atprotoIdentityService, tokenService, metrics)

        registerListPasskeysRoute(accountRepository, webAuthnService, tokenService, metrics)

        registerAddPasskeyBeginRoute(accountRepository, webAuthnService, tokenService, metrics)

        registerAddPasskeyCompleteRoute(accountRepository, identityRepository, webAuthnService, tokenService, metrics)

        registerDeletePasskeyRoute(
            accountRepository,
            webAuthnService,
            tokenService,
            metrics,
            oauthGrantRevoker,
            credentialMutationLock,
            AccountSignInMethods(webAuthnService, identityRepository, googleIdTokenVerifier),
        )

        registerDeleteAccountRoute(accountRepository, tokenService, metrics, accountDeletionService)

        registerEmailVerificationBeginRoute(accountRepository, tokenService, metrics, entitlementService, emailVerificationService)

        registerEmailVerificationCompleteRoute(accountRepository, tokenService, metrics, entitlementService, emailVerificationService)

        registerEntitlementRoute(accountRepository, tokenService, metrics, entitlementService)

        registerListIdentitiesRoute(accountRepository, identityRepository, tokenService, metrics)
    }
}
