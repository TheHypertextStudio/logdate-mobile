package app.logdate.server.routes

import app.logdate.server.atproto.firehose.AtprotoRepoStatus
import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.atproto.firehose.RepoStanding
import app.logdate.server.clientAddress
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.transfer.TransferLimits
import app.logdate.server.transfer.TransferSlots
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.BlobDownload
import studio.hypertext.atproto.pds.GetBlobRequest
import studio.hypertext.atproto.pds.GetLatestCommitResponse
import studio.hypertext.atproto.pds.GetRepoStatusResponse
import studio.hypertext.atproto.pds.ListRecordsResponse
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.repo.CarCodec
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid

/**
 * The read methods of the repository and sync namespaces as anyone may call them, with no sign-in.
 *
 * Each `try` method answers the call and returns `true` when the server shows the repository it
 * names, and returns `false` without touching the response when it does not, so the caller falls
 * through to the owner-only handling. That keeps the two audiences apart: this class never looks at
 * a token, and everything it returns comes from [PublicRepoAccess], which only reaches public
 * collections.
 */
internal class PublicRepoReads(
    private val access: PublicRepoAccess,
    private val identityService: AtprotoIdentityService,
    private val blobService: PdsBlobService?,
    private val downloads: PublicRepoDownloadLimits = PublicRepoDownloadLimits(),
) {
    suspend fun tryGetRecord(
        call: ApplicationCall,
        repo: String,
        collection: String,
        recordKey: String,
        cid: String?,
    ): Boolean {
        val did = shownRepo(repo) ?: return false
        val nsid = Nsid.parse(collection).getOrNull()
        val key = RecordKey.parse(recordKey).getOrNull()
        if (nsid == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Invalid repo, collection, or record key"))
            return true
        }
        val record = access.record(did, nsid, key, cid)
        if (record == null) {
            call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("RecordNotFound", "Record not found"))
        } else {
            call.respond(HttpStatusCode.OK, record)
        }
        return true
    }

    suspend fun tryListRecords(
        call: ApplicationCall,
        repo: String,
        collection: String,
        limit: Int,
        cursor: String?,
        reverse: Boolean,
    ): Boolean {
        val did = shownRepo(repo) ?: return false
        val nsid = Nsid.parse(collection).getOrNull()
        if (nsid == null) {
            call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Invalid repo or collection"))
            return true
        }
        call.respond(HttpStatusCode.OK, ListRecordsResponse.fromPage(access.records(did, nsid, limit, cursor, reverse)))
        return true
    }

    suspend fun tryGetRepo(
        call: ApplicationCall,
        did: AtprotoDid,
        since: Tid?,
    ): Boolean {
        if (respondIfInactive(call, did)) return true
        if (access.readableHead(did) == null) return false
        downloads.withDownload(call, PublicRepoDownloadLimits.Budget.REPOSITORIES) {
            val export = access.export(did, since)
            if (export == null) {
                call.respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", "Unknown repo: $did"))
            } else {
                call.respondBytes(CarCodec.write(export), ContentType.parse(CAR_CONTENT_TYPE), HttpStatusCode.OK)
            }
        }
        return true
    }

    suspend fun tryGetLatestCommit(
        call: ApplicationCall,
        did: AtprotoDid,
    ): Boolean {
        if (respondIfInactive(call, did)) return true
        val head = access.headIfShown(did) ?: return false
        call.respond(HttpStatusCode.OK, GetLatestCommitResponse(cid = head.commitCid, rev = Tid.fromLong(head.revision)))
        return true
    }

    suspend fun tryGetRepoStatus(
        call: ApplicationCall,
        did: AtprotoDid,
    ): Boolean {
        when (val standing = access.standing(did) ?: return false) {
            RepoStanding.Active -> {
                // An account announced before it wrote anything has no head, and one whose tree still holds
                // private records shows none: both are active, without a revision.
                val rev = access.headIfShown(did)?.let { Tid.fromLong(it.revision) }
                call.respond(HttpStatusCode.OK, GetRepoStatusResponse(did = did, active = true, rev = rev))
            }
            is RepoStanding.Inactive -> {
                val rev = if (standing.deleted) null else access.publicHeadOf(did)?.let { Tid.fromLong(it.revision) }
                call.respond(HttpStatusCode.OK, GetRepoStatusResponse(did = did, active = false, status = standing.status, rev = rev))
            }
        }
        return true
    }

    /**
     * Serves [cid] only when a public record of [did] refers to it. A blob nothing public refers to
     * is answered as missing, the same as a blob that does not exist.
     */
    suspend fun tryGetBlob(
        call: ApplicationCall,
        did: AtprotoDid,
        cid: Cid,
    ): Boolean {
        if (access.readableHead(did) == null) return false
        downloads.withDownload(call, PublicRepoDownloadLimits.Budget.BLOBS) {
            val download: BlobDownload? =
                if (access.referencesBlob(did, cid)) blobService?.getBlob(GetBlobRequest(did, cid))?.getOrThrow() else null
            if (download == null) {
                call.respond(HttpStatusCode.NotFound, PdsErrorResponse("BlobNotFound", "Blob not found"))
            } else {
                val contentType = runCatching { ContentType.parse(download.contentType) }.getOrDefault(ContentType.Application.OctetStream)
                call.respondBytes(download.bytes, contentType, HttpStatusCode.OK)
            }
        }
        return true
    }

    /**
     * Answers a method that needs the repository itself with the error the AT Protocol names for an
     * account that is not active, as a reference PDS does, and returns `true`. A deleted account is
     * a repository that does not exist.
     */
    private suspend fun respondIfInactive(
        call: ApplicationCall,
        did: AtprotoDid,
    ): Boolean {
        val inactive = access.standing(did) as? RepoStanding.Inactive ?: return false
        when (inactive.status) {
            AtprotoRepoStatus.DEACTIVATED ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("RepoDeactivated", "Repo has been deactivated: $did"),
                )
            AtprotoRepoStatus.SUSPENDED ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("RepoSuspended", "Repo has been suspended: $did"),
                )
            AtprotoRepoStatus.TAKENDOWN ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("RepoTakendown", "Repo has been taken down: $did"),
                )
            else -> call.respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", "Unknown repo: $did"))
        }
        return true
    }

    /** The DID of [repo] (a DID or a handle) when the server shows its repository. */
    private suspend fun shownRepo(repo: String): AtprotoDid? {
        val trimmed = repo.trim()
        val did =
            AtprotoDid.parse(trimmed).getOrNull()
                ?: identityService
                    .findByHandle(trimmed)
                    ?.did
                    ?.let(AtprotoDid::parse)
                    ?.getOrNull()
                ?: return null
        return did.takeIf { access.readableHead(it) != null }
    }

    private companion object {
        const val CAR_CONTENT_TYPE = "application/vnd.ipld.car"
    }
}

/**
 * Limits on the public downloads anyone may ask for: whole repositories (`com.atproto.sync.getRepo`)
 * and blobs (`com.atproto.sync.getBlob`). Both are built in memory before they are sent, so at most
 * [TransferLimits.downloads] of them run at once on an instance, and each network address has its
 * own per-minute budget for each kind.
 */
internal class PublicRepoDownloadLimits(
    val transfers: TransferSlots = TransferSlots(TransferLimits(downloads = 4)),
    private val repositories: RateLimitPolicy = RateLimitPolicy(maxRequests = 60, windowSeconds = 60),
    private val blobs: RateLimitPolicy = RateLimitPolicy(maxRequests = 600, windowSeconds = 60),
    private val limiter: FixedWindowRateLimiter = FixedWindowRateLimiter(),
) {
    enum class Budget(
        val keyPrefix: String,
    ) {
        REPOSITORIES("public-repo"),
        BLOBS("public-blob"),
    }

    /**
     * Runs [respond] with a download slot when [call]'s address has budget left. Otherwise answers
     * `429 RateLimitExceeded`, or `503 ServerBusy` when every slot is taken, both with `Retry-After`.
     */
    suspend fun withDownload(
        call: ApplicationCall,
        budget: Budget,
        respond: suspend () -> Unit,
    ) {
        val policy = if (budget == Budget.REPOSITORIES) repositories else blobs
        val key = "${budget.keyPrefix}:${call.clientAddress()}"
        if (!limiter.allow(key, policy)) {
            val retryAfter = limiter.retryAfterSeconds(key).coerceAtLeast(1)
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                PdsErrorResponse("RateLimitExceeded", "Too many downloads from this network. Try again in $retryAfter seconds."),
            )
            return
        }
        val slot = transfers.tryDownload() ?: return call.respondServerBusy(transfers.limits)
        try {
            respond()
        } finally {
            slot.release()
        }
    }
}

/** One repository in the response of `com.atproto.sync.listRepos`. */
@Serializable
internal data class ListedRepo(
    val did: AtprotoDid,
    val head: Cid,
    val rev: Tid,
    val active: Boolean,
)

/** The response of `com.atproto.sync.listRepos`. */
@Serializable
internal data class ListReposResponse(
    val repos: List<ListedRepo>,
    val cursor: String? = null,
)
