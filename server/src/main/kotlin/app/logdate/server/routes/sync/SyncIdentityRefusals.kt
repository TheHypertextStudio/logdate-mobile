package app.logdate.server.routes.sync

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.responses.error
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun ApplicationCall.respondIdentityWriteRefusal(failure: IdentityWriteRefusedException) {
    val (code, message) =
        when (failure.outcome) {
            IdentityFenceOutcome.HELD -> "IDENTITY_WRITE_HELD" to "Sync is paused while your account changes."
            IdentityFenceOutcome.CHANGED -> "IDENTITY_CHANGED" to "Refresh your account before syncing."
            IdentityFenceOutcome.DELETED -> "ACCOUNT_UNAVAILABLE" to "This account is unavailable."
            IdentityFenceOutcome.ALLOWED -> error("An allowed write cannot be refused")
        }
    val status = if (failure.outcome == IdentityFenceOutcome.DELETED) HttpStatusCode.Unauthorized else HttpStatusCode.Conflict
    respond(status, error(code, message))
}
