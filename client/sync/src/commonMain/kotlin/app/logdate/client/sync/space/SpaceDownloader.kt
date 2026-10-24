package app.logdate.client.sync.space

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.WireDownload
import app.logdate.lexicon.LogDateSchemas
import app.logdate.lexicon.app.logdate.crypto.Sealed
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.space.ChangePage
import app.logdate.space.DataHomeCursorTooOldException
import app.logdate.space.SealedRecordCodec
import app.logdate.space.SealedRecordException
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.lexicon.LexiconRecordValidator
import studio.hypertext.atproto.syntax.Nsid

/**
 * Reads the personal space's change feed into the download inbox.
 *
 * The feed carries every kind of data under one cursor, the `SPACE` checkpoint. Each change is
 * opened, checked against its schema, and staged as its own row under the kind that owns its record
 * type, where [SpaceStagedRecords] joins it with the item's other records for that kind's download
 * path. A change that can't be opened or checked is staged under [SPACE_UNREADABLE] with the reason,
 * and opened again once this device holds another key or runs a build that reads more, so it is
 * never dropped.
 *
 * A deletion is never lost: it is staged for a record this device applied, for one still waiting to
 * be applied, and it settles one this device couldn't read, so none of them comes back.
 */
internal class SpaceDownloader(
    private val registry: SpaceRecordRegistry,
    private val swapStore: SpaceSwapStore,
    private val inbox: DownloadInbox,
    private val validator: LexiconRecordValidator,
    private val rateLimit: SpaceRateLimit,
    private val pageSize: Int = PAGE_SIZE,
) {
    /** What this build can read: when it changes, records kept as unreadable are opened again. */
    private val reader: String by lazy {
        "envelope=$SEALED_ENVELOPE_VERSION;types=${registry.collections.sorted().joinToString(",")};" +
            "schemas=${LogDateSchemas.documents.hashCode()}"
    }

    suspend fun pull(
        access: SpaceAccess,
        client: SpaceClient,
    ): Result<Unit> {
        val selected = inbox.currentScope()
        rateLimit.refusal()?.let { return fetchFailed(it, selected) }
        retryUnreadable(access, selected)
        var cursor = inbox.cursor(SPACE_CHECKPOINT, selected)
        while (true) {
            val page =
                client.call("listChanges") { listChanges(access.space, cursor, pageSize) }.getOrElse { error ->
                    if (error is DataHomeCursorTooOldException) return reconcile(access, client, selected)
                    return fetchFailed(error, selected)
                }
            stagePage(access, selected, page)
            if (!page.hasMore) return fetched(selected)
            if (page.cursor <= cursor) return fetchFailed(stalled(), selected)
            cursor = page.cursor
        }
    }

    /**
     * The space purged deletions this device hadn't seen, so the feed can't say what was removed.
     * List everything from zero, stage it, and stage a deletion for every record this device knows
     * that the listing no longer has.
     */
    private suspend fun reconcile(
        access: SpaceAccess,
        client: SpaceClient,
        selected: DownloadScope,
    ): Result<Unit> {
        Napier.w("The personal space purged deletions since the last download; listing everything again")
        inbox.resetCheckpoint(SPACE_CHECKPOINT, selected)
        val live = mutableSetOf<String>()
        var cursor = 0L
        while (true) {
            val page =
                client
                    .call(
                        "listChanges",
                    ) { listChanges(access.space, cursor, pageSize) }
                    .getOrElse { return fetchFailed(it, selected) }
            page.changes.forEach { change -> if (change.deleted) live -= change.rkey else live += change.rkey }
            stagePage(access, selected, page)
            if (!page.hasMore) {
                stageMissing(access, selected, live, page.cursor)
                return fetched(selected)
            }
            if (page.cursor <= cursor) return fetchFailed(stalled(), selected)
            cursor = page.cursor
        }
    }

    private suspend fun stageMissing(
        access: SpaceAccess,
        selected: DownloadScope,
        live: Set<String>,
        cursor: Long,
    ) {
        val rows = mutableMapOf<String, MutableList<WireDownload>>()
        val known = mutableMapOf<String, Pair<String, SpaceInboxPayload>>()
        for (state in swapStore.all(access.scope)) {
            val owned = registry.forCollection(state.collection) ?: continue
            known[state.sealedRkey] =
                spacePartsType(owned.kind.entityType) to SpaceInboxPayload(state.collection, state.rkey, state.sealedRkey)
        }
        val versions = mutableMapOf<String, Long>()
        for (kind in registry.kinds) {
            val type = spacePartsType(kind.entityType)
            for (row in inbox.all(type, selected)) {
                versions[row.entityId] = maxOf(versions[row.entityId] ?: 0, row.version)
                if (row.deleted) continue
                val payload = SpaceInboxPayload.decode(row.payload) ?: continue
                known.getOrPut(row.entityId) { type to payload.withoutContent() }
            }
        }
        for ((sealedRkey, target) in known) {
            if (sealedRkey in live) continue
            val (type, payload) = target
            // Newer than any version this device staged for the record, so the deletion isn't skipped.
            val version = maxOf(cursor, (versions[sealedRkey] ?: 0) + 1)
            rows.getOrPut(type) { mutableListOf() } += WireDownload(sealedRkey, version, true, payload.copy(cid = null).encode())
        }
        inbox.stageAll(SPACE_CHECKPOINT, cursor, rows, selected)
        for (row in inbox.retained(SPACE_UNREADABLE, selected)) {
            if (row.entityId !in live) inbox.skipped(SPACE_UNREADABLE, row.entityId, row.version, selected)
        }
    }

    private suspend fun stagePage(
        access: SpaceAccess,
        selected: DownloadScope,
        page: ChangePage,
    ) {
        val rows = mutableMapOf<String, MutableList<WireDownload>>()
        val unreadable = mutableListOf<Pair<WireDownload, DiagnosticReason>>()
        val settled = mutableListOf<String>()
        for (change in page.changes.associateBy { it.rkey }.values) {
            if (change.deleted) {
                known(access, selected, change.rkey)?.let { (type, payload) ->
                    rows.getOrPut(type) { mutableListOf() } +=
                        WireDownload(change.rkey, change.seq, true, payload.copy(cid = null).encode())
                }
                // Nothing this device couldn't read may come back once the record is gone.
                settled += change.rkey
                continue
            }
            // This device wrote or already applied exactly this version.
            if (swapStore.bySealedRkey(access.scope, change.rkey)?.cid == change.cid) continue
            when (val read = read(access, change.rkey, change.cid, change.record)) {
                is Read.Readable -> {
                    rows.getOrPut(spacePartsType(read.owned.kind.entityType)) { mutableListOf() } +=
                        WireDownload(change.rkey, change.seq, false, read.payload.encode())
                    settled += change.rkey
                }

                is Read.Unreadable -> {
                    unreadable += WireDownload(change.rkey, change.seq, false, read.retained) to read.reason
                }
            }
        }
        if (unreadable.isNotEmpty()) rows[SPACE_UNREADABLE] = unreadable.map { it.first }.toMutableList()
        inbox.stageAll(SPACE_CHECKPOINT, page.cursor, rows, selected)
        for ((row, reason) in unreadable) {
            inbox.failed(SPACE_UNREADABLE, row.id, reason.name, row.version, selected)
        }
        for (sealedRkey in settled) settleUnreadable(sealedRkey, selected)
    }

    /**
     * Which record [sealedRkey] names and the inbox type it is staged under, when this device applied
     * it or has it staged; null when this device never read it.
     */
    private suspend fun known(
        access: SpaceAccess,
        selected: DownloadScope,
        sealedRkey: String,
    ): Pair<String, SpaceInboxPayload>? {
        swapStore.bySealedRkey(access.scope, sealedRkey)?.let { state ->
            val owned = registry.forCollection(state.collection) ?: return null
            return spacePartsType(owned.kind.entityType) to SpaceInboxPayload(state.collection, state.rkey, sealedRkey)
        }
        for (kind in registry.kinds) {
            val type = spacePartsType(kind.entityType)
            val row = inbox.row(type, sealedRkey, selected) ?: continue
            val payload = SpaceInboxPayload.decode(row.payload) ?: continue
            return type to payload.withoutContent()
        }
        return null
    }

    /** Opens again the changes earlier pulls couldn't read, once this device holds another key or reads more. */
    private suspend fun retryUnreadable(
        access: SpaceAccess,
        selected: DownloadScope,
    ) {
        for (row in inbox.pending(SPACE_UNREADABLE, selected)) {
            val retained = SpaceUnreadablePayload.decode(row.payload)
            if (retained == null) {
                inbox.failed(SPACE_UNREADABLE, row.entityId, DiagnosticReason.CORRUPT_PAYLOAD.name, row.version, selected)
                continue
            }
            if (retained.triedKeyId == access.keys.keyId && retained.triedReader == reader) continue
            when (val read = read(access, retained.sealedRkey, retained.cid, retained.record)) {
                is Read.Readable -> {
                    val type = spacePartsType(read.owned.kind.entityType)
                    val staged = WireDownload(retained.sealedRkey, row.version, false, read.payload.encode())
                    inbox.stageAll(SPACE_CHECKPOINT, 0, mapOf(type to listOf(staged)), selected)
                    inbox.skipped(SPACE_UNREADABLE, row.entityId, row.version, selected)
                }

                is Read.Unreadable -> {
                    // Record what was tried, so it isn't opened again until something changes.
                    inbox.replacePayload(SPACE_UNREADABLE, row.entityId, row.version, read.retained, selected)
                    inbox.failed(SPACE_UNREADABLE, row.entityId, read.reason.name, row.version, selected)
                }
            }
        }
    }

    /** A readable version of a record supersedes an earlier unreadable one, and a deletion ends it. */
    private suspend fun settleUnreadable(
        sealedRkey: String,
        selected: DownloadScope,
    ) {
        val row = inbox.row(SPACE_UNREADABLE, sealedRkey, selected) ?: return
        if (row.state == "APPLIED") return
        inbox.skipped(SPACE_UNREADABLE, sealedRkey, row.version, selected)
    }

    private suspend fun read(
        access: SpaceAccess,
        sealedRkey: String,
        cid: String?,
        envelope: Sealed?,
    ): Read {
        if (envelope == null || cid == null) return Read.Unreadable(DiagnosticReason.CORRUPT_PAYLOAD, "")

        fun unreadable(reason: DiagnosticReason) =
            Read.Unreadable(reason, SpaceUnreadablePayload(sealedRkey, cid, envelope, access.keys.keyId, reader).encode())

        if (envelope.version != SEALED_ENVELOPE_VERSION) return unreadable(DiagnosticReason.UNSUPPORTED_FORMAT)
        if (envelope.keyId != access.keys.keyId) return unreadable(DiagnosticReason.KEY_RECOVERY_REQUIRED)
        val payload =
            try {
                SealedRecordCodec.open(access.keys, access.space, access.author, sealedRkey, envelope)
            } catch (_: SealedRecordException) {
                return unreadable(DiagnosticReason.CORRUPT_PAYLOAD)
            }
        val owned = registry.forCollection(payload.collection) ?: return unreadable(DiagnosticReason.UNSUPPORTED_FORMAT)
        val record = payload.record as? JsonObject ?: return unreadable(DiagnosticReason.CORRUPT_PAYLOAD)
        val issues = validator.validateRecord(Nsid.require(payload.collection), record).issues
        if (issues.isNotEmpty()) {
            // Paths only: an issue's message can quote the record's contents.
            Napier.w("A ${payload.collection} record failed its schema at ${issues.joinToString { it.path.ifEmpty { "(record)" } }}")
            return unreadable(DiagnosticReason.CORRUPT_PAYLOAD)
        }
        return Read.Readable(owned, SpaceInboxPayload(payload.collection, canonicalRecordKey(payload.rkey), sealedRkey, cid, record))
    }

    private suspend fun fetched(selected: DownloadScope): Result<Unit> {
        inbox.fetchFailed(SPACE_CHECKPOINT, DiagnosticReason.NONE, selected)
        return Result.success(Unit)
    }

    private suspend fun fetchFailed(
        error: Throwable,
        selected: DownloadScope,
    ): Result<Unit> {
        rateLimit.record(error)
        val failure = error as? CloudApiException
        val reason =
            when {
                failure?.statusCode == 401 || failure?.statusCode == 403 -> DiagnosticReason.SIGN_IN_REQUIRED
                failure?.statusCode == RATE_LIMITED -> DiagnosticReason.RATE_LIMITED
                failure?.errorCode == "NETWORK_ERROR" -> DiagnosticReason.OFFLINE
                failure?.errorCode == STORAGE_QUOTA_EXCEEDED -> DiagnosticReason.QUOTA_EXCEEDED
                else -> DiagnosticReason.SERVER_UNAVAILABLE
            }
        inbox.fetchFailed(SPACE_CHECKPOINT, reason, selected)
        return Result.failure(error)
    }

    private fun stalled() = CloudApiException("SPACE_CURSOR_STALLED", "The personal space's change feed didn't advance")

    private sealed interface Read {
        class Readable(
            val owned: SpaceOwnedPart,
            val payload: SpaceInboxPayload,
        ) : Read

        class Unreadable(
            val reason: DiagnosticReason,
            val retained: String,
        ) : Read
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val RATE_LIMITED = 429

        /** The only sealed envelope format this version reads. */
        const val SEALED_ENVELOPE_VERSION = 1L
    }
}
