package app.logdate.server.routes.sync

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.logdate.LogDateMoment
import app.logdate.server.logdate.LogDateMomentsRepository
import app.logdate.server.logdate.LogDateRecordState
import app.logdate.server.logdate.MomentUpdateResult
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.SyncMomentDocs
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.MomentChange
import app.logdate.shared.model.sync.MomentChangesResponse
import app.logdate.shared.model.sync.MomentDeletion
import app.logdate.shared.model.sync.MomentUpdateRequest
import app.logdate.shared.model.sync.MomentUpdateResponse
import app.logdate.shared.model.sync.MomentUploadRequest
import app.logdate.shared.model.sync.MomentUploadResponse
import app.logdate.shared.model.sync.VersionConstraint
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import studio.hypertext.atproto.syntax.RecordKey

private const val MOMENT_PAYLOAD_PREFIX = "LDSE2:"

/** A moment's encrypted payload is small (a caption, a place, a heart rate); 64 KiB leaves ample room. */
internal const val MAX_MOMENT_PAYLOAD_LENGTH = 64 * 1024

/**
 * `/moments`: marked moments, in their own collection so the entry and journal feeds never carry them and
 * clients that do not know moments never download one. Auth, account scoping and tombstones work as they
 * do for entries, and like entries no quota applies.
 */
internal fun Route.syncMomentRoutes(
    accessTokenVerifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    momentsRepository: LogDateMomentsRepository,
) {
    route("/moments") {
        upsertMomentRoute(accessTokenVerifier, storageModes, metrics, momentsRepository)
        listMomentChangesRoute(accessTokenVerifier, storageModes, metrics, momentsRepository)
        getMomentRoute(accessTokenVerifier, storageModes, metrics, momentsRepository)
        patchMomentRoute(accessTokenVerifier, storageModes, metrics, momentsRepository)
        deleteMomentRoute(accessTokenVerifier, storageModes, metrics, momentsRepository)
    }
}

private fun Route.upsertMomentRoute(
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    repository: LogDateMomentsRepository,
) {
    put("/{momentId}", SyncMomentDocs.upsertMoment) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val userId = extractSyncUserId(call, verifier, storageModes) ?: return@put
            val momentId = call.requiredPathParam("momentId")
            val req = call.receive<MomentUploadRequest>()
            if (req.id != momentId) {
                return@put call.respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", "Request body id must match path momentId"))
            }
            if (call.rejectInvalidMoment(momentId, req.payload, req.schema)) return@put
            val moment = LogDateMoment(momentId, req.capturedAt, req.lastUpdated, 0L, req.deviceId, req.payload, req.schema)
            val strictCreate = call.isStrictCreate()
            if (strictCreate || call.isCreateOnly()) {
                call.respondConditionalCreate(
                    outcome = repository.createMomentIfAbsent(userId, moment, respectTombstones = strictCreate),
                    noun = "moment",
                    location = "/api/v1/moments/$momentId",
                ) { MomentUploadResponse(it.id, it.version, it.lastUpdated) }
                success = true
                return@put
            }
            val wasCreated = repository.momentState(userId, momentId) !is LogDateRecordState.Live
            val stored = repository.upsertMoment(userId, moment)
            val response = MomentUploadResponse(stored.id, stored.version, stored.lastUpdated)
            if (wasCreated) {
                call.response.headers.append(HttpHeaders.Location, "/api/v1/moments/$momentId")
                call.respond(HttpStatusCode.Created, response)
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
            success = true
        } finally {
            metrics.recordOperation(METRIC_MOMENT_UPLOAD, System.currentTimeMillis() - start, success)
        }
    }
}

private fun Route.listMomentChangesRoute(
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    repository: LogDateMomentsRepository,
) {
    get(SyncMomentDocs.listMomentChanges) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val userId = extractSyncUserId(call, verifier, storageModes) ?: return@get
            val since = parseSinceParameter(call) ?: return@get
            val changeSet = repository.momentChanges(userId, since, call.resolvePageSize())
            call.respond(
                MomentChangesResponse(
                    changes = changeSet.changes.map(LogDateMoment::toMomentChange),
                    deletions = changeSet.deletions.map { MomentDeletion(it.id, it.deletedAt, it.serverVersion) },
                    lastTimestamp = changeSet.lastTimestamp,
                    hasMore = changeSet.hasMore,
                ),
            )
            success = true
        } finally {
            metrics.recordOperation(METRIC_MOMENT_CHANGES, System.currentTimeMillis() - start, success)
        }
    }
}

private fun Route.getMomentRoute(
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    repository: LogDateMomentsRepository,
) {
    get("/{momentId}", SyncMomentDocs.getMoment) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val userId = extractSyncUserId(call, verifier, storageModes) ?: return@get
            val moment =
                repository.getMoment(userId, call.requiredPathParam("momentId"))
                    ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Moment not found"))
            call.respond(moment.toMomentChange())
            success = true
        } finally {
            metrics.recordOperation(METRIC_MOMENT_CHANGES, System.currentTimeMillis() - start, success)
        }
    }
}

private fun Route.patchMomentRoute(
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    repository: LogDateMomentsRepository,
) {
    patch("/{momentId}", SyncMomentDocs.patchMoment) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val userId = extractSyncUserId(call, verifier, storageModes) ?: return@patch
            val momentId = call.requiredPathParam("momentId")
            val req = call.receive<MomentUpdateRequest>()
            if (call.rejectInvalidMoment(momentId, req.payload, req.schema)) return@patch
            val expected = (req.versionConstraint as? VersionConstraint.Known)?.serverVersion
            val result =
                repository.updateMomentIfVersion(userId, momentId, expected) { existing ->
                    existing.copy(
                        capturedAt = req.capturedAt ?: existing.capturedAt,
                        lastUpdated = req.lastUpdated,
                        deviceId = req.deviceId,
                        payload = req.payload,
                        schema = req.schema,
                    )
                }
            val updated =
                when (result) {
                    is MomentUpdateResult.Updated -> {
                        result.moment
                    }

                    MomentUpdateResult.Missing -> {
                        return@patch call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Moment not found"))
                    }

                    MomentUpdateResult.Conflict -> {
                        metrics.recordConflict()
                        return@patch call.respond(HttpStatusCode.Conflict, error("CONFLICT", "Server has a newer version"))
                    }
                }
            call.respond(MomentUpdateResponse(momentId, updated.version, updated.lastUpdated))
            success = true
        } finally {
            metrics.recordOperation(METRIC_MOMENT_UPDATE, System.currentTimeMillis() - start, success)
        }
    }
}

private fun Route.deleteMomentRoute(
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    metrics: SyncMetricsRegistry,
    repository: LogDateMomentsRepository,
) {
    delete("/{momentId}", SyncMomentDocs.deleteMoment) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val userId = extractSyncUserId(call, verifier, storageModes) ?: return@delete
            repository.deleteMoment(userId, call.requiredPathParam("momentId"), System.currentTimeMillis())
            call.respond(HttpStatusCode.NoContent)
            success = true
        } finally {
            metrics.recordOperation(METRIC_MOMENT_DELETE, System.currentTimeMillis() - start, success)
        }
    }
}

/** Answers 400 and returns true when a moment's ID, payload or schema cannot be stored. */
private suspend fun ApplicationCall.rejectInvalidMoment(
    id: String,
    payload: String,
    schema: Int,
): Boolean {
    val problem =
        when {
            !RecordKey.isValid(id) -> "Moment ID is not a valid record key"
            !payload.startsWith(MOMENT_PAYLOAD_PREFIX) -> "Moment payload must be one LDSE2 encrypted value"
            payload.encodeToByteArray().size > MAX_MOMENT_PAYLOAD_LENGTH -> "Moment payload is larger than 64 KiB"
            schema < 1 -> "Moment schema must be 1 or higher"
            else -> return false
        }
    respond(HttpStatusCode.BadRequest, error("INVALID_MOMENT", problem))
    return true
}

private fun LogDateMoment.toMomentChange(): MomentChange =
    MomentChange(
        id = id,
        capturedAt = capturedAt,
        lastUpdated = lastUpdated,
        deviceId = deviceId,
        payload = payload,
        schema = schema,
        serverVersion = version,
    )
