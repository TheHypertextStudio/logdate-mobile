package app.logdate.server.routes

import app.logdate.lexicon.app.logdate.crypto.Sealed
import app.logdate.lexicon.app.logdate.space.DeleteRecordOutput
import app.logdate.lexicon.app.logdate.space.GetRecordOutput
import app.logdate.lexicon.app.logdate.space.ListRepoOpsChange
import app.logdate.lexicon.app.logdate.space.ListRepoOpsOutput
import app.logdate.lexicon.app.logdate.space.PutRecordOutput
import app.logdate.server.auth.Account
import app.logdate.server.database.toJavaUUID
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.migration.SpaceMigrationHolds
import app.logdate.server.migration.SpaceWriteRefusal
import app.logdate.server.ratelimit.FixedWindowRateLimiter
import app.logdate.server.routes.docs.XrpcSpaceDocs
import app.logdate.server.space.MAX_SPACE_CHANGES_LIMIT
import app.logdate.server.space.SpaceCursorTooOldException
import app.logdate.server.space.SpaceInvalidRecordException
import app.logdate.server.space.SpaceMissingBlobException
import app.logdate.server.space.SpaceQuotaExceededException
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceSwapException
import app.logdate.server.space.checkSealedRecord
import app.logdate.server.transfer.TransferSlots
import app.logdate.space.SpaceRef
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.uuid.ExperimentalUuidApi

/** The largest blob a space accepts. */
internal const val MAX_SPACE_BLOB_BYTES: Long = 100L * 1024 * 1024

/** Room for the largest sealed record, 4,000,000 bytes of ciphertext written as base64, and the rest of its JSON. */
internal const val MAX_SPACE_RECORD_REQUEST_BYTES: Long = 6L * 1024 * 1024

/** A delete carries only a space URI, a key and a CID; anything this large isn't one. */
internal const val MAX_SPACE_DELETE_REQUEST_BYTES: Long = 16L * 1024

private const val DEFAULT_CHANGES_LIMIT = 200
private const val MAX_RECORD_BLOBS = 64

/** CIDs are stored in 255-character columns, so a longer value can't name anything. */
private const val MAX_CID_LENGTH = 255
private const val RECORD_NOT_FOUND = "RecordNotFound"
internal const val BLOB_NOT_FOUND = "BlobNotFound"
private const val CURSOR_TOO_OLD = "CursorTooOld"
private const val ACCOUNT_STORAGE_CHANGED = "AccountStorageChanged"

/** How long a device waits before sending a held space write again. */
private const val SPACE_HOLD_RETRY_AFTER_SECONDS = 60

private val spaceJson = Json { ignoreUnknownKeys = true }

/**
 * The body of `app.logdate.space.putRecord`. Its record stays JSON, so the server stores exactly
 * the envelope that was sent and refuses one with fields it doesn't know.
 */
@Serializable
internal data class SpacePutRecordInput(
    val space: String,
    val rkey: String,
    val record: JsonObject,
    val blobs: List<String>? = null,
    val swapCid: String? = null,
    val createOnly: Boolean? = null,
)

/** The body of `app.logdate.space.deleteRecord`. */
@Serializable
internal data class SpaceDeleteRecordInput(
    val space: String,
    val rkey: String,
    val swapCid: String? = null,
)

/**
 * The `app.logdate.space.*` XRPC methods. Each account reaches only its own personal space; any
 * other space, including another account's, answers exactly as a missing record or blob does, so
 * nobody learns whether it exists. Only the owner's own LogDate devices get in: [auth] accepts
 * LogDate access tokens and nothing else. With [enabled] off, or without a [store], every method
 * answers `501 MethodNotImplemented`; the blob methods also need [blobStorage]. Every method checks
 * the access token first, so a caller without one gets `401` whether or not the feature is on.
 * Blobs larger than [maxBlobBytes] answer `413 BlobTooLarge`.
 *
 * Each account's requests are counted against [rateLimits] by [rateLimiter]; past a budget, a method
 * answers `429 RateLimitExceeded` with `Retry-After`. With an [entitlementEnforcer], a record write
 * or blob upload that would take the account past its storage quota answers `402 QuotaExceeded`; the
 * store checks it in the same transaction as the write. Blob transfers past this instance's
 * [transfers] answer `503 ServerBusy` with `Retry-After`, without spending a budget.
 *
 * While a device downloads the space to switch the account back to legacy storage, writes answer
 * `503 ServerBusy` with `Retry-After`; once the account is back on legacy storage, a write from a
 * device that hasn't noticed answers `409 AccountStorageChanged` (see [migrationHolds]).
 */
fun Route.spaceXrpcRoutes(
    enabled: Boolean,
    store: SpaceStore?,
    blobStorage: LogDateBlobStorage?,
    auth: LogDateAccountAuth,
    entitlementEnforcer: EntitlementEnforcer? = null,
    rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(),
    rateLimits: SpaceRateLimits = SpaceRateLimits(),
    transfers: TransferSlots = TransferSlots(),
    maxBlobBytes: Long = MAX_SPACE_BLOB_BYTES,
    migrationHolds: SpaceMigrationHolds = SpaceMigrationHolds.None,
    objectUploads: SpaceObjectUploads = SpaceObjectUploads.Direct,
) {
    val records = store?.takeIf { enabled }
    val blobs = records?.let { recordStore -> blobStorage?.let { SpaceObjects(recordStore, it, transfers, objectUploads) } }
    val gate = SpaceGate(auth, rateLimiter, rateLimits, migrationHolds)
    route("/xrpc") {
        putRecord(records, gate, entitlementEnforcer)
        deleteRecord(records, gate)
        getRecord(records, gate)
        listRepoOps(records, gate)
        uploadBlob(blobs, gate, entitlementEnforcer, maxBlobBytes)
        getBlob(blobs, gate)
        getBlobUploadReceipt(blobs, gate)
    }
}

/** What every space method checks first: the caller's LogDate access token, then its request budget. */
internal class SpaceGate(
    private val auth: LogDateAccountAuth,
    private val limiter: FixedWindowRateLimiter,
    private val limits: SpaceRateLimits,
    private val holds: SpaceMigrationHolds = SpaceMigrationHolds.None,
) {
    /**
     * The calling account and [service], or null after answering: `401` without a valid LogDate access
     * token, `501` when [service] is missing because the feature is off, and `429` once the account has
     * spent its [budget]. A request the server can't perform never spends a budget.
     */
    suspend fun <T : Any> admit(
        call: ApplicationCall,
        service: T?,
        budget: SpaceBudget,
    ): Pair<Account, T>? {
        val admitted = authorize(call, service) ?: return null
        if (!spend(call, admitted.first, budget)) return null
        return admitted
    }

    /** The calling account and [service], or null after answering `401` or `501`; spends nothing. */
    suspend fun <T : Any> authorize(
        call: ApplicationCall,
        service: T?,
    ): Pair<Account, T>? {
        val account = auth.requireLogDateAccount(call) ?: return null
        if (service == null) {
            call.respondNotImplemented()
            return null
        }
        return account to service
    }

    /**
     * Whether [account] may write to its personal space now, or answers why not and returns false:
     * `503` while its writes are held to switch it back, `409` once it is back on legacy storage.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun writable(
        call: ApplicationCall,
        account: Account,
    ): Boolean {
        when (holds.spaceWriteRefusal(account.id.toJavaUUID(), account.storageMode)) {
            null -> {
                return true
            }

            SpaceWriteRefusal.Held -> {
                call.response.header(HttpHeaders.RetryAfter, SPACE_HOLD_RETRY_AFTER_SECONDS.toString())
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    PdsErrorResponse(SERVER_BUSY, "This journal is being switched back; send this change again later"),
                )
            }

            SpaceWriteRefusal.StorageChanged -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    PdsErrorResponse(ACCOUNT_STORAGE_CHANGED, "This account's journal is no longer kept in its personal space"),
                )
            }
        }
        return false
    }

    /** Counts one request against [account]'s [budget], or answers `429` and returns false. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun spend(
        call: ApplicationCall,
        account: Account,
        budget: SpaceBudget,
    ): Boolean = call.spendSpaceBudget(limiter, limits, budget, account.id.toString())
}

@OptIn(ExperimentalUuidApi::class)
private fun Route.putRecord(
    records: SpaceStore?,
    gate: SpaceGate,
    quota: EntitlementEnforcer?,
) {
    post("/app.logdate.space.putRecord", XrpcSpaceDocs.putRecord) {
        val (account, store) = gate.admit(call, records, SpaceBudget.RECORD_WRITES) ?: return@post
        if (!gate.writable(call, account)) return@post
        val bytes =
            call.receiveBytesUpTo(MAX_SPACE_RECORD_REQUEST_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, PdsErrorResponse("RecordTooLarge", "The record is too large"))
        val input = decodeOrNull<SpacePutRecordInput>(bytes) ?: return@post call.respondInvalid("Invalid putRecord input")
        val space = call.ownSpace(input.space, account, RECORD_NOT_FOUND) ?: return@post
        if (!isRecordKey(input.rkey)) return@post call.respondInvalid("rkey must be a record key")
        val blobs = input.blobs.orEmpty()
        if (blobs.size > MAX_RECORD_BLOBS) return@post call.respondInvalid("A record can refer to at most $MAX_RECORD_BLOBS blobs")
        if (!blobs.all(::isCid) ||
            input.swapCid?.let(::isCid) == false
        ) {
            return@post call.respondInvalid("blobs and swapCid must be CID-shaped")
        }
        checkSealedRecord(input.record)?.let { return@post call.respondInvalidRecord(it) }
        val write =
            SpaceRecordWrite(
                accountId = account.id.toJavaUUID(),
                space = space.uri,
                author = space.authority,
                rkey = input.rkey,
                record = input.record,
                blobs = blobs,
                swapCid = input.swapCid,
                createOnly = input.createOnly == true,
                quota = quota?.spaceQuota(account.id.toJavaUUID()),
                identityEpoch = account.identityEpoch,
            )
        val stored =
            try {
                store.put(write)
            } catch (e: IdentityWriteRefusedException) {
                val refused = blobFailureResponse(e)
                return@post call.respond(refused.status, refused.body)
            } catch (e: SpaceSwapException) {
                return@post call.respondSwapConflict(e)
            } catch (e: SpaceQuotaExceededException) {
                return@post call.respondSpaceQuotaExceeded(e)
            } catch (e: SpaceInvalidRecordException) {
                return@post call.respondInvalidRecord(e.reason)
            } catch (e: SpaceMissingBlobException) {
                return@post call.respondInvalidRecord(e.message ?: "The record refers to a missing blob")
            }
        call.respond(PutRecordOutput(cid = stored.cid, seq = stored.seq))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun Route.deleteRecord(
    records: SpaceStore?,
    gate: SpaceGate,
) {
    post("/app.logdate.space.deleteRecord", XrpcSpaceDocs.deleteRecord) {
        val (account, store) = gate.admit(call, records, SpaceBudget.RECORD_WRITES) ?: return@post
        if (!gate.writable(call, account)) return@post
        val bytes =
            call.receiveBytesUpTo(MAX_SPACE_DELETE_REQUEST_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, PdsErrorResponse("RecordTooLarge", "The request is too large"))
        val input = decodeOrNull<SpaceDeleteRecordInput>(bytes) ?: return@post call.respondInvalid("Invalid deleteRecord input")
        val space = call.ownSpace(input.space, account, RECORD_NOT_FOUND) ?: return@post
        if (!isRecordKey(input.rkey)) return@post call.respondInvalid("rkey must be a record key")
        if (input.swapCid?.let(::isCid) == false) return@post call.respondInvalid("swapCid must be CID-shaped")
        val seq =
            try {
                store.delete(account.id.toJavaUUID(), space.uri, space.authority, input.rkey, input.swapCid, account.identityEpoch)
            } catch (e: IdentityWriteRefusedException) {
                val refused = blobFailureResponse(e)
                return@post call.respond(refused.status, refused.body)
            } catch (e: SpaceSwapException) {
                return@post call.respondSwapConflict(e)
            }
        call.respond(DeleteRecordOutput(seq = seq))
    }
}

private fun Route.getRecord(
    records: SpaceStore?,
    gate: SpaceGate,
) {
    get("/app.logdate.space.getRecord", XrpcSpaceDocs.getRecord) {
        val (account, store) = gate.admit(call, records, SpaceBudget.READS) ?: return@get
        val space = call.ownSpace(call.request.queryParameters["space"], account, RECORD_NOT_FOUND) ?: return@get
        val rkey =
            call.request.queryParameters["rkey"]?.takeIf(::isRecordKey)
                ?: return@get call.respondInvalid("rkey must be a record key")
        val stored = store.get(space.uri, space.authority, rkey) ?: return@get call.respondMissing(RECORD_NOT_FOUND)
        call.respond(GetRecordOutput(cid = stored.cid, seq = stored.seq, value = stored.record.toSealed()))
    }
}

private fun Route.listRepoOps(
    records: SpaceStore?,
    gate: SpaceGate,
) {
    get("/app.logdate.space.listRepoOps", XrpcSpaceDocs.listRepoOps) {
        val (account, store) = gate.admit(call, records, SpaceBudget.READS) ?: return@get
        val space = call.ownSpace(call.request.queryParameters["space"], account, RECORD_NOT_FOUND) ?: return@get
        val since =
            call.request.queryParameters["since"]?.let { param ->
                param.toLongOrNull()?.takeIf { it >= 0 } ?: return@get call.respondInvalid("since must be a whole number, 0 or more")
            } ?: 0L
        val limit =
            call.request.queryParameters["limit"]?.let { param ->
                param.toIntOrNull()?.takeIf { it in 1..MAX_SPACE_CHANGES_LIMIT }
                    ?: return@get call.respondInvalid("limit must be a whole number from 1 to $MAX_SPACE_CHANGES_LIMIT")
            } ?: DEFAULT_CHANGES_LIMIT
        val page =
            try {
                store.changes(space.uri, space.authority, since, limit)
            } catch (_: SpaceCursorTooOldException) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse(CURSOR_TOO_OLD, "Deletions after this cursor were purged; list again from 0"),
                )
            }
        val ops =
            page.changes.map {
                ListRepoOpsChange(cid = it.cid, deleted = it.deleted, rkey = it.rkey, seq = it.seq, value = it.record?.toSealed())
            }
        call.respond(ListRepoOpsOutput(cursor = page.cursor, hasMore = page.hasMore, ops = ops))
    }
}

/**
 * The account's own personal space, named by [uri]. A URI that isn't a space is an invalid request;
 * any other space answers as missing with [notFoundCode], exactly as a missing record or blob does.
 */
internal suspend fun ApplicationCall.ownSpace(
    uri: String?,
    account: Account,
    notFoundCode: String,
): SpaceRef? {
    val space = uri?.let(SpaceRef::parse)
    if (space == null) {
        respondInvalid("space must be a space URI")
        return null
    }
    val did = account.did
    if (did == null || space != SpaceRef.personal(did)) {
        respondMissing(notFoundCode)
        return null
    }
    return space
}

/** Lowercase base32 multibase is all [Cid.parse] checks, so this is a shape check, not a full parse. */
internal fun isCid(value: String): Boolean = value.length <= MAX_CID_LENGTH && value == value.trim() && Cid.parse(value).isSuccess

private fun isRecordKey(value: String): Boolean = RecordKey.parse(value).isSuccess

/** Up to [limit] bytes of the request body, or null when it's longer. */
private suspend fun ApplicationCall.receiveBytesUpTo(limit: Long): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) return null
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    return bytes.takeIf { it.size <= limit }
}

private inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
    try {
        spaceJson.decodeFromString<T>(bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/** Stored records passed [checkSealedRecord] on the way in, so they always decode. */
private fun JsonObject.toSealed(): Sealed = spaceJson.decodeFromJsonElement(this)

private suspend fun ApplicationCall.respondNotImplemented() =
    respond(HttpStatusCode.NotImplemented, PdsErrorResponse("MethodNotImplemented", "Personal spaces aren't enabled on this server"))

internal suspend fun ApplicationCall.respondMissing(code: String) = respond(HttpStatusCode.BadRequest, PdsErrorResponse(code, "Not found"))

internal suspend fun ApplicationCall.respondInvalid(message: String) =
    respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", message))

private suspend fun ApplicationCall.respondInvalidRecord(reason: String) =
    respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRecord", reason))

private suspend fun ApplicationCall.respondSwapConflict(error: SpaceSwapException) =
    respond(HttpStatusCode.Conflict, PdsErrorResponse("InvalidSwap", error.message ?: "The record changed"))
