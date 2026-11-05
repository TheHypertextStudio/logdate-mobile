@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.database.toJavaUUID
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.responses.error
import app.logdate.server.routes.LogDateAccountAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlin.uuid.toKotlinUuid

/** Keep archive access full-scope while binding mutations to the account captured before transfer. */
internal suspend fun ApplicationCall.backupWriteAuthority(
    tokenService: TokenService?,
    accountAuth: LogDateAccountAuth?,
): IdentityWriteExpectation? {
    val authenticatedId = extractUserId(this, tokenService) ?: return null
    // Non-database fixtures retain their original epoch; the production composition supplies account auth.
    if (accountAuth == null) return IdentityWriteExpectation(authenticatedId.toKotlinUuid(), 0)
    val account = accountAuth.requireLogDateAccount(this) ?: return null
    if (account.id.toJavaUUID() != authenticatedId) {
        respond(HttpStatusCode.Unauthorized, error("UNAUTHORIZED", "Invalid or expired token"))
        return null
    }
    return IdentityWriteExpectation(account.id, account.identityEpoch)
}
