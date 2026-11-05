package app.logdate.server.logging

import app.logdate.shared.model.diagnostics.DiagnosticContext
import app.logdate.shared.model.diagnostics.DiagnosticPlatform
import app.logdate.shared.model.diagnostics.DiagnosticProtocol
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.diagnostics.diagnosticCode
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.plc.PlcDirectoryException

internal const val SAFE_SERVER_EVENT_TAG = "ServerDiagnostics"

private val serverDiagnosticContext =
    DiagnosticContext(
        platform = DiagnosticPlatform.SERVER,
        protocols = DiagnosticProtocol.entries.toList(),
        serverBuild = System.getenv("LOGDATE_SERVER_BUILD_ID")?.takeIf { Regex("[0-9a-f]{7,64}").matches(it) },
    )

internal fun recordServerDiagnostic(event: SyncDiagnosticEvent) {
    runCatching {
        DiagnosticReportCodec.validateEvent(event)
        val safe = event.copy(code = diagnosticCode(event), context = serverDiagnosticContext)
        Napier.i(tag = SAFE_SERVER_EVENT_TAG, message = Json.encodeToString(safe))
    }
}

private val approvedNotices =
    mapOf(
        "Database not available, using in-memory repositories" to "SERVER_DATABASE_IN_MEMORY",
        (
            "LOGDATE_ALLOW_INMEMORY_FALLBACK is set: running on in-memory repositories. Nothing is " +
                "persisted and every record is lost when the process exits."
        ) to "SERVER_DATABASE_IN_MEMORY",
        "Account blob cleanup queue overdue" to "SERVER_ACCOUNT_BLOB_CLEANUP_OVERDUE",
        "Account blob cleanup failed" to "SERVER_ACCOUNT_BLOB_CLEANUP_FAILED",
        "Account blob cleanup batch complete" to "SERVER_ACCOUNT_BLOB_CLEANUP_COMPLETE",
        "Database repositories initialized successfully" to "SERVER_DATABASE_READY",
        "Production database unavailable; refusing to start with in-memory fallback" to "SERVER_DATABASE_REQUIRED",
        "Database unavailable; refusing to start somewhere nothing would persist" to "SERVER_DATABASE_REQUIRED",
        KeyCustodyNotices.SIGNING_KEYS_REWRAPPED to "SERVER_SIGNING_KEYS_REWRAPPED",
        KeyCustodyNotices.SIGNING_KEYS_CURRENT to "SERVER_SIGNING_KEYS_CURRENT",
        KeyCustodyNotices.SIGNING_KEYS_UNREADABLE to "SERVER_SIGNING_KEYS_UNREADABLE",
        KeyCustodyNotices.SIGNING_KEYS_REWRAP_FAILED to "SERVER_SIGNING_KEYS_REWRAP_FAILED",
        KeyCustodyNotices.OAUTH_KEY_STAGED to "SERVER_OAUTH_KEY_STAGED",
        KeyCustodyNotices.OAUTH_KEY_PROMOTED to "SERVER_OAUTH_KEY_PROMOTED",
        KeyCustodyNotices.OAUTH_KEY_RETIRED to "SERVER_OAUTH_KEY_RETIRED",
        KeyCustodyNotices.OAUTH_KEY_ROTATION_FAILED to "SERVER_OAUTH_KEY_ROTATION_FAILED",
        DownloadUrlSigningNotices.MEDIA_SIGNING_FAILED to "SERVER_MEDIA_URL_SIGNING_FAILED",
        DownloadUrlSigningNotices.BACKUP_SIGNING_FAILED to "SERVER_BACKUP_URL_SIGNING_FAILED",
        SpacePurgeNotices.BLOBS_PURGED to "SERVER_SPACE_BLOBS_PURGED",
        SpacePurgeNotices.BLOB_DELETE_FAILED to "SERVER_SPACE_BLOB_DELETE_FAILED",
        SpacePurgeNotices.PURGE_FAILED to "SERVER_SPACE_PURGE_FAILED",
        SpaceMigrationNotices.SWITCHED to "SERVER_SPACE_MIGRATION_SWITCHED",
        SpaceMigrationNotices.SWITCHED_BACK to "SERVER_SPACE_MIGRATION_SWITCHED_BACK",
        SpaceMigrationNotices.COPY_MISMATCH to "SERVER_SPACE_MIGRATION_COPY_MISMATCH",
        SpaceMigrationNotices.CLEANUP_FAILED to "SERVER_LEGACY_COPY_CLEANUP_FAILED",
        SpaceMigrationNotices.CLEANUP_SKIPPED to "SERVER_LEGACY_COPY_CLEANUP_SKIPPED",
        SpaceMigrationNotices.CLEANUP_RUN_FAILED to "SERVER_LEGACY_COPY_CLEANUP_RUN_FAILED",
        SpaceMigrationNotices.CLEANUP_PAUSED to "SERVER_LEGACY_COPY_CLEANUP_PAUSED",
        RepoEventNotices.COMMIT_UNREADABLE to "SERVER_REPO_EVENT_COMMIT_UNREADABLE",
        RepoEventNotices.EVENT_NOT_RECORDED to "SERVER_REPO_EVENT_NOT_RECORDED",
        RepoEventNotices.PRIVATE_RECORDS_HELD_BACK to "SERVER_REPO_EVENT_PRIVATE_RECORDS_HELD_BACK",
        RepoEventNotices.TREE_UNREADABLE to "SERVER_REPO_TREE_UNREADABLE",
        RepoEventNotices.FOLLOWER_FAILED to "SERVER_REPO_EVENT_FOLLOWER_FAILED",
        RepoEventNotices.PRUNE_FAILED to "SERVER_REPO_EVENT_PRUNE_FAILED",
        RepoEventNotices.CRAWL_REQUESTED to "SERVER_RELAY_CRAWL_REQUESTED",
        RepoEventNotices.CRAWL_REFUSED to "SERVER_RELAY_CRAWL_REFUSED",
        RepoEventNotices.CRAWL_FAILED to "SERVER_RELAY_CRAWL_FAILED",
        IdentityNotices.HANDLE_RESOLUTION_FAILED to "SERVER_HANDLE_RESOLUTION_FAILED",
        IdentityNotices.SESSION_REQUEST_FAILED to "SERVER_SESSION_REQUEST_FAILED",
        IdentityNotices.BACKFILL_REFUSED_PUBLISHING to "SERVER_IDENTITY_BACKFILL_REFUSED_PUBLISHING",
        IdentityNotices.BACKFILL_FAILED to "SERVER_IDENTITY_BACKFILL_FAILED",
        IdentityNotices.BACKFILL_ACCOUNT_FAILED to "SERVER_IDENTITY_BACKFILL_ACCOUNT_FAILED",
        IdentityPublishingNotices.PLC_OPERATION_PUBLISHED to "SERVER_PLC_OPERATION_PUBLISHED",
        IdentityPublishingNotices.PLC_OPERATION_REJECTED to "SERVER_PLC_OPERATION_REJECTED",
        IdentityPublishingNotices.PLC_DIRECTORY_RATE_LIMITED to "SERVER_PLC_DIRECTORY_RATE_LIMITED",
        IdentityPublishingNotices.PLC_DIRECTORY_UNREACHABLE to "SERVER_PLC_DIRECTORY_UNREACHABLE",
        IdentityPublishingNotices.PLC_OPERATION_UNCONFIRMED to "SERVER_PLC_OPERATION_UNCONFIRMED",
        IdentityPublishingNotices.RUN_STARTED to "SERVER_IDENTITY_PUBLISHING_STARTED",
        IdentityPublishingNotices.RUN_COMPLETE to "SERVER_IDENTITY_PUBLISHING_COMPLETE",
        IdentityPublishingNotices.RUN_STOPPED to "SERVER_IDENTITY_PUBLISHING_STOPPED",
        IdentityPublishingNotices.REFUSED_USAGE to "SERVER_IDENTITY_PUBLISHING_REFUSED_USAGE",
        IdentityPublishingNotices.REFUSED_PUBLISHING_OFF to "SERVER_IDENTITY_PUBLISHING_REFUSED_PUBLISHING_OFF",
        IdentityPublishingNotices.REFUSED_DIRECTORY_UNCONFIRMED to "SERVER_IDENTITY_PUBLISHING_REFUSED_DIRECTORY_UNCONFIRMED",
        IdentityPublishingNotices.REFUSED_NOT_PLC to "SERVER_IDENTITY_PUBLISHING_REFUSED_NOT_PLC",
        IdentityPublishingNotices.RUN_FAILED to "SERVER_IDENTITY_PUBLISHING_FAILED",
        IdentityPublishingNotices.ACCOUNT_FAILED to "SERVER_IDENTITY_PUBLISH_FAILED",
    )

/**
 * Messages that carry only counts. They pass through unchanged because a number of accounts says
 * nothing about any one account.
 */
private val approvedCountMessages =
    listOf(
        Regex("^Identity backfill dry run: \\d+ accounts need an identity$"),
        Regex("^Identity backfill complete: \\d+ provisioned, \\d+ skipped, \\d+ failed$"),
        Regex("^Deleted the legacy copies of \\d+ moved journals$"),
    )

private fun isApprovedCountMessage(message: String?): Boolean =
    message != null && message.length <= 120 && approvedCountMessages.any { it.matches(message) }

/**
 * Fixed log messages for AT Protocol identity. A failed handle lookup is an error because outside
 * apps then cannot verify LogDate handles; the backfill messages report the operator command.
 * [BACKFILL_ACCOUNT_FAILED] names no account, because production logs carry nothing that identifies
 * one; an account that failed still lacks an identity, so the next dry run counts it again.
 */
internal object IdentityNotices {
    const val HANDLE_RESOLUTION_FAILED = "Resolving a handle failed"
    const val SESSION_REQUEST_FAILED = "A hosted session request failed"
    const val BACKFILL_REFUSED_PUBLISHING =
        "Identity backfill refused: bulk publication requires individual identity consent"
    const val BACKFILL_FAILED = "Identity backfill failed"
    const val BACKFILL_ACCOUNT_FAILED = "Identity backfill could not provision one account; the run continues"

    fun backfillDryRun(pending: Int): String = "Identity backfill dry run: $pending accounts need an identity"

    fun backfillComplete(
        provisioned: Int,
        skipped: Int,
        failed: Int,
    ): String = "Identity backfill complete: $provisioned provisioned, $skipped skipped, $failed failed"
}

/**
 * Fixed log messages for signing-key custody. Production logs keep only approved messages, so these
 * outcomes stay visible there as SERVER_SIGNING_KEYS_* and SERVER_OAUTH_KEY_* codes.
 */
internal object KeyCustodyNotices {
    const val SIGNING_KEYS_REWRAPPED = "Re-encrypted stored signing keys under the current key-encryption key"
    const val SIGNING_KEYS_CURRENT = "Every stored signing key already uses the current key-encryption key"
    const val SIGNING_KEYS_UNREADABLE = "Some stored signing keys can't be decrypted with either key-encryption key"
    const val SIGNING_KEYS_REWRAP_FAILED = "Re-encrypting stored signing keys failed"
    const val OAUTH_KEY_STAGED = "Staged the next OAuth signing key"
    const val OAUTH_KEY_PROMOTED = "Promoted the next OAuth signing key"
    const val OAUTH_KEY_RETIRED = "Deleted the retired OAuth signing key"
    const val OAUTH_KEY_ROTATION_FAILED = "OAuth signing key rotation check failed"
}

/**
 * Fixed log messages for when signing a direct storage download URL fails and the response falls
 * back to the server's own binary endpoint. Downloads keep working, but every one is proxied
 * through the server. On Cloud Run the usual cause is that the runtime service account isn't
 * allowed to sign as itself (`iam.serviceAccounts.signBlob`).
 */
internal object DownloadUrlSigningNotices {
    const val MEDIA_SIGNING_FAILED = "Signing a media download URL failed; serving it through the server instead"
    const val BACKUP_SIGNING_FAILED = "Signing a backup download URL failed; serving it through the server instead"
}

/**
 * Fixed log messages for moving journals into personal spaces. Each names an outcome an operator
 * watches while accounts move, and none says which account it was.
 */
internal object SpaceMigrationNotices {
    const val SWITCHED = "Space migration switched an account into its personal space"
    const val SWITCHED_BACK = "Space migration switched an account back to legacy storage"
    const val COPY_MISMATCH = "Space migration copied a legacy file whose stored bytes don't match"
    const val CLEANUP_FAILED = "Deleting a moved journal's legacy copy failed; the next run tries again"
    const val CLEANUP_SKIPPED = "A switched journal isn't in its personal space; its legacy copy stays"
    const val CLEANUP_RUN_FAILED = "Deleting the legacy copies of moved journals failed"
    const val CLEANUP_PAUSED = "Deleting the legacy copies of moved journals is paused"
}

/**
 * Fixed log messages for the space maintenance sweep, which deletes old tombstones and blobs that no
 * record refers to. A blob's row is deleted only after its object is, so a failed object delete
 * leaves both in place and the next sweep tries that blob again. Both failures are errors: a blob
 * that keeps failing keeps its bytes in storage.
 */
internal object SpacePurgeNotices {
    const val BLOBS_PURGED = "Purged space blobs that nothing refers to"
    const val BLOB_DELETE_FAILED = "Deleting a space blob from storage failed; the next sweep retries it"
    const val PURGE_FAILED = "Purging space tombstones and blobs failed; the next sweep retries"
}

/**
 * Fixed log messages for the repository event stream and for asking relays to follow it. None names
 * an account or a relay, because production logs carry nothing that identifies either. A failure to
 * record an event is an error: a relay that missed it learns the repository's state again from the
 * next event, but only if there is one.
 */
internal object RepoEventNotices {
    const val COMMIT_UNREADABLE =
        "A repository commit could not be compared with the one before it; announcing the repository's state instead"
    const val EVENT_NOT_RECORDED = "Announcing a repository change failed; the next change announces the repository's state"
    const val PRIVATE_RECORDS_HELD_BACK = "A repository that holds private records was not announced"
    const val TREE_UNREADABLE = "A repository's record tree could not be read, so the repository was not shown"
    const val FOLLOWER_FAILED = "A follower of the repository event stream stopped after an error"
    const val PRUNE_FAILED = "Deleting old repository events failed; the next sweep retries"
    const val CRAWL_REQUESTED = "Asked a relay to crawl this server"
    const val CRAWL_REFUSED = "A relay refused to crawl this server"
    const val CRAWL_FAILED = "Asking a relay to crawl this server failed"
}

/**
 * Fixed log messages for sending authorized `did:plc` operations to the PLC directory, from key
 * changes and the identity publishing command alike. Production logs keep only approved messages,
 * so each outcome stays countable there as one SERVER_PLC_* or SERVER_IDENTITY_* code. The
 * publishing command also writes one [summary] line of counts per batch and per run.
 */
internal object IdentityPublishingNotices {
    const val PLC_OPERATION_PUBLISHED = "The PLC directory accepted a PLC operation"
    const val PLC_OPERATION_REJECTED = "The PLC directory rejected a PLC operation"
    const val PLC_DIRECTORY_RATE_LIMITED = "The PLC directory rate-limited a PLC operation"
    const val PLC_DIRECTORY_UNREACHABLE = "Sending a PLC operation to the PLC directory failed before it answered"
    const val PLC_OPERATION_UNCONFIRMED = "The PLC directory accepted a PLC operation that its audit log does not show"
    const val RUN_STARTED = "Identity publishing started"
    const val RUN_COMPLETE = "Identity publishing finished"
    const val RUN_STOPPED = "Identity publishing stopped at its failure limit"
    const val REFUSED_USAGE = "Identity publishing refused to start: the arguments are not valid"
    const val REFUSED_PUBLISHING_OFF = "Identity publishing refused to start: individual publication authority is unavailable"
    const val REFUSED_DIRECTORY_UNCONFIRMED =
        "Identity publishing refused to start: --confirm-directory does not name the configured PLC directory"
    const val REFUSED_NOT_PLC = "Identity publishing refused to start: hosted accounts do not use did:plc"
    const val RUN_FAILED = "Identity publishing failed"
    const val ACCOUNT_FAILED = "Publishing an account's identity failed"

    /** The first word of every summary line; the rest is `name=count` pairs. */
    const val SUMMARY_CODE = "SERVER_IDENTITY_PUBLISHING_SUMMARY"

    private val summaryPattern = Regex("^$SUMMARY_CODE( [a-z_]{1,40}=[0-9]{1,9}){1,32}$")

    /** Picks the notice for a failed submission from what the directory answered, if anything. */
    fun forSubmitFailure(failure: Throwable): String {
        val status = (failure as? PlcDirectoryException)?.statusCode
        return when {
            status == HTTP_TOO_MANY_REQUESTS -> PLC_DIRECTORY_RATE_LIMITED
            status != null && status in HTTP_CLIENT_ERRORS -> PLC_OPERATION_REJECTED
            else -> PLC_DIRECTORY_UNREACHABLE
        }
    }

    /** A line of counts that carries no identifiers, so production logs can keep it as written. */
    fun summary(counts: Map<String, Int>): String =
        buildString {
            append(SUMMARY_CODE)
            counts.forEach { (name, count) -> append(' ').append(name).append('=').append(count) }
        }.also { require(isSummary(it)) { "Summary names must be lowercase words and counts must be non-negative" } }

    fun isSummary(message: String?): Boolean = message != null && summaryPattern.matches(message)

    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val HTTP_CLIENT_ERRORS = 400..499
}

internal fun safeServerMessage(
    priority: LogLevel,
    tag: String?,
    message: String?,
): String {
    if (tag == SAFE_SERVER_EVENT_TAG) validatedEvent(message)?.let { return it }
    approvedNotices[message]?.let { return it }
    if (isApprovedCountMessage(message)) return requireNotNull(message)
    if (IdentityPublishingNotices.isSummary(message)) return requireNotNull(message)
    return when (priority) {
        LogLevel.ERROR, LogLevel.ASSERT -> "SERVER_ERROR"
        LogLevel.WARNING -> "SERVER_WARNING"
        else -> "SERVER_NOTICE"
    }
}

private fun validatedEvent(message: String?): String? {
    if (message == null || message.length > 4096) return null
    return runCatching {
        val event = Json.decodeFromString<SyncDiagnosticEvent>(message)
        DiagnosticReportCodec.validateEvent(event)
        Json.encodeToString(event)
    }.getOrNull()
}

/** Includes framework/library logs in the same stdout privacy boundary. */
class SafeServerMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String {
        if (event.loggerName == SERVER_LOG_NAME) {
            val text = event.formattedMessage
            if (text in approvedNotices.values || text in setOf("SERVER_ERROR", "SERVER_WARNING", "SERVER_NOTICE")) return text
            if (isApprovedCountMessage(text)) return text
            if (IdentityPublishingNotices.isSummary(text)) return text
            validatedEvent(text)?.let { return it }
        }
        return "SERVER_LIBRARY_EVENT"
    }
}
