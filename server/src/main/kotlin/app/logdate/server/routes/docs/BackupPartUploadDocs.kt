package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.binarySchema
import app.logdate.server.routes.created
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.quotaExceeded
import app.logdate.server.routes.rateLimited
import app.logdate.server.routes.sync.BACKUP_PART_RATE_LIMIT
import app.logdate.server.routes.sync.BACKUP_UPLOAD_RATE_LIMIT
import app.logdate.server.routes.syncError
import app.logdate.shared.model.sync.BackupPartUploadCompleteRequest
import app.logdate.shared.model.sync.BackupPartUploadStartRequest
import app.logdate.shared.model.sync.BackupPartUploadStartResponse
import app.logdate.shared.model.sync.BackupUploadResponse
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode

/** Documentation for `SyncBackupPartRoutes.kt`: a backup sent as numbered parts. */
internal object BackupPartUploadDocs {
    private fun RequestConfig.uploadId() {
        pathParameter<String>("uploadId") {
            description = "The upload's ID from `startBackupPartUpload`. Anything that is not a UUID answers `400 INVALID_ID`."
            example("Example") { value = SyncExamples.BACKUP_UPLOAD_ID }
        }
    }

    private val invalidUploadId = ErrorCase("INVALID_ID", "`uploadId` is not a UUID.", "Invalid uploadId")

    val startBackupPartUpload: RouteConfig.() -> Unit = {
        bearerOperation(
            "startBackupPartUpload",
            ApiTags.BACKUPS,
            "Start a backup sent in parts",
            """
            Starts a backup that is sent as numbered parts instead of one `multipart/form-data` body, for when a
            proxy in front of the server refuses large requests. Cloudflare's free plan, for example, refuses
            bodies over 100 MB.

            The server checks the quota for `sizeBytes` and returns an `uploadId`, the part size, and how many
            parts to send. Nothing is stored yet. Send each part with `uploadBackupPart`, then finish with
            `completeBackupPartUpload`. Starting counts against the same per-account limit as `uploadBackup`.
            """,
        )
        request {
            jsonBody(BackupPartUploadStartRequest(sizeBytes = 148_318_382), "The archive's total length in bytes, at most 2 GiB.")
        }
        response {
            ok(
                "Send the parts. Nothing is stored until they arrive.",
                BackupPartUploadStartResponse(SyncExamples.BACKUP_UPLOAD_ID, 33_554_432, 5),
            )
            syncError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "The body is not JSON or `sizeBytes` is out of range.",
                    "sizeBytes must be between 1 and 2147483648",
                ),
            )
            syncUnauthorized()
            quotaExceeded()
            rateLimited(BACKUP_UPLOAD_RATE_LIMIT, ErrorEnvelope.SYNC)
            syncError(HttpStatusCode.ServiceUnavailable, SyncStorageDocs.backupStorageUnavailable)
        }
    }

    val uploadBackupPart: RouteConfig.() -> Unit = {
        bearerOperation(
            "uploadBackupPart",
            ApiTags.BACKUPS,
            "Send one part of a backup",
            """
            Sends part `index` (counting from 0) of an upload as the raw request body. Every part except the last
            is exactly `partSizeBytes` long; the last holds the rest. `Content-Length` is required and must match
            the body. Parts may be sent in any order, and sending a part again replaces it, so a client retries a
            failed part by sending just that part.
            """,
        )
        request {
            uploadId()
            pathParameter<Int>("index") {
                description = "The part's number, from 0."
                example("First") { value = 0 }
            }
            body(binarySchema()) {
                required = true
                mediaTypes(ContentType.Application.OctetStream)
            }
        }
        response {
            noContent("The part is stored.")
            syncError(
                HttpStatusCode.BadRequest,
                invalidUploadId,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "`index` or `Content-Length` is out of range, or the body's length differs from `Content-Length`.",
                    "The body must be exactly as long as its Content-Length",
                ),
            )
            syncUnauthorized()
            syncError(
                HttpStatusCode.RequestTimeout,
                ErrorCase(
                    "UPLOAD_STALLED",
                    "The client sent nothing for too long. Send the part again.",
                    "The upload sent nothing for too long; send it again",
                ),
            )
            rateLimited(BACKUP_PART_RATE_LIMIT, ErrorEnvelope.SYNC)
            syncError(HttpStatusCode.ServiceUnavailable, SyncStorageDocs.backupStorageUnavailable)
        }
    }

    val completeBackupPartUpload: RouteConfig.() -> Unit = {
        bearerOperation(
            "completeBackupPartUpload",
            ApiTags.BACKUPS,
            "Finish a backup sent in parts",
            """
            Joins the parts in order and stores the result exactly as `uploadBackup` stores a backup sent in one
            request, then deletes the parts. `sizeBytes` must equal the length given when the upload started.
            A part that is missing or the wrong length answers `409 BACKUP_PART_MISSING` naming it; send that part
            again and complete again.
            """,
        )
        request {
            uploadId()
            jsonBody(
                BackupPartUploadCompleteRequest(
                    deviceId = DocExamples.DEVICE_ID,
                    manifest = SyncStorageDocs.BACKUP_MANIFEST,
                    sizeBytes = 148_318_382,
                ),
                "The fields `uploadBackup` takes as text parts, and the archive's total length.",
            )
        }
        response {
            created(
                "The backup is stored.",
                BackupUploadResponse(id = SyncExamples.BACKUP_ID, createdAt = SyncExamples.SERVER_VERSION, sizeBytes = 148_318_382),
                "/api/v1/backups/${SyncExamples.BACKUP_ID}",
            )
            syncError(
                HttpStatusCode.BadRequest,
                invalidUploadId,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "A field is missing or out of range. The `message` names it.",
                    "deviceId must be 1 to 256 characters",
                ),
            )
            syncUnauthorized()
            syncError(
                HttpStatusCode.Conflict,
                ErrorCase(
                    "BACKUP_PART_MISSING",
                    "A part has not arrived or has the wrong length. Send it again, then complete again.",
                    "Part 3 has not arrived or has the wrong length; send it again",
                ),
            )
            quotaExceeded()
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "BACKUP_ENCRYPT_FAILED",
                    "The server could not encrypt the archive. Retry; report it if it persists.",
                    "Failed to encrypt backup",
                ),
                SyncExamples.serverMisconfigured,
            )
            syncError(HttpStatusCode.ServiceUnavailable, SyncStorageDocs.backupStorageUnavailable)
        }
    }

    val abandonBackupPartUpload: RouteConfig.() -> Unit = {
        bearerOperation(
            "abandonBackupPartUpload",
            ApiTags.BACKUPS,
            "Abandon a backup sent in parts",
            "Deletes every part of an upload the client will not complete. Deleting an upload with no parts succeeds.",
        )
        request { uploadId() }
        response {
            noContent("The upload's parts are gone.")
            syncError(HttpStatusCode.BadRequest, invalidUploadId)
            syncUnauthorized()
            syncError(HttpStatusCode.ServiceUnavailable, SyncStorageDocs.backupStorageUnavailable)
        }
    }
}
