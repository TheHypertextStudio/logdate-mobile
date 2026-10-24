package app.logdate.client.sync.space

import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncRetryCoordinator
import app.logdate.client.sync.beginAttempt
import app.logdate.client.sync.conflict.ConflictResolution
import app.logdate.client.sync.conflict.DeletionResolution
import app.logdate.client.sync.dueNow
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.lexicon.app.logdate.crypto.SealedPayload
import app.logdate.space.DataHomeConflictException
import app.logdate.space.RecordSwap
import app.logdate.space.SealedRecordCodec
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.lexicon.LexiconRecordValidator
import studio.hypertext.atproto.syntax.Nsid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Uploads one kind's outbox entries to the personal space as sealed records, every record of each
 * item.
 *
 * A record is created only if absent, and replaced only if it still has the content identifier
 * this device last saw. When any record of an item changed elsewhere, the uploader reads all of
 * them, runs the kind's conflict resolver on the item they make, and writes again over the versions
 * it just read. A failure is the entry's alone: the rest of the outbox keeps uploading, and every
 * outcome settles or retries the entry through [SyncRetryCoordinator], as the per-kind uploads do.
 * Once the server refuses for rate limiting, the pass stops and the rest waits for its `Retry-After`.
 */
internal class SpaceUploader(
    private val swapStore: SpaceSwapStore,
    private val syncMetadataService: SyncMetadataService,
    private val retryCoordinator: SyncRetryCoordinator,
    private val validator: LexiconRecordValidator,
    private val rateLimit: SpaceRateLimit,
    private val recordProgress: (Int) -> Unit,
) {
    suspend fun <T : Any> upload(
        kind: SpaceKind<T>,
        access: SpaceAccess,
        client: SpaceClient,
        uploadsUpdates: Boolean,
        begin: (suspend (PendingUpload, T, MutableList<SyncError>) -> T?)?,
        afterDelete: suspend (PendingUpload) -> Unit,
    ): SyncResult {
        val type = kind.entityType
        val errors = mutableListOf<SyncError>()
        var uploaded = 0
        rateLimit.refusal()?.let { return SyncResult(success = false, errors = listOf(rateLimited(it))) }
        for (pending in syncMetadataService.getPendingUploads(type).dueNow(type, retryCoordinator)) {
            if (rateLimit.refusal() != null) break
            // An entry queued for another account or server waits until that one is signed in again.
            if (pending.scope != null &&
                (pending.scope.serverOrigin != access.scope.serverOrigin || !sameOwnerId(pending.scope.ownerId, access.scope.ownerId))
            ) {
                continue
            }
            val upload = Upload(kind, access, client, pending, errors)
            try {
                if (pending.operation == PendingOperation.DELETE) {
                    if (!retryCoordinator.beginAttempt(type, pending, errors)) continue
                    uploaded += upload.delete(afterDelete)
                    continue
                }
                if (pending.operation == PendingOperation.UPDATE && !uploadsUpdates) continue
                val item = kind.local.load(pending.entityId)
                if (item == null) {
                    errors += retryCoordinator.recordUnavailableOutboxEntry(type, pending)
                    continue
                }
                val ready =
                    if (begin != null) {
                        begin(pending, item, errors) ?: continue
                    } else {
                        if (!retryCoordinator.beginAttempt(type, pending, errors)) continue
                        item
                    }
                uploaded += upload.write(ready)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                upload.fail(error)
            }
        }
        return SyncResult(success = errors.isEmpty(), uploadedItems = uploaded, errors = errors)
    }

    private fun rateLimited(refusal: Throwable) =
        SyncError(
            SyncErrorType.SERVER_ERROR,
            "Personal space upload is waiting for the server's rate limit to lift",
            refusal,
            retryable = true,
        )

    /** The records the space holds for an item, and the item they make, when it still has its main record. */
    private class Remote<T>(
        val item: T?,
        val states: Map<RecordKey, SpaceRecordState>,
        val latestSeq: Long,
    )

    private data class RecordKey(
        val collection: String,
        val rkey: String,
    )

    /** One outbox entry's attempt. */
    private inner class Upload<T : Any>(
        private val kind: SpaceKind<T>,
        private val access: SpaceAccess,
        private val client: SpaceClient,
        private val pending: PendingUpload,
        private val errors: MutableList<SyncError>,
    ) {
        private val type = kind.entityType
        private val entityId = canonicalRecordKey(pending.entityId)

        suspend fun write(item: T): Int {
            var current = item
            var read: Map<RecordKey, SpaceRecordState>? = null
            repeat(MAX_WRITE_ATTEMPTS) {
                val records = recordsFor(current) ?: return 0
                val bases = read ?: knownStates(records)
                when (val written = writeAll(records, bases)) {
                    is Attempt.Succeeded -> {
                        kind.local.acknowledgeUpload(current, written.seq, Clock.System.now())
                        retryCoordinator.markUploadSettled(type, pending, Clock.System.now(), written.seq)
                        recordProgress(1)
                        return 1
                    }

                    is Attempt.Failed -> {
                        return fail(written.error)
                    }

                    is Attempt.Conflicted -> {
                        when (val next = resolve(current, written.conflict)) {
                            is Next.Write -> {
                                current = next.item
                                read = next.bases
                            }

                            is Next.Done -> {
                                return 0
                            }
                        }
                    }
                }
            }
            return fail(DataHomeConflictException("The item kept changing on other devices"))
        }

        suspend fun delete(afterDelete: suspend (PendingUpload) -> Unit): Int {
            var bases = knownStates(outgoing = null)
            repeat(MAX_WRITE_ATTEMPTS) {
                if (bases.isEmpty()) {
                    // This device never saw a version of it, so anything there is an edit it hasn't seen.
                    val remote = readRemote().getOrElse { return fail(it) }
                    if (remote.states.isEmpty()) return deleted(0, afterDelete)
                    if (remote.item != null && keepsEdit(remote)) return keepEdit(remote, DataHomeConflictException("Edited elsewhere"))
                    bases = remote.states
                }
                when (val removed = deleteAll(bases)) {
                    is Attempt.Succeeded -> {
                        return deleted(removed.seq, afterDelete)
                    }

                    is Attempt.Failed -> {
                        return fail(removed.error)
                    }

                    is Attempt.Conflicted -> {
                        val remote = readRemote().getOrElse { return fail(it) }
                        if (remote.item != null && keepsEdit(remote)) return keepEdit(remote, removed.conflict)
                        if (remote.states.isEmpty()) return deleted(0, afterDelete)
                        bases = remote.states
                    }
                }
            }
            return fail(DataHomeConflictException("The item kept changing on other devices"))
        }

        private fun keepsEdit(remote: Remote<T>): Boolean {
            val item = remote.item ?: return false
            return kind.local.conflictResolver.resolveDeletion(item, kind.local.lastUpdatedOf(item)) == DeletionResolution.KEEP_EDIT
        }

        /** The deletion met an edit made elsewhere: the edit comes back here and the conflict is listed. */
        private suspend fun keepEdit(
            remote: Remote<T>,
            conflict: DataHomeConflictException,
        ): Int {
            val item = requireNotNull(remote.item)
            kind.local.replaceLocal(item)
            remote.states.values.forEach { swapStore.put(access.scope, it) }
            kind.local.acknowledgeUpload(item, remote.latestSeq, Clock.System.now())
            errors += retryCoordinator.handleUploadConflict(type, pending, conflict, remote.latestSeq, kind.local.lastUpdatedOf(item))
            return 0
        }

        private suspend fun deleted(
            seq: Long,
            afterDelete: suspend (PendingUpload) -> Unit,
        ): Int {
            forgetItem()
            afterDelete(pending)
            retryCoordinator.markUploadSettled(type, pending, Clock.System.now(), seq)
            recordProgress(1)
            return 1
        }

        /** The versions this device last saw of every record the item has or can have. */
        private suspend fun knownStates(outgoing: List<SpaceOutgoing>?): Map<RecordKey, SpaceRecordState> {
            val keys = kind.parts.flatMap { part -> part.rkeysOf(entityId).map { RecordKey(part.collection, canonicalRecordKey(it)) } }
            val all = keys + outgoing.orEmpty().map { RecordKey(it.collection, it.rkey) }
            return all.distinct().mapNotNull { key -> swapStore.get(access.scope, key.collection, key.rkey)?.let { key to it } }.toMap()
        }

        /**
         * Every record [item] is stored as, checked against its schema, or null once the entry is
         * failed: retried when the item can map later as it is, set aside when it can't.
         */
        private suspend fun recordsFor(item: T): List<SpaceOutgoing>? =
            try {
                kind.outgoingRecords(item, validator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SpaceRecordMappingException) {
                fail(error)
                null
            } catch (error: SpaceRecordInvalidException) {
                fail(error)
                null
            } catch (error: Exception) {
                // A mapper that throws anything else has a bug no retry fixes.
                fail(SpaceRecordMappingException("The ${type.name.lowercase()} couldn't be converted to records", error))
                null
            }

        /**
         * Writes every record over the version in [bases] and deletes the records [bases] holds that
         * the item no longer has. Its main record goes first, so another device never sees the
         * item's other records before the item.
         */
        private suspend fun writeAll(
            records: List<SpaceOutgoing>,
            bases: Map<RecordKey, SpaceRecordState>,
        ): Attempt {
            var seq = 0L
            for (record in records) {
                val sealed =
                    SealedRecordCodec.seal(
                        access.keys,
                        access.space,
                        access.author,
                        SealedPayload(record.collection, record.record, record.rkey),
                    )
                val base = bases[RecordKey(record.collection, record.rkey)]
                val swap = base?.takeIf { it.sealedRkey == sealed.rkey }?.let { RecordSwap.Matching(it.cid) } ?: RecordSwap.Absent
                val written =
                    client.call("putRecord(${record.collection})") {
                        putRecord(access.space, sealed.rkey, sealed.record, record.blobs, swap)
                    }
                val stored = written.getOrElse { return attemptFailed(it) }
                swapStore.put(access.scope, SpaceRecordState(record.collection, record.rkey, sealed.rkey, stored.cid, stored.seq))
                seq = maxOf(seq, stored.seq)
                if (base != null && base.sealedRkey != sealed.rkey) {
                    // Another client wrote the key in another case; the lowercase record replaces it.
                    client
                        .call(
                            "deleteRecord(${record.collection})",
                        ) { deleteRecord(access.space, base.sealedRkey, RecordSwap.Matching(base.cid)) }
                        .getOrElse { return attemptFailed(it) }
                }
            }
            val kept = records.map { RecordKey(it.collection, it.rkey) }.toSet()
            val stale = bases.filterKeys { it !in kept }
            return when (val removed = deleteAll(stale)) {
                is Attempt.Succeeded -> Attempt.Succeeded(maxOf(seq, removed.seq))
                else -> removed
            }
        }

        private suspend fun deleteAll(states: Map<RecordKey, SpaceRecordState>): Attempt {
            var seq = 0L
            for ((key, state) in states) {
                val deleted =
                    client.call("deleteRecord(${key.collection})") {
                        deleteRecord(access.space, state.sealedRkey, RecordSwap.Matching(state.cid))
                    }
                seq = maxOf(seq, deleted.getOrElse { return attemptFailed(it) })
                swapStore.delete(access.scope, key.collection, key.rkey)
            }
            return Attempt.Succeeded(seq)
        }

        private fun attemptFailed(error: Throwable): Attempt =
            if (error is DataHomeConflictException) Attempt.Conflicted(error) else Attempt.Failed(error)

        /** Reads the item's records as other devices left them and lets the kind's resolver decide what to write. */
        private suspend fun resolve(
            local: T,
            conflict: DataHomeConflictException,
        ): Next<T> {
            val remote = readRemote().getOrElse { return Next.Done(fail(it)) }
            val remoteItem = remote.item
            if (remoteItem == null) {
                // Deleted elsewhere while this device edited it: kept here, and listed for review.
                forgetItem()
                errors += retryCoordinator.handleUploadConflict(type, pending, conflict, 0L, kind.local.lastUpdatedOf(local))
                return Next.Done(0)
            }
            val resolution =
                kind.local.conflictResolver.resolve(
                    local = local,
                    remote = remoteItem,
                    localTimestamp = kind.local.lastUpdatedOf(local),
                    remoteTimestamp = kind.local.lastUpdatedOf(remoteItem),
                )
            return when (resolution) {
                is ConflictResolution.KeepLocal -> {
                    Next.Write(resolution.value, remote.states)
                }

                is ConflictResolution.Merge -> {
                    kind.local.replaceLocal(resolution.merged)
                    Next.Write(resolution.merged, remote.states)
                }

                is ConflictResolution.KeepRemote -> {
                    kind.local.replaceLocal(resolution.value)
                    remote.states.values.forEach { swapStore.put(access.scope, it) }
                    kind.local.acknowledgeUpload(resolution.value, remote.latestSeq, Clock.System.now())
                    retryCoordinator.markUploadSettled(type, pending, Clock.System.now(), remote.latestSeq)
                    Next.Done(0)
                }

                is ConflictResolution.RequiresManualResolution -> {
                    errors +=
                        retryCoordinator.handleUploadConflict(type, pending, conflict, remote.latestSeq, kind.local.lastUpdatedOf(local))
                    Next.Done(0)
                }
            }
        }

        /** Every record of the item the space holds, opened and checked, and the item they make. */
        private suspend fun readRemote(): Result<Remote<T>> {
            val states = mutableMapOf<RecordKey, SpaceRecordState>()
            val changes = mutableMapOf<String, MutableList<SpacePartChange<*>>>()
            for (part in kind.parts) {
                for (rkey in part.rkeysOf(entityId).map(::canonicalRecordKey).distinct()) {
                    val sealedRkey = SealedRecordCodec.sealedRkey(access.keys, part.collection, rkey)
                    val stored =
                        client
                            .call(
                                "getRecord(${part.collection})",
                            ) { getRecord(access.space, sealedRkey) }
                            .getOrElse { return Result.failure(it) }
                            ?: continue
                    val payload =
                        try {
                            SealedRecordCodec.open(access.keys, access.space, access.author, stored.rkey, stored.record)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            return Result.failure(error)
                        }
                    val record = payload.record as? JsonObject
                    if (payload.collection != part.collection ||
                        !sameRecordKey(payload.rkey, rkey) ||
                        record == null ||
                        issues(part.collection, record)
                    ) {
                        return Result.failure(SpaceRecordInvalidException("The stored ${part.collection} record doesn't match its schema"))
                    }
                    val value =
                        try {
                            part.mapper.fromRecord(rkey, record)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            return Result.failure(error)
                        }
                    states[RecordKey(part.collection, rkey)] = SpaceRecordState(part.collection, rkey, sealedRkey, stored.cid, stored.seq)
                    changes.getOrPut(part.collection) { mutableListOf() } += SpacePartChange.Written(rkey, value, stored.seq)
                }
            }
            val latest = states.values.maxOfOrNull { it.seq } ?: 0L
            if (changes[kind.main.collection] == null) return Result.success(Remote(null, states, latest))
            val item =
                try {
                    kind.assemble(SpaceItemChanges(entityId, changes), local = null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return Result.failure(error)
                }
            return Result.success(Remote(item, states, latest))
        }

        private fun issues(
            collection: String,
            record: JsonObject,
        ) = validator.validateRecord(Nsid.require(collection), record).issues.isNotEmpty()

        private suspend fun forgetItem() {
            for (part in kind.parts) {
                part.rkeysOf(entityId).forEach { swapStore.delete(access.scope, part.collection, canonicalRecordKey(it)) }
            }
        }

        suspend fun fail(error: Throwable): Int {
            rateLimit.record(error)
            val movedToDeadLetter = retryCoordinator.handleRetryFailure(type, pending, error, permanent = error.isPermanentSpaceFailure())
            val retryable = !movedToDeadLetter && !error.isPermanentSpaceFailure()
            errors += SyncError(error.spaceErrorType(), "Failed to upload $type to the personal space: ${error.message}", error, retryable)
            Napier.w("Personal space upload failed")
            return 0
        }
    }

    private sealed interface Attempt {
        class Succeeded(
            val seq: Long,
        ) : Attempt

        class Conflicted(
            val conflict: DataHomeConflictException,
        ) : Attempt

        class Failed(
            val error: Throwable,
        ) : Attempt
    }

    private sealed interface Next<out T> {
        class Write<T>(
            val item: T,
            val bases: Map<RecordKey, SpaceRecordState>,
        ) : Next<T>

        class Done(
            val uploaded: Int,
        ) : Next<Nothing>
    }

    private companion object {
        /** Writes before giving up on an item other devices keep changing; the entry is retried later. */
        const val MAX_WRITE_ATTEMPTS = 3
    }
}
