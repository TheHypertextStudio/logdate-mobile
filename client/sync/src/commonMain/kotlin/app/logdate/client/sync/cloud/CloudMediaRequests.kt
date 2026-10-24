package app.logdate.client.sync.cloud

import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

internal suspend fun LogDateCloudApiClient.requestUploadMedia(
    accessToken: String,
    media: MediaUpload,
): Result<MediaUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.post("$baseUrl/media") {
                headers.append("Authorization", "Bearer $accessToken")
                media.uploadIntentId?.let { headers.append("X-LogDate-Upload-Intent", it) }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("contentId", media.contentId)
                            append("fileName", media.fileName)
                            append("mimeType", media.mimeType)
                            append("sizeBytes", media.sizeBytes.toString())
                            append("deviceId", media.deviceId.value)
                            // Read from disk as the request is written, so the heap never holds
                            // the whole file.
                            append(
                                key = "data",
                                value = InputProvider(media.sizeBytes) { media.openBody().buffered() },
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"${media.fileName}\"")
                                        append(HttpHeaders.ContentType, media.mimeType)
                                    },
                            )
                        },
                    ),
                )
            }

        when (response.status) {
            HttpStatusCode.Created -> {
                val responseBody = response.body<MediaUploadResponse>()
                Result.success(responseBody)
            }

            else -> {
                handleApiError(response)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload media")
        // The body is read from disk while the request is written, so a failure there reaches
        // this catch too. It is the media's fault, not the network's, and retrying it as a
        // network error would hide that.
        val mediaFailure = e.mediaReadFailure()
        Result.failure(
            mediaFailure ?: CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to upload media",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestDownloadMedia(
    accessToken: String,
    mediaId: String,
): Result<MediaDownloadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val metadataResponse =
            transport.get("$baseUrl/media/$mediaId") {
                headers.append("Authorization", "Bearer $accessToken")
            }

        when (metadataResponse.status) {
            HttpStatusCode.OK -> {
                val metadata = metadataResponse.body<MediaMetadataResponse>()
                val binaryResponse =
                    transport.get("$baseUrl/media/$mediaId/binary") {
                        headers.append("Authorization", "Bearer $accessToken")
                    }
                when (binaryResponse.status) {
                    HttpStatusCode.OK -> {
                        val bytes = binaryResponse.body<ByteArray>()
                        require(metadata.sizeBytes >= 0 && bytes.size.toLong() == metadata.sizeBytes) { "Media length mismatch" }
                        Result.success(
                            MediaDownloadResponse(
                                contentId = metadata.contentId,
                                fileName = metadata.fileName,
                                mimeType = metadata.mimeType,
                                sizeBytes = metadata.sizeBytes,
                                data = bytes,
                                downloadUrl = metadata.downloadUrl,
                            ),
                        )
                    }

                    else -> {
                        handleApiError(binaryResponse)
                    }
                }
            }

            else -> {
                handleApiError(metadataResponse)
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to download media")
        Result.failure(
            CloudApiException(
                errorCode = "NETWORK_ERROR",
                message = "Failed to download media",
            ),
        )
    }

internal suspend fun LogDateCloudApiClient.requestUploadBackupFile(
    accessToken: String,
    backup: BackupUploadFileRequest,
): Result<BackupUploadResponse> =
    try {
        val response =
            transport.post("${getBaseUrl(accessToken)}/backups") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("deviceId", backup.deviceId)
                            append("manifest", backup.manifest)
                            append(
                                "data",
                                InputProvider(backup.sizeBytes) { SystemFileSystem.source(backup.sourcePath).buffered() },
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"backup.bin\"")
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                },
                            )
                        },
                    ),
                )
            }
        if (response.status == HttpStatusCode.Created) Result.success(response.body()) else handleApiError(response)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(CloudApiException("NETWORK_ERROR", "Backup upload failed", cause = error))
    }

internal suspend fun LogDateCloudApiClient.requestDownloadBackupToFile(
    accessToken: String,
    backupId: String,
    destination: Path,
): Result<BackupInfoResponse> {
    var complete = false
    try {
        val baseUrl = getBaseUrl(accessToken)
        val metadataResponse =
            transport.get("$baseUrl/backups/$backupId") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        if (metadataResponse.status != HttpStatusCode.OK) return handleApiError(metadataResponse)
        val metadata = metadataResponse.body<BackupInfoResponse>()
        require(metadata.sizeBytes >= 0)
        return transport.streamGet("$baseUrl/backups/$backupId/binary", {
            headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
        }) { response ->
            if (response.status != HttpStatusCode.OK) return@streamGet handleApiError<BackupInfoResponse>(response)
            var transferred = 0L
            SystemFileSystem.sink(destination).buffered().use { sink ->
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val bytes = channel.readRemaining(64 * 1024L).readByteArray()
                    transferred += bytes.size
                    require(transferred <= metadata.sizeBytes) { "Backup length mismatch" }
                    sink.write(bytes)
                }
            }
            require(transferred == metadata.sizeBytes) { "Backup length mismatch" }
            complete = true
            Result.success(metadata)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return Result.failure(CloudApiException("NETWORK_ERROR", "Backup download failed", cause = error))
    } finally {
        if (!complete) runCatching { SystemFileSystem.delete(destination) }
    }
}

internal suspend fun LogDateCloudApiClient.requestUploadBackup(
    accessToken: String,
    backup: BackupUploadRequest,
): Result<BackupUploadResponse> =
    try {
        val baseUrl = getBaseUrl(accessToken)
        val response =
            transport.post("$baseUrl/backups") {
                headers.append("Authorization", "Bearer $accessToken")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("deviceId", backup.deviceId)
                            append("manifest", backup.manifest)
                            append(
                                key = "data",
                                value = backup.data,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"backup.bin\"")
                                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                    },
                            )
                        },
                    ),
                )
            }

        when (response.status) {
            HttpStatusCode.Created -> Result.success(response.body<BackupUploadResponse>())
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to upload backup")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to upload backup", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestListBackups(accessToken: String): Result<BackupListResponse> =
    try {
        val response =
            transport.get("${getBaseUrl(accessToken)}/backups") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        when (response.status) {
            HttpStatusCode.OK -> Result.success(response.body<BackupListResponse>())
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to list backups")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to list backups", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestGetBackup(
    accessToken: String,
    backupId: String,
): Result<BackupInfoResponse> =
    try {
        val response =
            transport.get("${getBaseUrl(accessToken)}/backups/$backupId") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        when (response.status) {
            HttpStatusCode.OK -> Result.success(response.body<BackupInfoResponse>())
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to get backup metadata")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to get backup metadata", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestDownloadBackup(
    accessToken: String,
    backupId: String,
): Result<BackupDownloadResponse> =
    try {
        // Resolve the backend once so a user changing server settings cannot combine metadata
        // from one server with bytes from another during this two-request operation.
        val baseUrl = getBaseUrl(accessToken)
        val metadataResponse =
            transport.get("$baseUrl/backups/$backupId") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        if (metadataResponse.status != HttpStatusCode.OK) {
            return handleApiError(metadataResponse)
        }
        val metadata = metadataResponse.body<BackupInfoResponse>()
        val binaryResponse =
            transport.get("$baseUrl/backups/$backupId/binary") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        when (binaryResponse.status) {
            HttpStatusCode.OK -> Result.success(BackupDownloadResponse(metadata, binaryResponse.body<ByteArray>()))
            else -> handleApiError(binaryResponse)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to download backup")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to download backup", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestDeleteBackup(
    accessToken: String,
    backupId: String,
): Result<Unit> =
    try {
        val response =
            transport.delete("${getBaseUrl(accessToken)}/backups/$backupId") {
                headers.append("Authorization", "Bearer $accessToken")
            }
        when (response.status) {
            HttpStatusCode.NoContent -> Result.success(Unit)
            else -> handleApiError(response)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Napier.e("Failed to delete backup")
        Result.failure(CloudApiException("NETWORK_ERROR", "Failed to delete backup", cause = e))
    }

internal suspend fun LogDateCloudApiClient.requestMediaUploadReceipt(
    accessToken: String,
    intent: String,
): Result<MediaUploadResponse?> =
    try {
        val response =
            transport.get("${getBaseUrl(accessToken)}/media/receipts/$intent") {
                headers.append("Authorization", "Bearer $accessToken")
                headers.append(HttpHeaders.CacheControl, "no-store")
            }
        when (response.status) {
            HttpStatusCode.OK -> Result.success(response.body<app.logdate.shared.model.sync.MediaUploadReceiptResponse>().receipt)
            HttpStatusCode.NotFound -> Result.failure(UnsupportedOperationException("Server does not support upload receipts"))
            else -> handleApiError(response)
        }
    } catch (cause: Exception) {
        if (cause is CancellationException) throw cause
        Result.failure(cause)
    }
