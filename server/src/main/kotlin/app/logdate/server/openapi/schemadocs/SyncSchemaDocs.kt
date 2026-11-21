package app.logdate.server.openapi.schemadocs

import app.logdate.server.openapi.SchemaDoc

/** Prose for the contents, journals, associations, drafts, media, backups and sync-status schemas. */
internal object SyncSchemaDocs {
    val docs: Map<String, SchemaDoc> =
        mapOf(
            // ---- Envelopes ----------------------------------------------------------------------
            "SimpleErrorResponse" to
                SchemaDoc(
                    "The error envelope used by every sync endpoint (contents, journals, associations, drafts, media, backups, sync status).",
                    mapOf(
                        "code" to "Stable, upper-snake-case code to branch on, such as `CONFLICT`. Never localized.",
                        "message" to "A sentence for logs and developers. May change between releases; do not parse it.",
                        "details" to
                            "Extra machine-readable context for some codes, for example `retryAfterSeconds` on `RATE_LIMIT_EXCEEDED`. Often empty.",
                        "timestamp" to "When the error was produced. ISO-8601, UTC.",
                    ),
                ),
            "SimpleSuccessResponse_SyncStatusSnapshot" to
                SchemaDoc(
                    "The generic success envelope around a sync-status payload.",
                    mapOf(
                        "data" to "The payload.",
                        "message" to "Always `Success`.",
                        "timestamp" to "When the response was produced. ISO-8601, UTC.",
                    ),
                ),
            "SyncStatusSnapshot" to
                SchemaDoc(
                    "Totals for one account's synced data.",
                    mapOf(
                        "contentCount" to "Entries the server holds, excluding deleted ones.",
                        "journalCount" to "Journals the server holds, excluding deleted ones.",
                        "associationCount" to "Entry-to-journal links the server holds, excluding removed ones.",
                        "lastTimestamp" to
                            "The newest version the server has assigned for this account. If it is higher than your stored cursor, " +
                            "there is something to fetch. An account that has never synced anything has no version yet and gets " +
                            "the current time instead, so also check that the counts are non-zero.",
                    ),
                ),
            // ---- Versioning ---------------------------------------------------------------------
            "VersionConstraint" to
                SchemaDoc(
                    "What a `PATCH` expects the server's current version to be. Send `known` to detect conflicts, `none` (or omit) for last-write-wins.",
                ),
            "VersionConstraint.Known" to
                SchemaDoc(
                    "Ask the server to reject the update if it holds a newer version than the one you last saw.",
                    mapOf(
                        "type" to "Always `known`.",
                        "serverVersion" to
                            "The `serverVersion` you last received for this record. If the server's is higher, the update answers `409 CONFLICT`.",
                    ),
                ),
            "VersionConstraint.None" to
                SchemaDoc("No check: the update always applies, even over newer changes.", mapOf("type" to "Always `none`.")),
            *ContentSyncSchemaDocs.entries,
            // ---- Journals -------------------------------------------------------------------------
            "JournalMergeRequest" to
                SchemaDoc(
                    "A durable request to retire the source journal into a surviving destination while retaining every membership.",
                    mapOf(
                        "operationId" to "A stable operation ID. Retries must preserve this ID and the exact submitted request.",
                        "destinationId" to
                            "The surviving journal's ID. Its metadata stays unchanged; merged destinations resolve to their survivor.",
                        "contentIds" to
                            "Source membership IDs known to this device, including entries it has not downloaded. Combined with remote memberships.",
                    ),
                ),
            "JournalMergeResponse" to
                SchemaDoc(
                    "Confirmation that a journal merge completed, including the destination after following later merges.",
                    mapOf(
                        "operationId" to "The stable operation ID from the request.",
                        "sourceId" to "The retired source journal's ID.",
                        "destinationId" to "The surviving destination after resolving durable journal redirects.",
                    ),
                ),
            "JournalUploadRequest" to
                SchemaDoc(
                    "A whole journal, as sent to **Create or replace a journal**.",
                    mapOf(
                        "id" to "The journal's ID. Must equal the `journalId` in the path.",
                        "title" to "The journal's name.",
                        "description" to "A longer description; may be empty.",
                        "createdAt" to "When the journal was created on the device. $EPOCH_MS",
                        "lastUpdated" to "When it was last edited on the device. $EPOCH_MS",
                        "syncVersion" to SYNC_VERSION_IGNORED,
                        "deviceId" to DEVICE_ID,
                    ),
                ),
            "JournalUploadResponse" to
                SchemaDoc(
                    "Confirmation that a journal was saved.",
                    mapOf(
                        "id" to "The journal's ID.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "uploadedAt" to "When the server accepted the write. $EPOCH_MS",
                    ),
                ),
            "JournalUpdateRequest" to
                SchemaDoc(
                    "The fields of a journal to change. Omitted fields keep their values.",
                    mapOf(
                        "title" to "New name, or omit to keep the current one.",
                        "description" to "New description, or omit to keep the current one.",
                        "lastUpdated" to "When the edit was made on the device. $EPOCH_MS",
                        "syncVersion" to SYNC_VERSION_IGNORED,
                        "deviceId" to DEVICE_ID,
                        "versionConstraint" to "Optional conflict check; see `VersionConstraint`.",
                    ),
                ),
            "JournalUpdateResponse" to
                SchemaDoc(
                    "Confirmation that a journal was updated.",
                    mapOf(
                        "id" to "The journal's ID.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "updatedAt" to "When the server applied the update. $EPOCH_MS",
                    ),
                ),
            "JournalChangesResponse" to
                SchemaDoc(
                    "One page of the journal change feed.",
                    mapOf(
                        "changes" to "Journals created or updated after `since`, oldest version first.",
                        "deletions" to "Journals deleted after `since`.",
                        "lastTimestamp" to CURSOR_LAST_TIMESTAMP,
                        "hasMore" to HAS_MORE,
                    ),
                ),
            "JournalChange" to
                SchemaDoc(
                    "A journal as the server holds it.",
                    mapOf(
                        "id" to "The journal's ID.",
                        "title" to "The journal's name.",
                        "description" to "Its description; may be empty.",
                        "createdAt" to "When it was created on the device. $EPOCH_MS",
                        "lastUpdated" to "The `lastUpdated` the client sent on its last write, stored as given. $EPOCH_MS",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "isDeleted" to IS_DELETED_ALWAYS_FALSE,
                    ),
                ),
            "JournalDeletion" to
                SchemaDoc(
                    "A tombstone: a journal that was deleted.",
                    mapOf(
                        "id" to "The deleted journal's ID.",
                        "deletedAt" to "When it was deleted. $EPOCH_MS",
                        "serverVersion" to DELETION_VERSION,
                        "mergedIntoJournalId" to "The surviving journal's ID for a merge tombstone, or `null` for an ordinary deletion.",
                    ),
                ),
            // ---- Associations ---------------------------------------------------------------------
            "AssociationUploadRequest" to SchemaDoc("Links to create or refresh in bulk.", mapOf("associations" to "The links.")),
            "Association" to
                SchemaDoc(
                    "A link between one entry and one journal. The pair `(journalId, contentId)` is its identity.",
                    mapOf(
                        "journalId" to "The journal's ID.",
                        "contentId" to "The entry's ID.",
                        "createdAt" to "When the link was made on the device. $EPOCH_MS",
                        "syncVersion" to SYNC_VERSION_IGNORED,
                        "deviceId" to DEVICE_ID,
                    ),
                ),
            "AssociationLinkUpsertRequest" to
                SchemaDoc(
                    "Details for a single link whose journal and entry are named in the path.",
                    mapOf("createdAt" to "When the link was made on the device. $EPOCH_MS", "deviceId" to DEVICE_ID),
                ),
            "AssociationUploadResponse" to
                SchemaDoc(
                    "Confirmation that links were saved.",
                    mapOf(
                        "uploadedCount" to "How many links were in the request; with `?ifAbsent=true`, how many were new.",
                        "uploadedAt" to "When the server accepted them. $EPOCH_MS",
                        "existing" to "With `?ifAbsent=true`: links that already existed and were left alone. Absent when there are none.",
                        "deleted" to "With `?ifAbsent=true`: links that were deleted and stay deleted. Absent when there are none.",
                    ),
                ),
            "AssociationChangesResponse" to
                SchemaDoc(
                    "One page of the link change feed.",
                    mapOf(
                        "changes" to "Links created or refreshed after `since`, oldest version first.",
                        "deletions" to "Links removed after `since`.",
                        "lastTimestamp" to CURSOR_LAST_TIMESTAMP,
                        "hasMore" to HAS_MORE,
                    ),
                ),
            "AssociationChange" to
                SchemaDoc(
                    "A link as the server holds it.",
                    mapOf(
                        "journalId" to "The journal's ID.",
                        "contentId" to "The entry's ID.",
                        "createdAt" to "When the link was made on the device. $EPOCH_MS",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "isDeleted" to IS_DELETED_ALWAYS_FALSE,
                    ),
                ),
            "AssociationDeletion" to
                SchemaDoc(
                    "A tombstone: a link that was removed.",
                    mapOf(
                        "journalId" to "The journal's ID.",
                        "contentId" to "The entry's ID.",
                        "deletedAt" to "When the link was removed. $EPOCH_MS",
                        "serverVersion" to DELETION_VERSION,
                    ),
                ),
            "AssociationDeleteRequest" to
                SchemaDoc("Links to remove in bulk.", mapOf("associations" to "The links, by journal and entry ID.")),
            "AssociationDeleteItem" to
                SchemaDoc("One link to remove.", mapOf("journalId" to "The journal's ID.", "contentId" to "The entry's ID.")),
            // ---- Drafts ---------------------------------------------------------------------------
            "DraftUploadRequest" to
                SchemaDoc(
                    "An in-progress entry, as sent to **Save a draft**.",
                    mapOf(
                        "id" to
                            "Ignored. The draft is keyed by the `draftId` in the path and the response echoes that; unlike entries, a mismatch is not rejected.",
                        "content" to "The draft's text so far.",
                        "blockTypes" to "The kinds of block in the draft, in order, such as `TEXT` or `IMAGE`.",
                        "journalIds" to "Journals the finished entry will belong to.",
                        "createdAt" to "When the draft was started on the device. $EPOCH_MS",
                        "lastUpdated" to "When it was last edited on the device. $EPOCH_MS",
                        "deviceId" to DEVICE_ID,
                        "encryptedBlocksVersion" to
                            "The format of `encryptedBlocks`: `1`, or `null` for a text-only draft. Send it together with " +
                            "`encryptedBlocks` or not at all; anything else answers `400 INVALID_DRAFT_FORMAT`.",
                        "encryptedBlocks" to
                            "The draft's blocks (text, photos, audio, video) as one `LDSE2:` encrypted value, or `null`. " +
                            "$ENCRYPTED_BY_APPS Saving a text-only draft over a draft that has blocks answers " +
                            "`409 DRAFT_FORMAT_UPGRADE_REQUIRED`, so an older app cannot silently drop them.",
                    ),
                ),
            "DraftUploadResponse" to
                SchemaDoc(
                    "Confirmation that a draft was saved.",
                    mapOf(
                        "id" to "The draft's ID.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "uploadedAt" to "When the server accepted the write. $EPOCH_MS",
                    ),
                ),
            "DraftChangesResponse" to
                SchemaDoc(
                    "Drafts changed or deleted since a cursor.",
                    mapOf(
                        "drafts" to "Drafts created or updated after `since`.",
                        "cursor" to "Reserved; currently always absent.",
                        "deletions" to "Drafts deleted after `since`.",
                        "lastTimestamp" to CURSOR_LAST_TIMESTAMP,
                        "hasMore" to HAS_MORE,
                    ),
                ),
            "DraftDeletion" to
                SchemaDoc(
                    "A tombstone: a draft that was deleted, usually because it became an entry.",
                    mapOf(
                        "id" to "The deleted draft's ID.",
                        "deletedAt" to "When it was deleted. $EPOCH_MS",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                    ),
                ),
            "DraftChange" to
                SchemaDoc(
                    "A draft as the server holds it.",
                    mapOf(
                        "id" to "The draft's ID.",
                        "content" to "The draft's text so far.",
                        "blockTypes" to "The kinds of block in the draft, in order.",
                        "journalIds" to "Journals the finished entry will belong to.",
                        "createdAt" to "When the draft was started on the device. $EPOCH_MS",
                        "lastUpdated" to "When it was last edited on the device. $EPOCH_MS",
                        "deviceId" to "Which device last saved it.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "encryptedBlocksVersion" to "The format of `encryptedBlocks`: `1`, or `null` for a text-only draft.",
                        "encryptedBlocks" to "The draft's blocks as one `LDSE2:` encrypted value, or `null`. $ENCRYPTED_BY_APPS",
                        "is_deleted" to "Always `false`; deleted drafts arrive in `deletions` instead.",
                    ),
                ),
            // ---- Moments --------------------------------------------------------------------------
            "MomentUploadRequest" to
                SchemaDoc(
                    "A whole marked moment.",
                    mapOf(
                        "id" to "The moment's ID. Must match the path.",
                        "capturedAt" to "When the moment was marked. $EPOCH_MS",
                        "lastUpdated" to "When it was last edited on the device. $EPOCH_MS",
                        "deviceId" to DEVICE_ID,
                        "payload" to "Everything else about the moment as one `LDSE2:` encrypted value, at most 64 KiB. $ENCRYPTED_BY_APPS",
                        "schema" to "The version of the payload's format. Starts at `1`.",
                    ),
                ),
            "MomentUploadResponse" to
                SchemaDoc(
                    "Confirmation that a moment was saved.",
                    mapOf(
                        "id" to "The moment's ID.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "uploadedAt" to "When the server accepted the write. $EPOCH_MS",
                    ),
                ),
            "MomentUpdateRequest" to
                SchemaDoc(
                    "A new payload for an existing moment.",
                    mapOf(
                        "payload" to "The moment's new encrypted payload. $ENCRYPTED_BY_APPS",
                        "schema" to "The version of the payload's format.",
                        "capturedAt" to "A corrected capture time, or absent to keep the stored one. $EPOCH_MS",
                        "lastUpdated" to "When it was edited on the device. $EPOCH_MS",
                        "deviceId" to DEVICE_ID,
                        "versionConstraint" to "Send `known` with the version you last saw to get `409 CONFLICT` instead of overwriting.",
                    ),
                ),
            "MomentUpdateResponse" to
                SchemaDoc(
                    "Confirmation that a moment was updated.",
                    mapOf(
                        "id" to "The moment's ID.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                        "updatedAt" to "When the server accepted the write. $EPOCH_MS",
                    ),
                ),
            "MomentChange" to
                SchemaDoc(
                    "A marked moment as the server stores it.",
                    mapOf(
                        "id" to "The moment's ID.",
                        "capturedAt" to "When the moment was marked. $EPOCH_MS",
                        "lastUpdated" to "When it was last edited on the device. $EPOCH_MS",
                        "deviceId" to "Which device last saved it.",
                        "payload" to "The encrypted payload. $ENCRYPTED_BY_APPS",
                        "schema" to "The version of the payload's format.",
                        "serverVersion" to SERVER_VERSION_ASSIGNED,
                    ),
                ),
            "MomentDeletion" to
                SchemaDoc(
                    "A tombstone: a moment that was deleted.",
                    mapOf(
                        "id" to "The deleted moment's ID.",
                        "deletedAt" to "When it was deleted. $EPOCH_MS",
                        "serverVersion" to DELETION_VERSION,
                    ),
                ),
            "MomentChangesResponse" to
                SchemaDoc(
                    "Moments changed or deleted since a cursor.",
                    mapOf(
                        "changes" to "Moments created or updated after `since`.",
                        "deletions" to "Moments deleted after `since`.",
                        "lastTimestamp" to CURSOR_LAST_TIMESTAMP,
                        "hasMore" to HAS_MORE,
                    ),
                ),
            // ---- Media ----------------------------------------------------------------------------
            "MediaUploadResponse" to
                SchemaDoc(
                    "Confirmation that a media file was stored.",
                    mapOf(
                        "contentId" to "The entry the file belongs to.",
                        "mediaId" to "The file's ID, derived from the account, entry and file name.",
                        "downloadUrl" to
                            "Where to fetch the bytes: a short-lived signed storage URL or this API's download endpoint. Treat as opaque.",
                        "uploadIntentId" to "The opaque random intent echoed for a receipt-backed upload; null for legacy uploads.",
                        "uploadedAt" to "When the server stored the file. $EPOCH_MS",
                    ),
                ),
            "MediaUploadReceiptResponse" to
                SchemaDoc(
                    "Result of an authenticated upload receipt lookup.",
                    mapOf(
                        "receipt" to
                            "Committed account-owned upload, or null if absent. Never reuse an intent for changed bytes or encryption keys.",
                    ),
                ),
            "MediaMetadataResponse" to
                SchemaDoc(
                    "What the server knows about a media file.",
                    mapOf(
                        "contentId" to "The entry the file belongs to.",
                        "mediaId" to "The file's ID.",
                        "fileName" to "The original file name given at upload.",
                        "mimeType" to "The media type given at upload, such as `image/jpeg`.",
                        "sizeBytes" to "The file's size in bytes.",
                        "downloadUrl" to "Where to fetch the bytes. Treat as opaque; it may be short-lived.",
                        "uploadedAt" to "When the server stored the file. $EPOCH_MS",
                    ),
                ),
            // ---- Backups ----------------------------------------------------------------------------
            "BackupPartUploadStartRequest" to
                SchemaDoc(
                    "Starts a backup sent in parts.",
                    mapOf("sizeBytes" to "The archive's total length in bytes, at most 2 GiB."),
                ),
            "BackupPartUploadStartResponse" to
                SchemaDoc(
                    "How to send a backup in parts.",
                    mapOf(
                        "uploadId" to "The upload's ID, a UUID. Every later call for this upload names it.",
                        "partSizeBytes" to "The length of every part but the last, which holds the rest.",
                        "partCount" to "How many parts to send, numbered from 0.",
                    ),
                ),
            "BackupPartUploadCompleteRequest" to
                SchemaDoc(
                    "Finishes a backup sent in parts once every part has been sent.",
                    mapOf(
                        "deviceId" to "The device the backup came from, 1 to 256 characters.",
                        "manifest" to "The backup's manifest as JSON text, at most 256 KiB.",
                        "sizeBytes" to "The archive's total length in bytes; must match the length given at the start.",
                    ),
                ),
            "BackupUploadResponse" to
                SchemaDoc(
                    "Confirmation that a backup was stored.",
                    mapOf(
                        "id" to "The backup's ID, a UUID.",
                        "createdAt" to "When the server stored it. $EPOCH_MS",
                        "sizeBytes" to "Size of the archive in bytes, as counted against the quota.",
                    ),
                ),
            "BackupInfoResponse" to
                SchemaDoc(
                    "One backup's record.",
                    mapOf(
                        "id" to "The backup's ID, a UUID.",
                        "deviceId" to "Which device made it.",
                        "manifest" to "The JSON manifest given at upload, verbatim.",
                        "createdAt" to "When the server stored it. $EPOCH_MS",
                        "sizeBytes" to "Size of the archive in bytes.",
                        "downloadUrl" to "Where to fetch the archive. Treat as opaque; it may be short-lived.",
                    ),
                ),
            "BackupListResponse" to SchemaDoc("The account's backups.", mapOf("backups" to "One record per backup.")),
        )
}
