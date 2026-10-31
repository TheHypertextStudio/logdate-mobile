package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.sinceAndLimit
import app.logdate.server.routes.sync.AssociationLinkUpsertRequest
import app.logdate.server.routes.sync.DEFAULT_SYNC_PAGE_SIZE
import app.logdate.server.routes.sync.MAX_ASSOCIATION_BATCH
import app.logdate.server.routes.sync.MAX_SYNC_PAGE_SIZE
import app.logdate.server.routes.syncError
import app.logdate.shared.model.sync.Association
import app.logdate.shared.model.sync.AssociationChange
import app.logdate.shared.model.sync.AssociationChangesResponse
import app.logdate.shared.model.sync.AssociationDeleteItem
import app.logdate.shared.model.sync.AssociationDeleteRequest
import app.logdate.shared.model.sync.AssociationDeletion
import app.logdate.shared.model.sync.AssociationUploadRequest
import app.logdate.shared.model.sync.AssociationUploadResponse
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

/** Documentation for the entry-to-journal link endpoints in `SyncCollectionRoutes.kt`. */
internal object SyncAssociationDocs {
    private val batchTooLarge =
        ErrorCase(
            "BATCH_TOO_LARGE",
            "The request carries more than $MAX_ASSOCIATION_BATCH links, or its body is larger than a full batch could " +
                "be. Split it into smaller requests.",
            "Send at most $MAX_ASSOCIATION_BATCH links per request",
        )

    val uploadAssociations: RouteConfig.() -> Unit = {
        bearerOperation(
            "uploadAssociations",
            ApiTags.ASSOCIATIONS,
            "Link entries to journals in bulk",
            """
            Creates or refreshes many entry-to-journal links in one request. Each item names a `journalId` and a
            `contentId`; the pair is the link's identity, so sending the same pair again updates it. This
            is what the app calls after a batch of new entries; for a single link there is also
            **Link one entry to a journal**.

            The server does not check that the journal or entry exist, so you can upload links before, after,
            or alongside the things they connect.

            With `?ifAbsent=true` only links that were never written are saved: links that already exist are
            listed in `existing` and deleted links in `deleted`, and neither is touched. `uploadedCount` then
            counts only the new links.
            """,
        )
        request {
            jsonBody(
                AssociationUploadRequest(
                    associations =
                        listOf(
                            Association(
                                journalId = SyncExamples.JOURNAL_ID,
                                contentId = SyncExamples.CONTENT_ID,
                                createdAt = SyncExamples.CREATED_AT,
                                deviceId = SyncExamples.deviceId,
                            ),
                        ),
                ),
                "The links to create or refresh.",
            )
            ifAbsentQuery("links that exist or were deleted are reported in the response and left alone.")
        }
        response {
            ok(
                "All links were saved.",
                AssociationUploadResponse(uploadedCount = 1, uploadedAt = SyncExamples.SERVER_VERSION),
            )
            syncError(HttpStatusCode.PayloadTooLarge, batchTooLarge)
            syncUnauthorized()
            syncStorageModeMismatch()
            syncMigrationHold()
            syncServerError()
        }
    }

    val listAssociationChanges: RouteConfig.() -> Unit = {
        bearerOperation(
            "listAssociationChanges",
            ApiTags.ASSOCIATIONS,
            "Page link changes",
            """
            Returns links created or refreshed after the `since` cursor, and tombstones for links removed after
            it. Same cursor rules as **Page entry changes**. Because links share the account's version sequence,
            a device that pages entries, journals and links from the same `since` gets a consistent picture.
            """,
        )
        request { sinceAndLimit(DEFAULT_SYNC_PAGE_SIZE, MAX_SYNC_PAGE_SIZE) }
        response {
            ok(
                "A page of changes. Send `lastTimestamp` back as the next `since`.",
                AssociationChangesResponse(
                    changes =
                        listOf(
                            AssociationChange(
                                journalId = SyncExamples.JOURNAL_ID,
                                contentId = SyncExamples.CONTENT_ID,
                                createdAt = SyncExamples.CREATED_AT,
                                serverVersion = SyncExamples.SERVER_VERSION,
                            ),
                        ),
                    deletions =
                        listOf(
                            AssociationDeletion(
                                journalId = SyncExamples.JOURNAL_ID,
                                contentId = "01J7Q2W1V2U3T4S5R6Q7P8N9M0",
                                deletedAt = SyncExamples.CREATED_AT - 60_000,
                            ),
                        ),
                    lastTimestamp = SyncExamples.SERVER_VERSION,
                    hasMore = false,
                ),
            )
            syncError(HttpStatusCode.BadRequest, SyncExamples.invalidSince)
            syncUnauthorized()
            syncStorageModeMismatch()
            syncServerError()
        }
    }

    val upsertAssociation: RouteConfig.() -> Unit = {
        bearerOperation(
            "upsertAssociation",
            ApiTags.ASSOCIATIONS,
            "Link one entry to a journal",
            """
            Creates or refreshes a single link between the journal and the entry named in the path. Safe to
            repeat. Answers `204` with no body; the link's version appears in the next **Page link changes**.
            """,
        )
        request {
            journalId("")
            contentId("")
            jsonBody(
                AssociationLinkUpsertRequest(createdAt = SyncExamples.CREATED_AT, deviceId = SyncExamples.deviceId),
                "When and where the link was made.",
            )
        }
        response {
            noContent("The link exists.")
            syncUnauthorized()
            syncStorageModeMismatch()
            syncMigrationHold()
            syncServerError()
        }
    }

    val deleteAssociation: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteAssociation",
            ApiTags.ASSOCIATIONS,
            "Unlink one entry from a journal",
            """
            Removes the link between the journal and the entry named in the path. Neither the journal nor the
            entry is touched. The removal becomes a tombstone in **Page link changes**. Repeating the call
            answers `204` again.
            """,
        )
        request {
            journalId("")
            contentId("")
        }
        response {
            noContent("The link is gone (or already was).")
            syncUnauthorized()
            syncStorageModeMismatch()
            syncMigrationHold()
            syncServerError()
        }
    }

    val deleteAssociations: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteAssociations",
            ApiTags.ASSOCIATIONS,
            "Unlink entries in bulk",
            """
            Removes many links in one request, each named by its `journalId` and `contentId` pair. Pairs that do
            not exist are ignored. Every removal becomes a tombstone in **Page link changes**.
            """,
        )
        request {
            jsonBody(
                AssociationDeleteRequest(
                    associations = listOf(AssociationDeleteItem(journalId = SyncExamples.JOURNAL_ID, contentId = SyncExamples.CONTENT_ID)),
                ),
                "The links to remove.",
            )
        }
        response {
            noContent("The links are gone.")
            syncError(HttpStatusCode.PayloadTooLarge, batchTooLarge)
            syncUnauthorized()
            syncStorageModeMismatch()
            syncMigrationHold()
            syncServerError()
        }
    }
}
