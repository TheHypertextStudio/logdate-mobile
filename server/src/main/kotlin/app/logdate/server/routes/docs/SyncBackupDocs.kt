package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.binarySchema
import app.logdate.server.routes.created
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.quotaExceeded
import app.logdate.server.routes.rateLimited
import app.logdate.server.routes.sync.BACKUP_UPLOAD_RATE_LIMIT
import app.logdate.server.routes.syncError
import app.logdate.shared.model.sync.BackupInfoResponse
import app.logdate.shared.model.sync.BackupListResponse
import app.logdate.shared.model.sync.BackupUploadResponse
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode

/** Encrypted backup upload, download and removal API contracts. */
internal object SyncBackupDocs {
    private const val BACKUP_DOWNLOAD_URL = "https://cloud.logdate.app/api/v1/backups/${SyncExamples.BACKUP_ID}/binary"
    private const val BACKUP_MANIFEST = """{"format":"logdate-cloud-backup","encryption":"identity-aes-gcm-chunked-v2"}"""
    private val backupInfo =
        BackupInfoResponse(
            id = SyncExamples.BACKUP_ID,
            deviceId = DocExamples.DEVICE_ID,
            manifest = BACKUP_MANIFEST,
            createdAt = SyncExamples.SERVER_VERSION,
            sizeBytes = 48_318_382,
            downloadUrl = BACKUP_DOWNLOAD_URL,
        )

    private val backupStorageUnavailable =
        ErrorCase(
            "BACKUP_STORAGE_UNAVAILABLE",
            "This deployment has no blob storage configured, so backups cannot be stored or read. The operator must configure storage.",
            "Backup storage not configured",
        )

    private fun RequestConfig.backupId() {
        pathParameter<String>("backupId") {
            description = "The backup's ID, a UUID assigned at upload. Anything that is not a UUID answers `400 INVALID_ID`."
            example("Example") { value = SyncExamples.BACKUP_ID }
        }
    }

    private val invalidBackupId = ErrorCase("INVALID_ID", "`backupId` is not a UUID.", "Invalid backupId")

    // ---- Backups ------------------------------------------------------------------------------

    val uploadBackup: RouteConfig.() -> Unit = {
        bearerOperation(
            "uploadBackup",
            ApiTags.BACKUPS,
            "Upload a backup",
            """
            Stores an encrypted snapshot of a device's data. The request is `multipart/form-data` with three
            parts, all required:

            | Part | Type | Meaning |
            |---|---|---|
            | `deviceId` | text | Which device made the backup. |
            | `manifest` | text | JSON metadata stored verbatim and returned with the listing. Identity-encrypted archives expose only format and encryption version; older backups may include counts and app version. |
            | `data` | file | The backup archive. Must not be empty. |

            The app encrypts the archive before upload; the server encrypts it again at rest. Every upload
            creates a new backup with a random ID (retries are not merged), so a client should not resend a
            backup whose response it merely lost. The size counted against the quota is the length of `data`.

            > [!NOTE]
            > Limited to {{backup.upload}} per account, and counted against both the storage quota and the plan's
            > backup-count limit.
            """,
        )
        request {
            multipartBody {
                description = "The archive and the fields that describe it. See the table above."
                mediaTypes(ContentType.MultiPart.FormData)
                part<String>("deviceId") { required = true }
                part<String>("manifest") { required = true }
                part("data", binarySchema()) {
                    required = true
                    mediaTypes(ContentType.Application.OctetStream)
                }
            }
        }
        response {
            created(
                "The backup is stored.",
                BackupUploadResponse(id = SyncExamples.BACKUP_ID, createdAt = SyncExamples.SERVER_VERSION, sizeBytes = 48_318_382),
                "/api/v1/backups/${SyncExamples.BACKUP_ID}",
            )
            syncError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "The body is not multipart, a part is missing, or `data` is empty. The `message` names the problem.",
                    "Backup payload must not be empty",
                ),
            )
            syncUnauthorized()
            quotaExceeded()
            rateLimited(BACKUP_UPLOAD_RATE_LIMIT, ErrorEnvelope.SYNC)
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "BACKUP_ENCRYPT_FAILED",
                    "The server could not encrypt the archive. Retry; report it if it persists.",
                    "Failed to encrypt backup",
                ),
                ErrorCase(
                    "BACKUP_METADATA_WRITE_FAILED",
                    "Metadata completion was not confirmed. Possibly committed archives are retained; " +
                        "check the backup list before uploading again.",
                    "Failed to store backup metadata",
                ),
                SyncExamples.serverMisconfigured,
            )
            syncError(HttpStatusCode.ServiceUnavailable, backupStorageUnavailable)
        }
    }

    val listBackups: RouteConfig.() -> Unit = {
        bearerOperation(
            "listBackups",
            ApiTags.BACKUPS,
            "List backups",
            """
            Lists every backup the account has, with the manifest each was uploaded with and a `downloadUrl`.
            This is what a restore screen shows so the person can choose which device and which date to restore
            from.
            """,
        )
        response {
            ok("The account's backups.", BackupListResponse(backups = listOf(backupInfo)))
            syncUnauthorized()
            syncServerError()
        }
    }

    val getBackup: RouteConfig.() -> Unit = {
        bearerOperation(
            "getBackup",
            ApiTags.BACKUPS,
            "Get backup details",
            "Returns one backup's record: device, manifest, size, creation time and a fresh `downloadUrl`.",
        )
        request { backupId() }
        response {
            ok("The backup's details.", backupInfo)
            syncError(HttpStatusCode.BadRequest, invalidBackupId)
            syncUnauthorized()
            syncError(
                HttpStatusCode.NotFound,
                ErrorCase("NOT_FOUND", "No backup with that ID belongs to this account.", "Backup not found"),
            )
            syncServerError()
        }
    }

    val downloadBackup: RouteConfig.() -> Unit = {
        bearerOperation(
            "downloadBackup",
            ApiTags.BACKUPS,
            "Download a backup",
            """
            Streams the backup archive as `application/octet-stream`, with the server's at-rest encryption
            removed. The result is exactly what the app uploaded, which the app still has to decrypt with its
            own key.
            """,
        )
        request { backupId() }
        response {
            code(HttpStatusCode.OK) {
                description = "The archive's bytes."
                body(binarySchema()) { mediaTypes(ContentType.Application.OctetStream) }
            }
            syncError(HttpStatusCode.BadRequest, invalidBackupId)
            syncUnauthorized()
            syncError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "NOT_FOUND",
                    "No backup with that ID belongs to this account, or its archive is missing from storage.",
                    "Backup not found",
                ),
            )
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "BACKUP_DECRYPT_FAILED",
                    "The stored archive could not be decrypted. Report it; retrying will not help.",
                    "Failed to decrypt backup",
                ),
                SyncExamples.serverMisconfigured,
            )
            syncError(HttpStatusCode.ServiceUnavailable, backupStorageUnavailable)
        }
    }

    val deleteBackup: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteBackup",
            ApiTags.BACKUPS,
            "Delete a backup",
            """
            Removes a backup and its archive, releasing its bytes and its slot in the backup-count limit. Deleting
            a backup that does not exist still answers `204`.
            """,
        )
        request { backupId() }
        response {
            noContent("The backup is gone (or already was).")
            syncError(HttpStatusCode.BadRequest, invalidBackupId)
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
            syncError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "BACKUP_DELETE_FAILED",
                    "Blob storage refused to delete the archive; the record was kept. Retry.",
                    "Failed to delete backup blob",
                ),
                SyncExamples.serverMisconfigured,
            )
            syncError(HttpStatusCode.ServiceUnavailable, backupStorageUnavailable)
        }
    }
}
