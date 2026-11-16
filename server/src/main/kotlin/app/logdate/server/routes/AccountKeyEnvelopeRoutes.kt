@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.accountkeys.AccountKeyEnvelopeRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.AccountKeyEnvelopeDocs
import app.logdate.server.routes.sync.extractUserId
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID
import kotlin.uuid.toKotlinUuid

@Serializable
internal data class AccountKeyEnvelope(
    val ciphertext: String,
)

internal fun Route.accountKeyEnvelopeRoutes(
    tokenService: TokenService,
    passkeys: PasskeyRepository,
    repository: AccountKeyEnvelopeRepository,
    enabled: Boolean,
) {
    route("/account/key-envelopes/{credentialId}") {
        get(AccountKeyEnvelopeDocs.get) {
            val owner = extractUserId(call, tokenService) ?: return@get
            call.response.headers.append("Cache-Control", "no-store")
            if (!enabled) return@get call.respond(HttpStatusCode.ServiceUnavailable, error("UNAVAILABLE", "Secure unlock unavailable"))
            val credential = call.parameters["credentialId"]
            if (!ownsCredential(passkeys, owner, credential)) {
                return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Envelope unavailable"))
            }
            val ciphertext =
                repository.get(owner, credential!!)
                    ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Envelope unavailable"))
            call.respond(AccountKeyEnvelope(ciphertext))
        }
        put(AccountKeyEnvelopeDocs.put) {
            val owner = extractUserId(call, tokenService) ?: return@put
            call.response.headers.append("Cache-Control", "no-store")
            if (!enabled) return@put call.respond(HttpStatusCode.ServiceUnavailable, error("UNAVAILABLE", "Secure unlock unavailable"))
            val credential = call.parameters["credentialId"]
            if (!ownsCredential(passkeys, owner, credential)) {
                return@put call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Envelope unavailable"))
            }
            val envelope = call.receive<AccountKeyEnvelope>()
            if (!validEnvelope(envelope.ciphertext)) {
                return@put call.respond(HttpStatusCode.BadRequest, error("INVALID_ENVELOPE", "Invalid encrypted envelope"))
            }
            if (!repository.put(owner, credential!!, envelope.ciphertext)) {
                return@put call.respond(HttpStatusCode.Conflict, error("ENVELOPE_EXISTS", "An envelope already exists for this credential"))
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private val CREDENTIAL_ID = Regex("[A-Za-z0-9_-]{1,2048}")

private suspend fun ownsCredential(
    passkeys: PasskeyRepository,
    owner: UUID,
    credentialId: String?,
): Boolean {
    if (credentialId == null || !credentialId.matches(CREDENTIAL_ID)) return false
    return passkeys.getPasskeyByCredentialId(credentialId)?.first == owner.toKotlinUuid()
}

private fun validEnvelope(ciphertext: String): Boolean {
    if (ciphertext.length != 132) return false
    val bytes = runCatching { Base64.getDecoder().decode(ciphertext) }.getOrNull() ?: return false
    return bytes.size == 97 &&
        bytes.take(5).toByteArray().contentEquals("LDKE1".toByteArray()) &&
        Base64.getEncoder().encodeToString(bytes) == ciphertext
}
