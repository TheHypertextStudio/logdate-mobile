package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.responses.SimpleSuccessResponse
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.binarySchema
import app.logdate.server.routes.created
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.quotaExceeded
import app.logdate.server.routes.rateLimited
import app.logdate.server.routes.sync.MEDIA_UPLOAD_RATE_LIMIT
import app.logdate.server.routes.sync.SyncStatusSnapshot
import app.logdate.server.routes.syncError
import app.logdate.shared.model.sync.MediaMetadataResponse
import app.logdate.shared.model.sync.MediaUploadReceiptResponse
import app.logdate.shared.model.sync.MediaUploadResponse
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode

/** Documentation for `SyncMediaRoutes.kt`, `SyncBackupRoutes.kt` and `SyncStatusRoutes.kt`. */
internal object SyncStorageDocs {
    private const val MEDIA_DOWNLOAD_URL = "https://cloud.logdate.app/api/v1/media/${SyncExamples.MEDIA_ID}/binary"

    private val mediaMetadata =
        MediaMetadataResponse(
            contentId = SyncExamples.CONTENT_ID,
            mediaId = SyncExamples.MEDIA_ID,
            fileName = "IMG_20260912_071455.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2_483_113,
            downloadUrl = MEDIA_DOWNLOAD_URL,
            uploadedAt = SyncExamples.SERVER_VERSION,
        )

    private val storageUnavailable =
        ErrorCase(
            "MEDIA_STORAGE_UNAVAILABLE",
            "This deployment has no blob storage configured, so files stored externally cannot be reached. The operator must configure storage.",
            "Media storage not configured",
        )

    private fun RequestConfig.mediaId() {
        pathParameter<String>("mediaId") {
            description = "The opaque media ID assigned at upload, derived from an owner-scoped intent or the legacy entry and file name."
            example("Example") { value = SyncExamples.MEDIA_ID }
        }
    }

    // ---- Media -------------------------------------------------------------------------------

    val uploadMedia: RouteConfig.() -> Unit = {
        bearerOperation(
            "uploadMedia",
            ApiTags.MEDIA,
            "Upload a media file",
            """
            Stores a photo, video or audio file for an entry. The request is `multipart/form-data` with these
            parts, all required:

            | Part | Type | Meaning |
            |---|---|---|
            | `contentId` | text | ID of the entry the file belongs to. |
            | `fileName` | text | Original file name, used for the download's name and to derive the media ID. |
            | `mimeType` | text | The file's media type, such as `image/jpeg`. Served back on download. |
            | `sizeBytes` | text | Byte length of `data`, as a decimal number. Must match exactly. |
            | `deviceId` | text | Which device is uploading. |
            | `data` | file | The bytes. |

            `sizeBytes` must equal the actual length of `data` so that a truncated upload is rejected instead of
            stored half-finished. The media ID is derived from the account, `contentId` and `fileName`, so
            retrying an interrupted upload replaces the same file rather than creating a duplicate; upload a
            different file under the same name to replace it deliberately.

            The server encrypts the bytes at rest. `downloadUrl` in the response is either a short-lived signed
            URL into blob storage or this API's own **Download a media file** endpoint, depending on how the
            deployment is configured; treat it as opaque and fetch it with the same bearer token.

            > [!NOTE]
            > Limited to {{media.upload}} per account, and counted against the storage quota.
            """,
        )
        request {
            headerParameter<String>("X-LogDate-Upload-Intent") {
                description = "Optional random UUID version 4. Creates a write-once receipt, scoped to the authenticated account. " +
                    "Reuse only for the exact content and encryption version; check its receipt before replaying bytes. " +
                    "Requests without this header keep legacy replacement behavior."
                required = false
            }

            multipartBody {
                description = "The file and the fields that describe it. See the table above."
                mediaTypes(ContentType.MultiPart.FormData)
                part<String>("contentId") { required = true }
                part<String>("fileName") { required = true }
                part<String>("mimeType") { required = true }
                part<String>("sizeBytes") { required = true }
                part<String>("deviceId") { required = true }
                part("data", binarySchema()) {
                    required = true
                    mediaTypes(ContentType.Application.OctetStream)
                }
            }
        }
        response {
            created(
                "The file is stored (or replaced).",
                MediaUploadResponse(
                    contentId = SyncExamples.CONTENT_ID,
                    mediaId = SyncExamples.MEDIA_ID,
                    downloadUrl = MEDIA_DOWNLOAD_URL,
                    uploadedAt = SyncExamples.SERVER_VERSION,
                ),
                "/api/v1/media/${SyncExamples.MEDIA_ID}",
            )
            syncError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "The body is not multipart, a part is missing, `sizeBytes` is not a positive number, or `sizeBytes` " +
                        "does not equal the length of `data`. The `message` names the problem.",
                    "Missing required multipart field: sizeBytes",
                ),
            )
            syncUnauthorized()
            syncStorageModeMismatch()
            syncMigrationHold()
            quotaExceeded()
            rateLimited(MEDIA_UPLOAD_RATE_LIMIT, ErrorEnvelope.SYNC)
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "MEDIA_ENCRYPT_FAILED",
                    "The server could not encrypt the file. Retry; report it if it persists.",
                    "Failed to encrypt media payload",
                ),
                ErrorCase("MEDIA_UPLOAD_FAILED", "Blob storage rejected the write. Retry with backoff.", "Failed to store media"),
                ErrorCase(
                    "MEDIA_METADATA_WRITE_FAILED",
                    "Metadata completion was not confirmed. Possibly committed bytes are retained; check the upload receipt before retrying.",
                    "Failed to store media metadata",
                ),
                SyncExamples.serverMisconfigured,
            )
        }
    }

    val getMediaUploadReceipt: RouteConfig.() -> Unit = {
        bearerOperation(
            "getMediaUploadReceipt",
            ApiTags.MEDIA,
            "Check an upload receipt",
            "Returns a committed receipt for this account and upload intent, or null when none exists. " +
                "Use before retrying a lost acknowledgement. Responses must not be cached. " +
                "The client must bind the random intent to its owner, server, content bytes and encryption context locally.",
        )
        request {
            pathParameter<String>("intent") {
                description = "Lowercase random UUID version 4 generated and durably stored before the upload."
                example("Intent") { value = "b88ce7fe-902c-4c01-862d-45238bf30928" }
            }
        }
        response {
            ok("Committed receipt, or null when no matching account-owned upload exists.", MediaUploadReceiptResponse(null))
            syncUnauthorized()
            syncStorageModeMismatch()
            syncError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "INVALID_UPLOAD_INTENT",
                    "Use a lowercase random UUID version 4.",
                    "Invalid upload identity",
                ),
            )
            syncServerError()
        }
    }

    val getMediaMetadata: RouteConfig.() -> Unit = {
        bearerOperation(
            "getMediaMetadata",
            ApiTags.MEDIA,
            "Get media details",
            """
            Returns what the server knows about a media file: which entry it belongs to, its name, type and
            size, and a fresh `downloadUrl`. Use it to render an attachment list without downloading anything.
            """,
        )
        request { mediaId() }
        response {
            ok("The file's details.", mediaMetadata)
            syncUnauthorized()
            syncStorageModeMismatch()
            syncError(HttpStatusCode.NotFound, ErrorCase("NOT_FOUND", "No media with that ID belongs to this account.", "Media not found"))
            syncServerError()
        }
    }

    val downloadMedia: RouteConfig.() -> Unit = {
        bearerOperation(
            "downloadMedia",
            ApiTags.MEDIA,
            "Download a media file",
            """
            Streams the file's bytes, decrypted, with `Content-Type` set to the `mimeType` given at upload (or
            `application/octet-stream` if that value cannot be parsed). This is the endpoint `downloadUrl`
            points at when the deployment does not use signed storage URLs.
            """,
        )
        request { mediaId() }
        response {
            code(HttpStatusCode.OK) {
                description = "The file's bytes. `Content-Type` is the media type given at upload."
                body(binarySchema()) { mediaTypes(ContentType.Application.OctetStream) }
            }
            syncUnauthorized()
            syncStorageModeMismatch()
            syncError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "NOT_FOUND",
                    "No media with that ID belongs to this account, or its bytes are missing from storage.",
                    "Media not found",
                ),
            )
            syncError(
                HttpStatusCode.InternalServerError,
                storageUnavailable,
                ErrorCase(
                    "MEDIA_DECRYPT_FAILED",
                    "The stored bytes could not be decrypted. Report it; retrying will not help.",
                    "Failed to decrypt media payload",
                ),
                SyncExamples.serverMisconfigured,
            )
        }
    }

    val deleteMedia: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteMedia",
            ApiTags.MEDIA,
            "Delete a media file",
            """
            Removes a media file and its record, releasing its bytes from the storage quota. Deleting a file that
            does not exist still answers `204`, so retries are safe. The entry the file belonged to is not
            changed; update its `mediaUri` yourself.
            """,
        )
        request { mediaId() }
        response {
            noContent("The file is gone (or already was).")
            syncUnauthorized()
            syncError(
                HttpStatusCode.Conflict,
                ErrorCase(
                    "IDENTITY_WRITE_HELD",
                    "An identity change has paused writes; no object was deleted.",
                    "Sync is paused while your account changes.",
                ),
                ErrorCase(
                    "IDENTITY_CHANGED",
                    "The request belongs to an earlier identity epoch; no object was deleted.",
                    "Refresh your account before syncing.",
                ),
            )
            syncStorageModeMismatch()
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "MEDIA_DELETE_FAILED",
                    "Blob storage refused to delete the bytes; the record was kept so nothing is orphaned. Retry.",
                    "Failed to delete media blob",
                ),
                SyncExamples.serverMisconfigured,
            )
            syncError(HttpStatusCode.ServiceUnavailable, storageUnavailable, SyncExamples.migrationInProgress)
        }
    }

    // ---- Backups ------------------------------------------------------------------------------

    val uploadBackup: RouteConfig.() -> Unit = SyncBackupDocs.uploadBackup
    val listBackups: RouteConfig.() -> Unit = SyncBackupDocs.listBackups
    val getBackup: RouteConfig.() -> Unit = SyncBackupDocs.getBackup
    val downloadBackup: RouteConfig.() -> Unit = SyncBackupDocs.downloadBackup
    val deleteBackup: RouteConfig.() -> Unit = SyncBackupDocs.deleteBackup

    // ---- Status -------------------------------------------------------------------------------

    val getSyncStatus: RouteConfig.() -> Unit = {
        bearerOperation(
            "getSyncStatus",
            ApiTags.SYNC_STATUS,
            "Get sync status",
            """
            Returns how many entries, journals and links the server holds for the account and the newest
            version it has assigned. Compare `lastTimestamp` with the cursor you have stored: if the server's is
            higher, there is something to fetch. The counts exclude tombstones. An account that has never synced
            anything has no version yet and gets the current time as `lastTimestamp`, so check the counts too.

            The payload is wrapped in the generic `{ "data", "message", "timestamp" }` success envelope.
            """,
        )
        response {
            ok(
                "The account's sync totals.",
                SimpleSuccessResponse(
                    data =
                        SyncStatusSnapshot(
                            contentCount = 412,
                            journalCount = 6,
                            associationCount = 431,
                            lastTimestamp = SyncExamples.SERVER_VERSION,
                        ),
                    timestamp = DocExamples.instantNow,
                ),
            )
            syncUnauthorized()
            syncServerError()
        }
    }
}
