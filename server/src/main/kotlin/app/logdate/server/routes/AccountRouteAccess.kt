package app.logdate.server.routes

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.auth.storageModes
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.migration.SpaceMigrationHolds
import io.ktor.server.application.Application
import org.koin.ktor.ext.inject

internal data class AccountRouteAccess(
    val storageModes: AccountStorageModes,
    val auth: LogDateAccountAuth,
)

/** Resolve shared first-party account access at the application composition boundary. */
internal fun Application.accountRouteAccess(
    accounts: AccountRepository,
    verifier: AccessTokenVerifier,
): AccountRouteAccess {
    val identities by inject<AtprotoIdentityService>()
    val holds by inject<SpaceMigrationHolds>()
    return AccountRouteAccess(accounts.storageModes(holds), LogDateAccountAuth(verifier, accounts, identities))
}
