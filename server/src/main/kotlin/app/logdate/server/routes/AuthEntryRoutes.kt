package app.logdate.server.routes

import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountStorageMode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

internal fun Route.registerAuthEntryRoutes(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    restoreCredentialService: RestoreCredentialService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    newAccountStorageMode: AccountStorageMode,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    registerSignupRoutes(
        accountRepository,
        identityRepository,
        sessionManager,
        webAuthnService,
        atprotoIdentityService,
        tokenService,
        googleIdTokenVerifier,
        metrics,
        newAccountStorageMode,
        rateLimiter,
    )

    route("/signin") {
        route("/passkey") {
            registerSigninPasskeyBeginRoute(accountRepository, webAuthnService, metrics, rateLimiter)

            registerSigninPasskeyCompleteRoute(
                accountRepository,
                identityRepository,
                webAuthnService,
                atprotoIdentityService,
                tokenService,
                metrics,
                rateLimiter,
            )
        }

        registerSigninGoogleRoute(
            accountRepository,
            identityRepository,
            webAuthnService,
            atprotoIdentityService,
            tokenService,
            googleIdTokenVerifier,
            metrics,
            newAccountStorageMode,
            rateLimiter,
        )
    }

    route("/restore") {
        registerRestoreRegisterBeginRoute(accountRepository, restoreCredentialService, tokenService, metrics)

        registerRestoreRegisterCompleteRoute(accountRepository, restoreCredentialService, tokenService, metrics)

        registerRestoreBeginRoute(restoreCredentialService, metrics)

        registerRestoreCompleteRoute(
            accountRepository,
            identityRepository,
            webAuthnService,
            restoreCredentialService,
            atprotoIdentityService,
            tokenService,
            metrics,
        )
    }
}

private fun Route.registerSignupRoutes(
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    sessionManager: SessionManager,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    googleIdTokenVerifier: GoogleIdTokenVerifier,
    metrics: AuthMetricsRegistry,
    newAccountStorageMode: AccountStorageMode,
    rateLimiter: InMemoryAuthRateLimiter,
) {
    route("/signup") {
        registerSignupUsernameRoute(accountRepository, metrics)

        route("/passkey") {
            registerSignupPasskeyBeginRoute(accountRepository, sessionManager, webAuthnService, metrics, rateLimiter)

            registerSignupPasskeyCompleteRoute(
                accountRepository,
                identityRepository,
                sessionManager,
                webAuthnService,
                atprotoIdentityService,
                tokenService,
                googleIdTokenVerifier,
                metrics,
                newAccountStorageMode,
                rateLimiter,
            )
        }

        registerSignupGoogleRoute(
            accountRepository,
            identityRepository,
            webAuthnService,
            atprotoIdentityService,
            tokenService,
            googleIdTokenVerifier,
            metrics,
            newAccountStorageMode,
            rateLimiter,
        )
    }
}
