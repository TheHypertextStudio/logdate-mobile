package app.logdate.server.routes

import app.logdate.server.auth.AccessCheck
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.bearerToken
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import studio.hypertext.atproto.pds.PdsErrorResponse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Signs in requests made by the owner's own LogDate devices: a full-access LogDate token, or the
 * token of a device linked to the account (a watch) while its device session is active, sent as
 * `Authorization: Bearer`. OAuth DPoP tokens and AT Protocol session tokens are refused, so
 * third-party AT Protocol clients can't reach what it guards.
 */
class LogDateAccountAuth(
    private val verifier: AccessTokenVerifier,
    private val accountRepository: AccountRepository,
    private val identityService: AtprotoIdentityService,
) {
    /**
     * The active account [call]'s LogDate access token belongs to, with its AT Protocol identity
     * ensured so its `did` is set. A linked device whose session was removed or expired gets
     * `401 DEVICE_SESSION_REVOKED`, so it knows to be linked again. Session answers are cached as on
     * the sync routes, so a removal reaches other server instances within 30 seconds. Every other
     * refused or missing token, including one for a deactivated account, gets the same
     * `401 AuthRequired`. Either way this answers the call and returns null.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun requireLogDateAccount(call: ApplicationCall): Account? {
        val token = bearerToken(call.request.header(HttpHeaders.Authorization))
        val accountId =
            when (val check = verifier.verify(token, allowSyncScope = true)) {
                is AccessCheck.Granted -> {
                    Uuid.parseOrNull(check.claims.accountId)
                }

                AccessCheck.SessionRevoked -> {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        PdsErrorResponse(DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED, "This device was removed from the account"),
                    )
                    return null
                }

                AccessCheck.Missing, AccessCheck.Invalid, AccessCheck.ScopeDenied -> {
                    null
                }
            }
        val account =
            accountId
                ?.let { accountRepository.findById(it) }
                ?.takeIf { it.isActive }
                ?.let { identityService.ensureIdentity(it) }
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, PdsErrorResponse("AuthRequired", "A LogDate access token is required"))
        }
        return account
    }
}
