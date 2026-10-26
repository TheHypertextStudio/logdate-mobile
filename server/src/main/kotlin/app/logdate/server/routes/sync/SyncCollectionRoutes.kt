package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.logdate.DraftFormatUpgradeRequiredException
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.LogDateDraft
import app.logdate.server.logdate.LogDateEntry
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.SyncCollectionDocs
import app.logdate.server.routes.docs.SyncDraftDocs
import app.logdate.server.sync.StaleWriteException
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.ContentChangesResponse
import app.logdate.shared.model.sync.ContentDeletion
import app.logdate.shared.model.sync.ContentUpdateRequest
import app.logdate.shared.model.sync.ContentUpdateResponse
import app.logdate.shared.model.sync.ContentUploadRequest
import app.logdate.shared.model.sync.ContentUploadResponse
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.DraftChange
import app.logdate.shared.model.sync.DraftChangesResponse
import app.logdate.shared.model.sync.DraftDeletion
import app.logdate.shared.model.sync.DraftUploadRequest
import app.logdate.shared.model.sync.DraftUploadResponse
import app.logdate.shared.model.sync.JournalUpdateRequest
import app.logdate.shared.model.sync.VersionConstraint
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * CRUD + change-feed endpoints for the four sync collections: contents, journals, associations,
 * and drafts. Each block follows the same shape — PUT to upsert, GET with `since` for paginated
 * change feeds, PATCH with optimistic locking, DELETE for soft deletion.
 */
internal fun Route.syncCollectionRoutes(
    tokenService: TokenService?,
    metrics: SyncMetricsRegistry,
    collectionsRepository: LogDateCollectionsRepository,
) {
    contentRoutes(tokenService, metrics, collectionsRepository)
    journalRoutes(tokenService, metrics, collectionsRepository)
    associationRoutes(tokenService, metrics, collectionsRepository)
    draftRoutes(tokenService, collectionsRepository)
}

private fun Route.contentRoutes(
    tokenService: TokenService?,
    metrics: SyncMetricsRegistry,
    collectionsRepository: LogDateCollectionsRepository,
) {
    route("/contents") {
        put("/{contentId}", SyncCollectionDocs.upsertContent) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@put
                val contentId = call.requiredPathParam("contentId")
                val req = call.receive<ContentUploadRequest>()
                if (req.id != contentId) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        error("VALIDATION_ERROR", "Request body id must match path contentId"),
                    )
                }
                val wasCreated = !collectionsRepository.entryExists(userId, contentId)
                if (call.request.header(HttpHeaders.IfNoneMatch) == "*" && !wasCreated) {
                    return@put call.respond(
                        HttpStatusCode.PreconditionFailed,
                        error("CONTENT_EXISTS", "Content already exists"),
                    )
                }
                Napier.d("Content upsert completed")
                val entry =
                    LogDateEntry(
                        id = contentId,
                        type = req.type,
                        content = req.content,
                        mediaUri = req.mediaUri,
                        durationMs = req.durationMs,
                        createdAt = req.createdAt,
                        lastUpdated = req.lastUpdated,
                        version = 0L,
                        deviceId = req.deviceId,
                        caption = req.caption,
                        photoPresentation = req.photoPresentation,
                        location = req.location,
                        transcript = req.transcript,
                    )
                val stored =
                    try {
                        if (call.request.header(HttpHeaders.IfNoneMatch) == "*") {
                            collectionsRepository.createEntryIfAbsent(userId, entry)
                                ?: return@put call.respond(
                                    HttpStatusCode.PreconditionFailed,
                                    error("CONTENT_EXISTS", "Content already exists"),
                                )
                        } else {
                            collectionsRepository.upsertEntry(userId, entry)
                        }
                    } catch (_: StaleWriteException) {
                        metrics.recordConflict()
                        return@put call.respond(
                            HttpStatusCode.Conflict,
                            error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"),
                        )
                    }
                val response =
                    ContentUploadResponse(
                        id = stored.id,
                        serverVersion = stored.version,
                        uploadedAt = stored.lastUpdated,
                    )
                if (wasCreated) {
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/contents/${stored.id}")
                    call.respond(HttpStatusCode.Created, response)
                } else {
                    call.respond(HttpStatusCode.OK, response)
                }
                success = true
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_CONTENT_UPLOAD, System.currentTimeMillis() - start, success)
            }
        }

        get(SyncCollectionDocs.listContentChanges) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val since = parseSinceParameter(call) ?: return@get
                val pageSize = call.resolvePageSize()
                val changeSet = collectionsRepository.entryChanges(userId, since, pageSize)
                val changes = changeSet.changes.map(LogDateEntry::toContentChange)
                val deletions =
                    changeSet.deletions.map {
                        ContentDeletion(
                            id = it.id,
                            deletedAt = it.deletedAt,
                            serverVersion = it.serverVersion,
                        )
                    }
                call.respond(ContentChangesResponse(changes, deletions, changeSet.lastTimestamp, changeSet.hasMore))
                success = true
            } finally {
                metrics.recordOperation(METRIC_CONTENT_CHANGES, System.currentTimeMillis() - start, success)
            }
        }

        get("/{contentId}", SyncCollectionDocs.getContent) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val contentId = call.requiredPathParam("contentId")
                val record =
                    collectionsRepository.getEntry(userId, contentId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Content not found"))
                call.respond(record.toContentChange())
                success = true
            } finally {
                metrics.recordOperation(METRIC_CONTENT_CHANGES, System.currentTimeMillis() - start, success)
            }
        }

        patch("/{contentId}", SyncCollectionDocs.patchContent) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@patch
                val contentId = call.requiredPathParam("contentId")
                val req = call.receive<ContentUpdateRequest>()
                val existing = collectionsRepository.getEntry(userId, contentId)
                if (req.isOutdated(existing?.version)) {
                    metrics.recordConflict()
                    return@patch call.respond(
                        HttpStatusCode.Conflict,
                        error("CONFLICT", "Server has a newer version"),
                    )
                }
                val updated =
                    collectionsRepository.upsertEntry(
                        userId = userId,
                        entry =
                            LogDateEntry(
                                id = contentId,
                                type = existing?.type ?: "TEXT",
                                content = req.content ?: existing?.content,
                                mediaUri = req.mediaUri ?: existing?.mediaUri,
                                durationMs = req.durationMs ?: existing?.durationMs ?: 0L,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                                lastUpdated = req.lastUpdated,
                                version = existing?.version ?: 0L,
                                deviceId = req.deviceId,
                                caption = req.caption ?: existing?.caption,
                                photoPresentation = req.photoPresentation ?: existing?.photoPresentation,
                                location = req.location ?: existing?.location,
                                transcript = req.transcript,
                            ),
                    )
                call.respond(ContentUpdateResponse(contentId, updated.version, updated.lastUpdated))
                success = true
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_CONTENT_UPDATE, System.currentTimeMillis() - start, success)
            }
        }

        delete("/{contentId}", SyncCollectionDocs.deleteContent) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@delete
                val contentId = call.requiredPathParam("contentId")
                collectionsRepository.deleteEntry(userId, contentId, System.currentTimeMillis())
                call.respond(HttpStatusCode.NoContent)
                success = true
            } finally {
                metrics.recordOperation(METRIC_CONTENT_DELETE, System.currentTimeMillis() - start, success)
            }
        }
    }
}

private fun Route.draftRoutes(
    tokenService: TokenService?,
    collectionsRepository: LogDateCollectionsRepository,
) {
    route("/drafts") {
        put("/{draftId}", SyncDraftDocs.upsertDraft) {
            val userId = extractUserId(call, tokenService) ?: return@put
            val draftId = call.requiredPathParam("draftId")
            val req = call.receive<DraftUploadRequest>()
            val encryptedBlocks = req.encryptedBlocks
            if ((req.encryptedBlocksVersion == null) != (req.encryptedBlocks == null) ||
                (req.encryptedBlocksVersion != null && req.encryptedBlocksVersion != 1) ||
                (encryptedBlocks != null && !encryptedBlocks.startsWith("LDSE2:"))
            ) {
                call.respond(HttpStatusCode.BadRequest, error("INVALID_DRAFT_FORMAT", "Unsupported draft format"))
                return@put
            }
            val stored =
                try {
                    collectionsRepository.upsertDraft(
                        userId = userId,
                        draft =
                            LogDateDraft(
                                id = draftId,
                                content = req.content,
                                blockTypes = req.blockTypes,
                                journalIds = req.journalIds,
                                createdAt = req.createdAt,
                                lastUpdated = req.lastUpdated,
                                version = 0L,
                                deviceId = req.deviceId,
                                encryptedBlocksVersion = req.encryptedBlocksVersion,
                                encryptedBlocks = req.encryptedBlocks,
                            ),
                    )
                } catch (_: StaleWriteException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        error("DELETION_CONFLICT", "This draft was deleted; keep the edit for review"),
                    )
                    return@put
                } catch (_: DraftFormatUpgradeRequiredException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        error("DRAFT_FORMAT_UPGRADE_REQUIRED", "Upgrade this client before editing this draft"),
                    )
                    return@put
                }
            call.respond(
                HttpStatusCode.OK,
                DraftUploadResponse(
                    id = stored.id,
                    serverVersion = stored.version,
                    uploadedAt = stored.lastUpdated,
                ),
            )
        }

        get("/changes", SyncDraftDocs.listDraftChanges) {
            val userId = extractUserId(call, tokenService) ?: return@get
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DRAFT_SYNC_PAGE_SIZE
            val changeSet = collectionsRepository.draftChanges(userId, since, limit)
            call.respond(
                DraftChangesResponse(
                    drafts =
                        changeSet.changes.map { draft ->
                            DraftChange(
                                id = draft.id,
                                content = draft.content,
                                blockTypes = draft.blockTypes,
                                journalIds = draft.journalIds,
                                createdAt = draft.createdAt,
                                lastUpdated = draft.lastUpdated,
                                deviceId = draft.deviceId,
                                serverVersion = draft.version,
                                encryptedBlocksVersion = draft.encryptedBlocksVersion,
                                encryptedBlocks = draft.encryptedBlocks,
                            )
                        },
                    deletions =
                        changeSet.deletions.map {
                            DraftDeletion(it.id, it.deletedAt, it.serverVersion)
                        },
                    lastTimestamp = changeSet.lastTimestamp,
                    hasMore = changeSet.hasMore,
                ),
            )
        }

        delete("/{draftId}", SyncDraftDocs.deleteDraft) {
            val userId = extractUserId(call, tokenService) ?: return@delete
            val draftId = call.requiredPathParam("draftId")
            collectionsRepository.deleteDraft(userId, draftId, System.currentTimeMillis())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
internal data class AssociationLinkUpsertRequest(
    val createdAt: Long,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
)

internal suspend fun parseSinceParameter(call: io.ktor.server.application.ApplicationCall): Long? {
    val raw = call.request.queryParameters["since"] ?: return 0L
    return raw.toLongOrNull() ?: run {
        call.respond(
            HttpStatusCode.BadRequest,
            error("INVALID_PARAMETER", "since parameter must be a valid long"),
        )
        null
    }
}

internal fun io.ktor.server.application.ApplicationCall.resolvePageSize(): Int =
    (request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_SYNC_PAGE_SIZE)
        .coerceIn(1, MAX_SYNC_PAGE_SIZE)

private fun ContentUpdateRequest.isOutdated(currentVersion: Long?): Boolean =
    (versionConstraint as? VersionConstraint.Known)?.serverVersion?.let { it != currentVersion } == true

internal fun JournalUpdateRequest.isOutdated(currentVersion: Long?): Boolean =
    (versionConstraint as? VersionConstraint.Known)?.serverVersion?.let { it != currentVersion } == true
