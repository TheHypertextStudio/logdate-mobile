package app.logdate.client.sync.cloud

import app.logdate.shared.model.sync.BackupPartUploadCompleteRequest
import app.logdate.shared.model.sync.BackupPartUploadStartRequest
import app.logdate.shared.model.sync.BackupPartUploadStartResponse
import app.logdate.shared.model.sync.BackupUploadResponse
import io.github.aakira.napier.Napier
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

/**
 * Archives larger than this go up in parts. A proxy in front of the server may refuse request bodies
 * over 100 MB (Cloudflare's free plan does), and the server's parts are 32 MiB, so anything that would
 * need more than one part is split.
 */
internal const val PARTED_BACKUP_THRESHOLD_BYTES: Long = 32L * 1024 * 1024

private const val PART_ATTEMPTS = 3
private const val PART_COPY_BYTES = 64 * 1024

/** The server answered that it has no parted uploads, so the caller sends the archive in one request. */
internal class PartedBackupUploadUnsupported : Exception("The server does not accept backups in parts")

/**
 * Sends [backup] as numbered parts: starts an upload, sends each part (retrying a failed part on its
 * own), then completes it. Fails with [PartedBackupUploadUnsupported] when the server predates parted
 * uploads. An upload that fails after starting is abandoned so its parts don't linger on the server.
 */
internal suspend fun LogDateCloudApiClient.requestUploadBackupInParts(
    accessToken: String,
    backup: BackupUploadFileRequest,
): Result<BackupUploadResponse> {
    val baseUrl = getBaseUrl(accessToken)
    val startResponse =
        transport.post("$baseUrl/backups/uploads") {
            headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(BackupPartUploadStartRequest(sizeBytes = backup.sizeBytes))
        }
    if (startResponse.status == HttpStatusCode.NotFound || startResponse.status == HttpStatusCode.MethodNotAllowed) {
        return Result.failure(PartedBackupUploadUnsupported())
    }
    if (startResponse.status != HttpStatusCode.OK) return handleApiError(startResponse)
    val upload = startResponse.body<BackupPartUploadStartResponse>()

    var completed = false
    try {
        for (index in 0 until upload.partCount) {
            val offset = index * upload.partSizeBytes
            val length = minOf(upload.partSizeBytes, backup.sizeBytes - offset)
            sendPartWithRetries(baseUrl, accessToken, upload.uploadId, index, backup.sourcePath, offset, length)
                .onFailure { return Result.failure(it) }
        }
        val completeResponse =
            transport.post("$baseUrl/backups/uploads/${upload.uploadId}/complete") {
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(BackupPartUploadCompleteRequest(backup.deviceId, backup.manifest, backup.sizeBytes))
            }
        if (completeResponse.status != HttpStatusCode.Created) return handleApiError(completeResponse)
        completed = true
        return Result.success(completeResponse.body())
    } finally {
        if (!completed) {
            withContext(NonCancellable) {
                runCatching {
                    transport.delete("$baseUrl/backups/uploads/${upload.uploadId}") {
                        headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                    }
                }.onFailure { Napier.w("Could not abandon a backup upload's parts") }
            }
        }
    }
}

private suspend fun LogDateCloudApiClient.sendPartWithRetries(
    baseUrl: String,
    accessToken: String,
    uploadId: String,
    index: Int,
    source: Path,
    offset: Long,
    length: Long,
): Result<Unit> {
    var lastFailure: Throwable? = null
    repeat(PART_ATTEMPTS) { attempt ->
        if (attempt > 0) delay((2 * attempt).seconds)
        val outcome =
            try {
                val response =
                    transport.put("$baseUrl/backups/uploads/$uploadId/parts/$index") {
                        headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                        setBody(FileRangeContent(source, offset, length))
                    }
                when {
                    response.status == HttpStatusCode.NoContent -> return Result.success(Unit)
                    response.status.isRetryablePartFailure() -> handleApiError<Unit>(response)
                    else -> return handleApiError(response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(CloudApiException("NETWORK_ERROR", "Backup part upload failed", cause = error))
            }
        lastFailure = outcome.exceptionOrNull()
    }
    return Result.failure(lastFailure ?: CloudApiException("NETWORK_ERROR", "Backup part upload failed"))
}

private fun HttpStatusCode.isRetryablePartFailure(): Boolean =
    this == HttpStatusCode.RequestTimeout || this == HttpStatusCode.TooManyRequests || value >= 500

/** Streams [length] bytes of [path] from [offset] without holding the part in memory. */
private class FileRangeContent(
    private val path: Path,
    private val offset: Long,
    private val length: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long = length
    override val contentType: ContentType = ContentType.Application.OctetStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        SystemFileSystem.source(path).buffered().use { source ->
            source.skip(offset)
            val buffer = ByteArray(PART_COPY_BYTES)
            var remaining = length
            while (remaining > 0) {
                val read = source.readAtMostTo(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "The backup file ended before the part did" }
                channel.writeFully(buffer, 0, read)
                remaining -= read
            }
        }
    }
}
