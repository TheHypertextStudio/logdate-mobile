package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.database.AtprotoAppPasswordStore
import app.logdate.server.identity.PlcPublicationPasskey
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun Route.atprotoAppPasswordRoutes(
    accounts: AccountRepository,
    tokens: TokenService,
    store: AtprotoAppPasswordStore,
    passkeys: PlcPublicationPasskey,
) {
    route("/identity/app-passwords") {
        get("", { hidden = true }) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@get
            call.respond(AppPasswordList(store.list(account.id).map { AppPasswordSummary(it.id, it.name, it.createdAt) }))
        }
        post("/review", { hidden = true }) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@post
            val did =
                account.did?.takeIf { it == account.publicIdentityDid && account.isActive && account.moderation == null }
                    ?: return@post call.appPasswordUnavailable()
            val name =
                call.receive<AppPasswordReviewInput>().name.validAppPasswordName()
                    ?: return@post call.appPasswordUnavailable()
            val options = passkeys.begin(account.id, appPasswordBinding(account.id, did, name))
            if (options.allowCredentials.isEmpty()) return@post call.appPasswordUnavailable()
            call.respond(options)
        }
        post("", { hidden = true }) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@post
            val did =
                account.did?.takeIf { it == account.publicIdentityDid && account.isActive && account.moderation == null }
                    ?: return@post call.appPasswordUnavailable()
            val input = call.receive<AppPasswordCreateInput>()
            val name = input.name.validAppPasswordName() ?: return@post call.appPasswordUnavailable()
            val binding = appPasswordBinding(account.id, did, name)
            if (!passkeys.verify(account.id, binding, input.challenge, input.credential)) {
                return@post call.appPasswordUnavailable()
            }
            val issued = store.issue(account.id, did, name) ?: return@post call.appPasswordUnavailable()
            call.respond(HttpStatusCode.Created, AppPasswordSecret(issued.value))
        }
        delete("/{id}", { hidden = true }) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val account = resolveIdentityApiAccount(call, accounts, tokens) ?: return@delete
            val id = call.parameters["id"]?.let(Uuid::parseOrNull) ?: return@delete call.appPasswordUnavailable()
            store.revoke(account.id, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun String.validAppPasswordName(): String? = trim().takeIf { it.length in 1..64 && it.none(Char::isISOControl) }

@OptIn(ExperimentalUuidApi::class)
internal fun appPasswordBinding(
    accountId: Uuid,
    did: String,
    name: String,
): Uuid {
    val bytes = MessageDigest.getInstance("SHA-256").digest("$accountId\u0000$did\u0000$name".encodeToByteArray())
    return Uuid.fromByteArray(bytes.copyOf(16))
}

private suspend fun ApplicationCall.appPasswordUnavailable() =
    respondIdentityApiError(HttpStatusCode.Conflict, "APP_PASSWORD_UNAVAILABLE", "This app password couldn’t be created.")

@Serializable
private data class AppPasswordReviewInput(
    val name: String,
)

@Serializable
private class AppPasswordCreateInput(
    val name: String,
    val challenge: String,
    val credential: PasskeyAuthenticationResponse,
)

@Serializable
private class AppPasswordSecret(
    val password: String,
)

@Serializable
private data class AppPasswordList(
    val passwords: List<AppPasswordSummary>,
)

@Serializable
private data class AppPasswordSummary(
    val id: String,
    val name: String,
    val createdAt: String,
)
