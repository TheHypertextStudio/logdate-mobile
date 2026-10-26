package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.logdate.JournalMergeConflictException
import app.logdate.server.logdate.JournalMergeDestinationMissingException
import app.logdate.server.logdate.JournalMergedException
import app.logdate.server.logdate.LogDateAssociation
import app.logdate.server.logdate.LogDateAssociationRef
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.LogDateJournal
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.SyncAssociationDocs
import app.logdate.server.routes.docs.SyncCollectionDocs
import app.logdate.server.routes.docs.SyncJournalMergeDocs
import app.logdate.server.sync.StaleWriteException
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.AssociationChangesResponse
import app.logdate.shared.model.sync.AssociationDeleteRequest
import app.logdate.shared.model.sync.AssociationDeletion
import app.logdate.shared.model.sync.AssociationUploadRequest
import app.logdate.shared.model.sync.AssociationUploadResponse
import app.logdate.shared.model.sync.JournalChangesResponse
import app.logdate.shared.model.sync.JournalDeletion
import app.logdate.shared.model.sync.JournalMergeRequest
import app.logdate.shared.model.sync.JournalUpdateRequest
import app.logdate.shared.model.sync.JournalUpdateResponse
import app.logdate.shared.model.sync.JournalUploadRequest
import app.logdate.shared.model.sync.JournalUploadResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

internal fun Route.journalRoutes(
    tokenService: TokenService?,
    metrics: SyncMetricsRegistry,
    collectionsRepository: LogDateCollectionsRepository,
) {
    route("/journals") {
        post("/{journalId}/merge", SyncJournalMergeDocs.merge) {
            val userId = extractUserId(call, tokenService) ?: return@post
            val journalId = call.requiredPathParam("journalId")
            val request = call.receive<JournalMergeRequest>()
            try {
                call.respond(collectionsRepository.merge(userId, journalId, request))
            } catch (_: JournalMergeDestinationMissingException) {
                call.respond(HttpStatusCode.NotFound, error("MERGE_DESTINATION_MISSING", "Merge destination is unavailable"))
            } catch (conflict: JournalMergeConflictException) {
                call.respond(HttpStatusCode.Conflict, error("MERGE_CONFLICT", conflict.message.orEmpty()))
            } catch (invalid: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", invalid.message.orEmpty()))
            }
        }

        put("/{journalId}", SyncCollectionDocs.upsertJournal) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@put
                val journalId = call.requiredPathParam("journalId")
                val req = call.receive<JournalUploadRequest>()
                if (req.id != journalId) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        error("VALIDATION_ERROR", "Request body id must match path journalId"),
                    )
                }
                collectionsRepository.journalMergeDestination(userId, journalId)?.let { throw JournalMergedException(it) }
                val wasCreated = !collectionsRepository.journalExists(userId, journalId)
                if (call.request.header(HttpHeaders.IfNoneMatch) == "*" && !wasCreated) {
                    return@put call.respond(
                        HttpStatusCode.PreconditionFailed,
                        error("JOURNAL_EXISTS", "Journal already exists"),
                    )
                }
                Napier.d("Journal upsert completed")
                val journal =
                    LogDateJournal(
                        id = journalId,
                        title = req.title,
                        description = req.description,
                        createdAt = req.createdAt,
                        lastUpdated = req.lastUpdated,
                        version = 0L,
                        deviceId = req.deviceId,
                    )
                val stored =
                    if (call.request.header(HttpHeaders.IfNoneMatch) == "*") {
                        collectionsRepository.createJournalIfAbsent(userId, journal)
                            ?: return@put call.respond(HttpStatusCode.PreconditionFailed, error("JOURNAL_EXISTS", "Journal already exists"))
                    } else {
                        collectionsRepository.upsertJournal(userId, journal)
                    }
                val response = JournalUploadResponse(journalId, stored.version, stored.lastUpdated)
                if (wasCreated) {
                    call.response.headers.append(HttpHeaders.Location, "/api/v1/journals/$journalId")
                    call.respond(HttpStatusCode.Created, response)
                } else {
                    call.respond(HttpStatusCode.OK, response)
                }
                success = true
            } catch (merged: JournalMergedException) {
                metrics.recordConflict()
                call.respond(
                    HttpStatusCode.Conflict,
                    error(
                        "JOURNAL_MERGED",
                        "Journal has been merged",
                        mapOf(
                            "destinationId" to merged.destinationId,
                        ),
                    ),
                )
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_JOURNAL_UPLOAD, System.currentTimeMillis() - start, success)
            }
        }

        get(SyncCollectionDocs.listJournalChanges) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val since = parseSinceParameter(call) ?: return@get
                val pageSize = call.resolvePageSize()
                val changeSet = collectionsRepository.journalChanges(userId, since, pageSize)
                val changes = changeSet.changes.map(LogDateJournal::toJournalChange)
                val deletions =
                    changeSet.deletions.map {
                        JournalDeletion(
                            id = it.id,
                            deletedAt = it.deletedAt,
                            serverVersion = it.serverVersion,
                            mergedIntoJournalId = it.mergedIntoJournalId,
                        )
                    }
                call.respond(JournalChangesResponse(changes, deletions, changeSet.lastTimestamp, changeSet.hasMore))
                success = true
            } finally {
                metrics.recordOperation(METRIC_JOURNAL_CHANGES, System.currentTimeMillis() - start, success)
            }
        }

        get("/{journalId}", SyncCollectionDocs.getJournal) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val journalId = call.requiredPathParam("journalId")
                val record =
                    collectionsRepository.getJournal(userId, journalId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Journal not found"))
                call.respond(record.toJournalChange())
                success = true
            } finally {
                metrics.recordOperation(METRIC_JOURNAL_CHANGES, System.currentTimeMillis() - start, success)
            }
        }

        patch("/{journalId}", SyncCollectionDocs.patchJournal) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@patch
                val journalId = call.requiredPathParam("journalId")
                val req = call.receive<JournalUpdateRequest>()
                collectionsRepository.journalMergeDestination(userId, journalId)?.let { throw JournalMergedException(it) }
                val existing = collectionsRepository.getJournal(userId, journalId)
                if (req.isOutdated(existing?.version)) {
                    metrics.recordConflict()
                    return@patch call.respond(
                        HttpStatusCode.Conflict,
                        error("CONFLICT", "Server has a newer version"),
                    )
                }
                val updatedAt = System.currentTimeMillis()
                val stored =
                    collectionsRepository.upsertJournal(
                        userId = userId,
                        journal =
                            LogDateJournal(
                                id = journalId,
                                title = req.title ?: existing?.title.orEmpty(),
                                description = req.description ?: existing?.description.orEmpty(),
                                createdAt = existing?.createdAt ?: updatedAt,
                                lastUpdated = req.lastUpdated,
                                version = existing?.version ?: 0L,
                                deviceId = req.deviceId,
                            ),
                    )
                call.respond(JournalUpdateResponse(journalId, stored.version, stored.lastUpdated))
                success = true
            } catch (merged: JournalMergedException) {
                metrics.recordConflict()
                call.respond(
                    HttpStatusCode.Conflict,
                    error(
                        "JOURNAL_MERGED",
                        "Journal has been merged",
                        mapOf(
                            "destinationId" to merged.destinationId,
                        ),
                    ),
                )
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_JOURNAL_UPDATE, System.currentTimeMillis() - start, success)
            }
        }

        delete("/{journalId}", SyncCollectionDocs.deleteJournal) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@delete
                val journalId = call.requiredPathParam("journalId")
                collectionsRepository.deleteJournal(userId, journalId, System.currentTimeMillis())
                call.respond(HttpStatusCode.NoContent)
                success = true
            } finally {
                metrics.recordOperation(METRIC_JOURNAL_DELETE, System.currentTimeMillis() - start, success)
            }
        }
    }
}

internal fun Route.associationRoutes(
    tokenService: TokenService?,
    metrics: SyncMetricsRegistry,
    collectionsRepository: LogDateCollectionsRepository,
) {
    route("/associations") {
        post(SyncAssociationDocs.uploadAssociations) {
            val start = System.currentTimeMillis()

            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@post
                val req = call.receive<AssociationUploadRequest>()
                val uploadedAt = System.currentTimeMillis()
                collectionsRepository.upsertAssociations(
                    userId = userId,
                    associations =
                        req.associations.map {
                            LogDateAssociation(
                                journalId = it.journalId,
                                entryId = it.contentId,
                                createdAt = it.createdAt,
                                version = 0L,
                                deviceId = it.deviceId,
                            )
                        },
                )
                call.respond(AssociationUploadResponse(req.associations.size, uploadedAt))
                success = true
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_ASSOCIATION_UPLOAD, System.currentTimeMillis() - start, success)
            }
        }

        get(SyncAssociationDocs.listAssociationChanges) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val since = parseSinceParameter(call) ?: return@get
                val pageSize = call.resolvePageSize()
                val changeSet = collectionsRepository.associationChanges(userId, since, pageSize)
                val changes = changeSet.changes.map(LogDateAssociation::toAssociationChange)
                val deletions =
                    changeSet.deletions.map {
                        AssociationDeletion(
                            journalId = it.association.journalId,
                            contentId = it.association.entryId,
                            deletedAt = it.deletedAt,
                            serverVersion = it.serverVersion,
                        )
                    }
                call.respond(AssociationChangesResponse(changes, deletions, changeSet.lastTimestamp, changeSet.hasMore))
                success = true
            } finally {
                metrics.recordOperation(METRIC_ASSOCIATION_CHANGES, System.currentTimeMillis() - start, success)
            }
        }

        put("/{journalId}/{contentId}", SyncAssociationDocs.upsertAssociation) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@put
                val journalId = call.requiredPathParam("journalId")
                val contentId = call.requiredPathParam("contentId")
                val req = call.receive<AssociationLinkUpsertRequest>()
                collectionsRepository.upsertAssociations(
                    userId = userId,
                    associations =
                        listOf(
                            LogDateAssociation(
                                journalId = journalId,
                                entryId = contentId,
                                createdAt = req.createdAt,
                                version = 0L,
                                deviceId = req.deviceId,
                            ),
                        ),
                )
                call.respond(HttpStatusCode.NoContent)
                success = true
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_ASSOCIATION_UPLOAD, System.currentTimeMillis() - start, success)
            }
        }

        delete("/{journalId}/{contentId}", SyncAssociationDocs.deleteAssociation) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@delete
                val journalId = call.requiredPathParam("journalId")
                val contentId = call.requiredPathParam("contentId")
                collectionsRepository.deleteAssociations(
                    userId,
                    listOf(LogDateAssociationRef(journalId, contentId)),
                    System.currentTimeMillis(),
                )
                call.respond(HttpStatusCode.NoContent)
                success = true
            } finally {
                metrics.recordOperation(METRIC_ASSOCIATION_DELETE, System.currentTimeMillis() - start, success)
            }
        }

        delete(SyncAssociationDocs.deleteAssociations) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@delete
                val req = call.receive<AssociationDeleteRequest>()
                collectionsRepository.deleteAssociations(
                    userId,
                    req.associations.map { LogDateAssociationRef(it.journalId, it.contentId) },
                    System.currentTimeMillis(),
                )
                call.respond(HttpStatusCode.NoContent)
                success = true
            } finally {
                metrics.recordOperation(METRIC_ASSOCIATION_DELETE, System.currentTimeMillis() - start, success)
            }
        }
    }
}
