package app.logdate.server.routes.sync

import app.logdate.server.audit.AuditCategory
import app.logdate.server.audit.AuditLogger
import app.logdate.server.auth.AccessCheck
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.auth.TokenScope
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.bearerToken
import app.logdate.server.entitlements.QuotaCheck
import app.logdate.server.entitlements.QuotaReason
import app.logdate.server.logdate.ConditionalCreate
import app.logdate.server.logdate.LogDateAssociation
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateEntry
import app.logdate.server.logdate.LogDateJournal
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.LogDateRecordState
import app.logdate.server.logging.DownloadUrlSigningNotices
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.responses.error
import app.logdate.server.routes.MAX_SPACE_BLOB_BYTES
import app.logdate.server.sync.MediaAccessPolicy
import app.logdate.server.sync.SyncMetricsSnapshot
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.StorageModeErrorCodes
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.sync.AssociationChange
import app.logdate.shared.model.sync.ContentChange
import app.logdate.shared.model.sync.JournalChange
import io.github.aakira.napier.Napier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// --- Pagination + metric names ------------------------------------------------------------------

internal const val DEFAULT_SYNC_PAGE_SIZE = 200
internal const val MAX_SYNC_PAGE_SIZE = 500

/** Drafts page smaller and, unlike the other feeds, do not clamp the caller's `limit`. */
internal const val DRAFT_SYNC_PAGE_SIZE = 100
internal const val MILLIS_PER_DAY = 86_400_000L

internal const val METRIC_SYNC_STATUS = "sync.status"
internal const val METRIC_SYNC_METRICS = "sync.metrics"
internal const val METRIC_SYNC_METRICS_PROM = "sync.metrics.prometheus"
internal const val METRIC_CONTENT_UPLOAD = "sync.content.upload"
internal const val METRIC_CONTENT_CHANGES = "sync.content.changes"
internal const val METRIC_CONTENT_UPDATE = "sync.content.update"
internal const val METRIC_CONTENT_DELETE = "sync.content.delete"
internal const val METRIC_JOURNAL_UPLOAD = "sync.journal.upload"
internal const val METRIC_JOURNAL_CHANGES = "sync.journal.changes"
internal const val METRIC_JOURNAL_UPDATE = "sync.journal.update"
internal const val METRIC_JOURNAL_DELETE = "sync.journal.delete"
internal const val METRIC_ASSOCIATION_UPLOAD = "sync.association.upload"
internal const val METRIC_ASSOCIATION_CHANGES = "sync.association.changes"
internal const val METRIC_ASSOCIATION_DELETE = "sync.association.delete"
internal const val METRIC_MOMENT_UPLOAD = "sync.moment.upload"
internal const val METRIC_MOMENT_CHANGES = "sync.moment.changes"
internal const val METRIC_MOMENT_UPDATE = "sync.moment.update"
internal const val METRIC_MOMENT_DELETE = "sync.moment.delete"
internal const val METRIC_MEDIA_UPLOAD = "sync.media.upload"
internal const val METRIC_MEDIA_DOWNLOAD = "sync.media.download"
internal const val METRIC_MEDIA_DELETE = "sync.media.delete"
internal const val METRIC_SYNC_PURGE = "sync.maintenance.purge"

// --- Passive configuration for sync sub-routes --------------------------------------------------

/**
 * Environment-derived policy knobs the sync surface reads. Intentionally *only* passive values —
 * collaborator objects (services, repositories, metrics, rate limiters) are passed as individual
 * function parameters so the dependency graph of each sub-route stays visible at the call site.
 */
internal data class SyncRouteConfig(
    val mediaAccessPolicy: MediaAccessPolicy,
)

// --- Per-user write-path rate-limit policies ----------------------------------------------------

/**
 * Per-user ceilings on the write paths. Tuned to let an actively-syncing device churn through its
 * pending upload queue (which typically runs a dozen or two items in a burst) without bothering a
 * well-behaved client, while still throttling a runaway loop or a compromised token.
 */
internal val MEDIA_UPLOAD_RATE_LIMIT = RateLimitPolicy(maxRequests = 120, windowSeconds = 60)
internal val BACKUP_UPLOAD_RATE_LIMIT = RateLimitPolicy(maxRequests = 6, windowSeconds = 60 * 60)

// --- Multipart parsing --------------------------------------------------------------------------

internal data class ParsedMediaMultipartUpload(
    val contentId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray,
    val deviceId: String,
)

private suspend fun ApplicationCall.respondMediaTooLarge(maxDataBytes: Long) =
    respond(HttpStatusCode.PayloadTooLarge, error("MEDIA_TOO_LARGE", "Media files can be at most $maxDataBytes bytes"))

internal suspend fun ApplicationCall.respondMissingMultipartField(field: String) {
    respond(
        HttpStatusCode.BadRequest,
        error("VALIDATION_ERROR", "Missing required multipart field: $field"),
    )
}

/** Room for the form fields and part boundaries that travel with a media file. */
private const val MEDIA_MULTIPART_FIELDS_BYTES = 64L * 1024

/**
 * Reads a media upload, refusing a file larger than [maxDataBytes] with `413 MEDIA_TOO_LARGE`. A
 * request whose `Content-Length` already says so is refused before anything is read, and the
 * file part is never read past the limit.
 */
internal suspend fun ApplicationCall.receiveMediaMultipartUpload(maxDataBytes: Long = MAX_SPACE_BLOB_BYTES): ParsedMediaMultipartUpload? {
    val declaredLength = request.contentLength()
    if (declaredLength != null && declaredLength > maxDataBytes + MEDIA_MULTIPART_FIELDS_BYTES) {
        respondMediaTooLarge(maxDataBytes)
        return null
    }
    val multipart =
        runCatching { receiveMultipart() }.getOrElse {
            respond(
                HttpStatusCode.BadRequest,
                error("VALIDATION_ERROR", "Expected multipart/form-data body"),
            )
            return null
        }

    var contentId: String? = null
    var fileName: String? = null
    var mimeType: String? = null
    var sizeBytes: Long? = null
    var deviceId: String? = null
    var data: ByteArray? = null
    var dataTooLarge = false

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> {
                when (part.name) {
                    "contentId" -> contentId = part.value.trim()
                    "fileName" -> fileName = part.value.trim()
                    "mimeType" -> mimeType = part.value.trim()
                    "sizeBytes" -> sizeBytes = part.value.trim().toLongOrNull()
                    "deviceId" -> deviceId = part.value.trim()
                }
            }

            is PartData.FileItem -> {
                if (part.name == "data") {
                    val bytes = part.provider().readRemaining(maxDataBytes + 1).readByteArray()
                    if (bytes.size > maxDataBytes) dataTooLarge = true else data = bytes
                }
            }

            else -> Unit
        }
        part.dispose()
    }

    if (dataTooLarge) {
        respondMediaTooLarge(maxDataBytes)
        return null
    }
    val requiredContentId =
        contentId?.takeIf { it.isNotBlank() } ?: run {
            respondMissingMultipartField("contentId")
            return null
        }
    val requiredFileName =
        fileName?.takeIf { it.isNotBlank() } ?: run {
            respondMissingMultipartField("fileName")
            return null
        }
    val requiredMimeType =
        mimeType?.takeIf { it.isNotBlank() } ?: run {
            respondMissingMultipartField("mimeType")
            return null
        }
    val requiredSizeBytes =
        sizeBytes ?: run {
            respondMissingMultipartField("sizeBytes")
            return null
        }
    val requiredDeviceId =
        deviceId?.takeIf { it.isNotBlank() } ?: run {
            respondMissingMultipartField("deviceId")
            return null
        }
    val requiredData =
        data ?: run {
            respondMissingMultipartField("data")
            return null
        }

    if (requiredSizeBytes <= 0) {
        respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", "sizeBytes must be greater than 0"))
        return null
    }
    if (requiredSizeBytes != requiredData.size.toLong()) {
        respond(
            HttpStatusCode.BadRequest,
            error("VALIDATION_ERROR", "sizeBytes does not match uploaded binary payload size"),
        )
        return null
    }

    return ParsedMediaMultipartUpload(
        contentId = requiredContentId,
        fileName = requiredFileName,
        mimeType = requiredMimeType,
        sizeBytes = requiredSizeBytes,
        data = requiredData,
        deviceId = requiredDeviceId,
    )
}

// --- Auth helpers -------------------------------------------------------------------------------

private const val DEVICE_SESSION_REVOKED_MESSAGE = "This device was removed from the account"
private const val DEVICE_SCOPE_DENIED_MESSAGE = "A linked device cannot do this"

/**
 * Extracts the user ID from a full-access token. The routes that call this act on the whole account
 * (backups, location history, diagnostics, maintenance, device enrollment, key envelopes), so a linked
 * device token answers 403. Returns null and responds with 401/403/500 if authentication fails, so
 * callers just early-return.
 */
internal suspend fun extractUserId(
    call: ApplicationCall,
    tokenService: TokenService?,
): UUID? {
    if (tokenService == null) {
        call.respond(
            HttpStatusCode.InternalServerError,
            error("SERVER_MISCONFIGURED", "Token service is not configured"),
        )
        return null
    }

    val authHeader = call.request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respond(
            HttpStatusCode.Unauthorized,
            error("UNAUTHORIZED", "Missing or invalid Authorization header"),
        )
        return null
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    val accountId = tokenService.validateAccessToken(token)
    if (accountId == null) {
        if (tokenService.decodeAccessToken(token)?.scope == TokenScope.SYNC) {
            return call.rejectAccess(HttpStatusCode.Forbidden, DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED, DEVICE_SCOPE_DENIED_MESSAGE)
        }
        return call.rejectAccess(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid or expired token")
    }

    return call.parseAccountId(accountId)
}

/**
 * Extracts the user ID for a sync data route (contents, journals, associations, drafts, media, sync
 * status, moments). Full-access tokens pass, and so does a linked device token while its session is
 * active; a removed session answers `401 DEVICE_SESSION_REVOKED` so the device knows to stop.
 */
internal suspend fun extractSyncUserId(
    call: ApplicationCall,
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes? = null,
): UUID? {
    if (verifier == null) {
        call.respond(
            HttpStatusCode.InternalServerError,
            error("SERVER_MISCONFIGURED", "Token service is not configured"),
        )
        return null
    }
    val token = bearerToken(call.request.header(HttpHeaders.Authorization))
    val claims =
        when (val check = verifier.verify(token, allowSyncScope = true)) {
            is AccessCheck.Granted -> check.claims
            AccessCheck.Missing ->
                return call.rejectAccess(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing or invalid Authorization header")
            AccessCheck.Invalid ->
                return call.rejectAccess(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid or expired token")
            AccessCheck.SessionRevoked ->
                return call.rejectAccess(
                    HttpStatusCode.Unauthorized,
                    DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                    DEVICE_SESSION_REVOKED_MESSAGE,
                )
            AccessCheck.ScopeDenied ->
                return call.rejectAccess(HttpStatusCode.Forbidden, DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED, DEVICE_SCOPE_DENIED_MESSAGE)
        }
    return call.parseAccountId(claims.accountId)?.let { call.requireLegacyStorage(it, storageModes) }
}

/**
 * Extracts the user ID for a sync route that changes the whole account (merging journals). Only a
 * full-access token passes; a linked device token answers 403 `DEVICE_SCOPE_DENIED`.
 */
internal suspend fun extractFullAccessUserId(
    call: ApplicationCall,
    verifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes? = null,
): UUID? {
    if (verifier == null) {
        call.respond(
            HttpStatusCode.InternalServerError,
            error("SERVER_MISCONFIGURED", "Token service is not configured"),
        )
        return null
    }
    val token = bearerToken(call.request.header(HttpHeaders.Authorization))
    val claims =
        when (val check = verifier.verify(token, allowSyncScope = false)) {
            is AccessCheck.Granted -> check.claims
            AccessCheck.Missing ->
                return call.rejectAccess(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing or invalid Authorization header")
            AccessCheck.Invalid, AccessCheck.SessionRevoked ->
                return call.rejectAccess(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Invalid or expired token")
            AccessCheck.ScopeDenied ->
                return call.rejectAccess(HttpStatusCode.Forbidden, DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED, DEVICE_SCOPE_DENIED_MESSAGE)
        }
    return call.parseAccountId(claims.accountId)?.let { call.requireLegacyStorage(it, storageModes) }
}

/**
 * [accountId] when its data is kept in legacy storage, which the per-kind sync routes serve.
 *
 * - An account whose journal was moved into its personal space answers `403 SYNC_FORMAT_UPGRADE_REQUIRED`:
 *   an app that predates the move asks the person to update, and a newer one reads `/auth/me` again.
 *   It isn't a `409`, because released apps settle a queued change that gets a `409` as a conflict
 *   and stop sending it; a `403` leaves the change queued until the app is updated.
 * - Any other storage mode, including one this server doesn't recognize, answers
 *   `403 SYNC_STORAGE_MODE_MISMATCH`, so a device with a stale or wrong idea of the account's mode
 *   can't split its data between two stores; it reads `/auth/me` again instead.
 * - While a device checks the journal before moving it, a write answers
 *   `503 SYNC_MIGRATION_IN_PROGRESS` with `Retry-After`; the app keeps it queued and sends it later.
 *   Reads still work.
 *
 * Without [storageModes] every account passes.
 */
internal suspend fun ApplicationCall.requireLegacyStorage(
    accountId: UUID,
    storageModes: AccountStorageModes?,
): UUID? {
    val mode = storageModes?.of(accountId) ?: return accountId
    if (mode == AccountStorageMode.LEGACY) {
        if (request.httpMethod == HttpMethod.Get || !storageModes.legacyWritesHeld(accountId)) return accountId
        response.header(HttpHeaders.RetryAfter, MIGRATION_HOLD_RETRY_AFTER_SECONDS.toString())
        return rejectAccess(HttpStatusCode.ServiceUnavailable, StorageModeErrorCodes.SYNC_MIGRATION_IN_PROGRESS, MIGRATION_HOLD_MESSAGE)
    }
    if (mode == AccountStorageMode.PERSONAL_SPACE_V1 && storageModes.movedByMigration(accountId)) {
        return rejectAccess(HttpStatusCode.Forbidden, StorageModeErrorCodes.SYNC_FORMAT_UPGRADE_REQUIRED, FORMAT_UPGRADE_MESSAGE)
    }
    return rejectAccess(HttpStatusCode.Forbidden, StorageModeErrorCodes.SYNC_STORAGE_MODE_MISMATCH, STORAGE_MODE_MISMATCH_MESSAGE)
}

private const val STORAGE_MODE_MISMATCH_MESSAGE = "This account's data isn't kept in legacy sync storage; read the account again"
private const val FORMAT_UPGRADE_MESSAGE = "This account's journal moved to a newer sync format; update the app to keep syncing"
private const val MIGRATION_HOLD_MESSAGE = "This account's journal is being moved; send this change again later"

/** How long an app waits before sending a held write again. */
private const val MIGRATION_HOLD_RETRY_AFTER_SECONDS = 60

private suspend fun ApplicationCall.rejectAccess(
    status: HttpStatusCode,
    code: String,
    message: String,
): UUID? {
    respond(status, error(code, message))
    return null
}

private suspend fun ApplicationCall.parseAccountId(accountId: String): UUID? =
    try {
        UUID.fromString(accountId)
    } catch (_: IllegalArgumentException) {
        Napier.e("Account reference validation failed")
        respond(
            HttpStatusCode.Unauthorized,
            error("UNAUTHORIZED", "Invalid token payload"),
        )
        null
    }

internal fun ApplicationCall.requiredPathParam(name: String): String =
    requireNotNull(parameters[name]) { "Missing required route parameter: $name" }

// --- Create-only writes --------------------------------------------------------------------------

internal const val SERVER_VERSION_HEADER = "X-LogDate-Server-Version"
internal const val DELETED_AT_HEADER = "X-LogDate-Deleted-At"

/** `?ifAbsent=true`: create only, and answer 410 instead of recreating a deleted record. */
internal fun ApplicationCall.isStrictCreate(): Boolean = request.queryParameters["ifAbsent"] == "true"

/** `If-None-Match: *`: create only; a deleted record may be recreated, which the Mac app relies on. */
internal fun ApplicationCall.isCreateOnly(): Boolean = request.header(HttpHeaders.IfNoneMatch) == "*"

/**
 * Answers a create-only write: `201` with a `Location`, `412 <NOUN>_EXISTS` with `details.serverVersion`,
 * or `410 <NOUN>_DELETED` with `details.deletedAt` and `details.serverVersion`.
 */
internal suspend inline fun <T, reified R : Any> ApplicationCall.respondConditionalCreate(
    outcome: ConditionalCreate<T>,
    noun: String,
    location: String,
    created: (T) -> R,
) {
    val label = noun.replaceFirstChar(Char::uppercaseChar)
    val code = noun.uppercase()
    when (outcome) {
        is ConditionalCreate.Created -> {
            response.headers.append(HttpHeaders.Location, location)
            respond(HttpStatusCode.Created, created(outcome.record))
        }
        is ConditionalCreate.Exists -> {
            val details = outcome.serverVersion?.let { mapOf("serverVersion" to it.toString()) }.orEmpty()
            respond(HttpStatusCode.PreconditionFailed, error("${code}_EXISTS", "$label already exists", details))
        }
        is ConditionalCreate.Deleted -> {
            val details = mapOf("deletedAt" to outcome.deletedAt.toString(), "serverVersion" to outcome.serverVersion.toString())
            respond(HttpStatusCode.Gone, error("${code}_DELETED", "$label was deleted", details))
        }
    }
}

/** Answers `HEAD` for one record: `200` or `410` with its server version, or `404`. */
internal suspend fun ApplicationCall.respondRecordState(state: LogDateRecordState) {
    when (state) {
        is LogDateRecordState.Live -> {
            response.headers.append(SERVER_VERSION_HEADER, state.serverVersion.toString())
            respond(HttpStatusCode.OK)
        }
        is LogDateRecordState.Deleted -> {
            response.headers.append(SERVER_VERSION_HEADER, state.serverVersion.toString())
            response.headers.append(DELETED_AT_HEADER, state.deletedAt.toString())
            respond(HttpStatusCode.Gone)
        }
        LogDateRecordState.Absent -> respond(HttpStatusCode.NotFound)
    }
}

// --- Wire-shape mappers --------------------------------------------------------------------------

internal fun LogDateEntry.toContentChange(): ContentChange =
    ContentChange(
        id = id,
        type = type,
        content = content,
        mediaUri = mediaUri,
        durationMs = durationMs ?: 0L,
        createdAt = createdAt,
        lastUpdated = lastUpdated,
        serverVersion = version,
        isDeleted = false,
        caption = caption,
        photoPresentation = photoPresentation,
        location = location,
        transcript = transcript,
        capturedOn = capturedOn,
        capturedDuring = capturedDuring,
    )

internal fun LogDateJournal.toJournalChange(): JournalChange =
    JournalChange(
        id = id,
        title = title,
        description = description,
        createdAt = createdAt,
        lastUpdated = lastUpdated,
        serverVersion = version,
        isDeleted = false,
    )

internal fun LogDateAssociation.toAssociationChange(): AssociationChange =
    AssociationChange(
        journalId = journalId,
        contentId = entryId,
        createdAt = createdAt,
        serverVersion = version,
        isDeleted = false,
    )

// --- Download URL resolution ---------------------------------------------------------------------

internal fun buildMediaDownloadUrl(
    call: ApplicationCall,
    mediaId: String,
): String = buildBinaryDownloadUrl(call, "/api/v1/media/$mediaId/binary")

internal fun buildBackupDownloadUrl(
    call: ApplicationCall,
    backupId: String,
): String = buildBinaryDownloadUrl(call, "/api/v1/backups/$backupId/binary")

private fun buildBinaryDownloadUrl(
    call: ApplicationCall,
    path: String,
): String {
    val origin = call.request.local
    val defaultPort =
        (origin.scheme == "http" && origin.localPort == 80) ||
            (origin.scheme == "https" && origin.localPort == 443)
    val portPart = if (defaultPort) "" else ":${origin.localPort}"
    return "${origin.scheme}://${origin.localHost}$portPart$path"
}

internal suspend fun resolveMediaDownloadUrl(
    call: ApplicationCall,
    record: LogDateMedia,
    mediaStorage: LogDateBlobStorage?,
    accessPolicy: MediaAccessPolicy,
): String {
    if (accessPolicy.useSignedUrls && mediaStorage != null && record.storagePath != null) {
        return runCatching {
            withContext(Dispatchers.IO) { mediaStorage.getSignedDownloadUrl(record.storagePath, accessPolicy.signedUrlTtlHours) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Napier.e(DownloadUrlSigningNotices.MEDIA_SIGNING_FAILED)
            buildMediaDownloadUrl(call, record.mediaId)
        }
    }
    return buildMediaDownloadUrl(call, record.mediaId)
}

internal suspend fun resolveBackupDownloadUrl(
    call: ApplicationCall,
    record: LogDateBackup,
    mediaStorage: LogDateBlobStorage?,
    accessPolicy: MediaAccessPolicy,
): String {
    if (accessPolicy.useSignedUrls && mediaStorage != null) {
        return runCatching {
            withContext(Dispatchers.IO) { mediaStorage.getSignedDownloadUrl(record.storagePath, accessPolicy.signedUrlTtlHours) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Napier.e(DownloadUrlSigningNotices.BACKUP_SIGNING_FAILED)
            buildBackupDownloadUrl(call, record.id.toString())
        }
    }
    return buildBackupDownloadUrl(call, record.id.toString())
}

// --- Prometheus export ---------------------------------------------------------------------------

internal fun SyncMetricsSnapshot.toPrometheus(): String {
    val builder = StringBuilder()
    builder.appendLine("# HELP logdate_sync_conflicts_total Total sync conflicts detected.")
    builder.appendLine("# TYPE logdate_sync_conflicts_total counter")
    builder.appendLine("logdate_sync_conflicts_total $conflictCount")
    builder.appendLine("# HELP logdate_sync_operation_success_total Successful sync operations by type.")
    builder.appendLine("# TYPE logdate_sync_operation_success_total counter")
    builder.appendLine("# HELP logdate_sync_operation_error_total Failed sync operations by type.")
    builder.appendLine("# TYPE logdate_sync_operation_error_total counter")
    builder.appendLine("# HELP logdate_sync_operation_duration_ms_total Total sync operation duration by type.")
    builder.appendLine("# TYPE logdate_sync_operation_duration_ms_total counter")
    builder.appendLine("# HELP logdate_sync_operation_bytes_total Total bytes processed by operation type.")
    builder.appendLine("# TYPE logdate_sync_operation_bytes_total counter")
    builder.appendLine("# HELP logdate_sync_operation_duration_ms Sync operation duration in milliseconds.")
    builder.appendLine("# TYPE logdate_sync_operation_duration_ms histogram")

    operations.forEach { operation ->
        val label = escapeLabelValue(operation.name)
        builder.appendLine("logdate_sync_operation_success_total{operation=\"$label\"} ${operation.successCount}")
        builder.appendLine("logdate_sync_operation_error_total{operation=\"$label\"} ${operation.errorCount}")
        builder.appendLine("logdate_sync_operation_duration_ms_total{operation=\"$label\"} ${operation.totalDurationMs}")
        builder.appendLine("logdate_sync_operation_bytes_total{operation=\"$label\"} ${operation.totalBytes}")
        operation.durationBuckets.forEach { bucket ->
            val bound = if (bucket.upperBoundMs == Long.MAX_VALUE) "+Inf" else bucket.upperBoundMs.toString()
            builder.appendLine("logdate_sync_operation_duration_ms_bucket{operation=\"$label\",le=\"$bound\"} ${bucket.count}")
        }
        builder.appendLine("logdate_sync_operation_duration_ms_sum{operation=\"$label\"} ${operation.totalDurationMs}")
        builder.appendLine(
            "logdate_sync_operation_duration_ms_count{operation=\"$label\"} ${operation.successCount + operation.errorCount}",
        )
    }

    builder.appendLine("# HELP logdate_sync_diagnostic_events_total Safe diagnostic transitions by classification.")
    builder.appendLine("# TYPE logdate_sync_diagnostic_events_total counter")
    diagnostics.forEach { event ->
        builder.appendLine(
            "logdate_sync_diagnostic_events_total{phase=\"${event.phase.name}\",outcome=\"${event.outcome.name}\",reason=\"${event.reason.name}\",protocol_version=\"1\"} ${event.count}",
        )
    }
    builder.appendLine("# HELP logdate_sync_diagnostic_drops_total Rejected diagnostic measurements.")
    builder.appendLine("# TYPE logdate_sync_diagnostic_drops_total counter")
    builder.appendLine("logdate_sync_diagnostic_drops_total $diagnosticDrops")

    return builder.toString()
}

private fun escapeLabelValue(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// --- Error-response shapes for quota and rate-limit violations -----------------------------------

internal suspend fun respondRateLimited(
    call: ApplicationCall,
    rateLimiter: SlidingWindowRateLimiter,
    key: String,
    policy: RateLimitPolicy,
) {
    val retryAfter = rateLimiter.retryAfterSeconds(key, policy)
    AuditLogger.emit(
        AuditCategory.SYNC_RATE_LIMITED,
        mapOf(
            "key" to key,
            "retryAfterSeconds" to retryAfter.toString(),
            "maxRequests" to policy.maxRequests.toString(),
            "windowSeconds" to policy.windowSeconds.toString(),
        ),
    )
    call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
    call.respond(
        HttpStatusCode.TooManyRequests,
        error(
            code = "RATE_LIMIT_EXCEEDED",
            message = "Too many uploads. Try again in $retryAfter seconds.",
            details = mapOf("retryAfterSeconds" to retryAfter.toString()),
        ),
    )
}

/**
 * Structured 402 Payment Required. `reason` is one of the [QuotaReason] enum values and
 * `limit`/`current` give the user-facing numbers so the client can render a specific "X of Y"
 * message without parsing the free-form body.
 */
internal suspend fun respondQuotaExceeded(
    call: ApplicationCall,
    denied: QuotaCheck.Denied,
) {
    val reasonMessage =
        when (denied.reason) {
            QuotaReason.STORAGE_BYTES ->
                "Storage quota exceeded: ${denied.current} of ${denied.limit} bytes in use."
            QuotaReason.BACKUP_COUNT ->
                "Backup quota exceeded: ${denied.current} of ${denied.limit} backups stored."
        }
    AuditLogger.emit(
        AuditCategory.SYNC_QUOTA_EXCEEDED,
        mapOf(
            "reason" to denied.reason.name,
            "limit" to denied.limit.toString(),
            "current" to denied.current.toString(),
        ),
    )
    call.respond(
        HttpStatusCode.PaymentRequired,
        error(
            code = "QUOTA_EXCEEDED",
            message = reasonMessage,
            details =
                mapOf(
                    "reason" to denied.reason.name,
                    "limit" to denied.limit.toString(),
                    "current" to denied.current.toString(),
                ),
        ),
    )
}
