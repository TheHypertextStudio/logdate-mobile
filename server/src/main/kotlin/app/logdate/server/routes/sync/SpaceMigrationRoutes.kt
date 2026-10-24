package app.logdate.server.routes.sync

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.migration.LegacyMediaCopier
import app.logdate.server.migration.SpaceMigrationResult
import app.logdate.server.migration.SpaceMigrationService
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.SpaceMigrationDocs
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transfer.TransferSlots
import app.logdate.shared.model.sync.RegisteredLegacyMedia
import app.logdate.shared.model.sync.SpaceMigrationErrorCodes
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.shared.model.sync.SpaceMigrationRequest
import app.logdate.shared.model.sync.SpaceMigrationSwitchBackRequest
import app.logdate.shared.model.sync.SpaceMigrationSwitchRequest
import app.logdate.space.SpaceRef
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

private val migrationJson = Json { ignoreUnknownKeys = true }

/** How long a device waits when the server is already copying as many files as it can. */
private const val COPY_BUSY_RETRY_AFTER_SECONDS = 30

/**
 * `/api/v1/space-migration`: the calls a device makes to move its account's journal from legacy
 * storage into its personal space, and back while the legacy copy is kept. Every call needs a
 * full-access token, because each one acts on the whole account; a linked watch is refused.
 *
 * While [newMoves] is false, as when the migration is turned off, the calls that start or advance
 * a move answer `404 FEATURE_DISABLED`. Switching back is part of the safety guarantee, so the
 * status, the switch back, and the hold and inventory it needs keep working for an account that
 * switched, until its legacy copy is gone.
 */
internal fun Route.spaceMigrationRoutes(
    tokenService: TokenService?,
    service: SpaceMigrationService,
    copier: LegacyMediaCopier,
    accounts: AccountRepository,
    transfers: TransferSlots,
    metrics: SyncMetricsRegistry? = null,
    newMoves: () -> Boolean = { true },
) {
    route("/space-migration") {
        get(SpaceMigrationDocs.status) {
            val accountId = extractUserId(call, tokenService) ?: return@get
            call.respond(service.status(accountId))
        }
        post("/begin", SpaceMigrationDocs.begin) {
            val accountId = call.moveCaller(tokenService, newMoves) ?: return@post
            val request = call.migrationBody<SpaceMigrationRequest>() ?: return@post
            call.respondOutcome(metrics.timed("space_migration.begin") { service.begin(accountId, request.migrationId) })
        }
        post("/hold", SpaceMigrationDocs.hold) {
            val accountId = call.moveOrSwitchBackCaller(tokenService, service, newMoves) ?: return@post
            val request = call.migrationBody<SpaceMigrationRequest>() ?: return@post
            call.respondOutcome(service.hold(accountId, request.migrationId))
        }
        post("/release", SpaceMigrationDocs.release) {
            val accountId = call.moveOrSwitchBackCaller(tokenService, service, newMoves) ?: return@post
            val request = call.migrationBody<SpaceMigrationRequest>() ?: return@post
            call.respondOutcome(service.release(accountId, request.migrationId))
        }
        get("/legacy-inventory", SpaceMigrationDocs.legacyInventory) {
            val accountId = call.moveOrSwitchBackCaller(tokenService, service, newMoves) ?: return@get
            call.respond(service.legacyInventory(accountId, call.request.queryParameters["ids"]))
        }
        post("/media/{mediaId}", SpaceMigrationDocs.registerMedia) {
            val accountId = call.moveCaller(tokenService, newMoves) ?: return@post
            val request = call.migrationBody<SpaceMigrationRequest>() ?: return@post
            metrics.timed("space_migration.media_copy") {
                call.registerMedia(accountId, service, copier, accounts, transfers, request)
            }
        }
        post("/switch", SpaceMigrationDocs.switch) {
            val accountId = call.moveCaller(tokenService, newMoves) ?: return@post
            val request = call.migrationBody<SpaceMigrationSwitchRequest>() ?: return@post
            call.respondOutcome(metrics.timed("space_migration.switch") { service.switch(accountId, request) })
        }
        post("/switch-back", SpaceMigrationDocs.switchBack) {
            val accountId = extractUserId(call, tokenService) ?: return@post
            val request = call.migrationBody<SpaceMigrationSwitchBackRequest>() ?: return@post
            call.respondOutcome(metrics.timed("space_migration.switch_back") { service.switchBack(accountId, request) })
        }
    }
}

/**
 * Runs [block] and records it as the operation [name]. A refusal is an answer, not a failure: only a
 * call that threw, which the client sees as a server error, counts as one.
 */
private inline fun <T> SyncMetricsRegistry?.timed(
    name: String,
    block: () -> T,
): T {
    val start = System.currentTimeMillis()
    var success = false
    try {
        return block().also { success = true }
    } finally {
        this?.recordOperation(name, System.currentTimeMillis() - start, success)
    }
}

/** Copies one legacy file into the personal space of a move that is uploading, holding a transfer slot while it runs. */
@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.registerMedia(
    accountId: UUID,
    migrations: SpaceMigrationService,
    copier: LegacyMediaCopier,
    accounts: AccountRepository,
    transfers: TransferSlots,
    request: SpaceMigrationRequest,
) {
    if (migrations.active(accountId, request.migrationId, SpaceMigrationPhase.UPLOADING) == null) {
        return respondOutcome<RegisteredLegacyMedia>(
            SpaceMigrationResult.Refused(SpaceMigrationErrorCodes.NOT_ACTIVE, "No move with this id is uploading"),
        )
    }
    val did =
        accounts.findById(accountId.toKotlinUuid())?.did
            ?: return respondOutcome<RegisteredLegacyMedia>(
                SpaceMigrationResult.Refused(SpaceMigrationErrorCodes.NO_IDENTITY, "This account has no DID"),
            )
    val slot = transfers.tryStreamedUpload()
    if (slot == null) {
        response.header(HttpHeaders.RetryAfter, COPY_BUSY_RETRY_AFTER_SECONDS.toString())
        return respond(HttpStatusCode.ServiceUnavailable, error("SERVER_BUSY", "The server is copying as many files as it can; try again"))
    }
    try {
        respondOutcome(copier.copy(accountId, SpaceRef.personal(did).uri, requiredPathParam("mediaId")))
    } finally {
        slot.release()
    }
}

/** The caller's account for a call that starts or advances a move, or null after answering `401`, `403` or `404 FEATURE_DISABLED`. */
private suspend fun ApplicationCall.moveCaller(
    tokenService: TokenService?,
    newMoves: () -> Boolean,
): UUID? {
    val accountId = extractUserId(this, tokenService) ?: return null
    if (newMoves()) return accountId
    respondDisabled()
    return null
}

/**
 * The caller's account for a call that both a move and a switch back make. While new moves are off,
 * only an account that switched may make it, because only its switch back still needs it.
 */
private suspend fun ApplicationCall.moveOrSwitchBackCaller(
    tokenService: TokenService?,
    service: SpaceMigrationService,
    newMoves: () -> Boolean,
): UUID? {
    val accountId = extractUserId(this, tokenService) ?: return null
    if (newMoves() || service.status(accountId).phase == SpaceMigrationPhase.SWITCHED) return accountId
    respondDisabled()
    return null
}

private suspend inline fun <reified T> ApplicationCall.migrationBody(): T? =
    try {
        migrationJson.decodeFromString<T>(receiveText())
    } catch (_: SerializationException) {
        respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", "Invalid request body"))
        null
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", "Invalid request body"))
        null
    }

private suspend inline fun <reified T : Any> ApplicationCall.respondOutcome(outcome: SpaceMigrationResult<T>) {
    when (outcome) {
        is SpaceMigrationResult.Done -> {
            respond(outcome.value)
        }

        is SpaceMigrationResult.Refused -> {
            val status = if (outcome.code == LegacyMediaCopier.MEDIA_NOT_FOUND) HttpStatusCode.NotFound else HttpStatusCode.Conflict
            respond(status, error(outcome.code, outcome.message))
        }
    }
}

private suspend fun ApplicationCall.respondDisabled() =
    respond(HttpStatusCode.NotFound, error("FEATURE_DISABLED", "Moving journals into personal spaces isn't turned on for this server"))
