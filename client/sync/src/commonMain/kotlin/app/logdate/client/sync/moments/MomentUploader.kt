package app.logdate.client.sync.moments

import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.client.repository.moments.atMillisecondPrecision
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncProfile
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncRetryCoordinator
import app.logdate.client.sync.SyncTokenRefresher
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.beginAttempt
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.ConditionalCreateResult
import app.logdate.client.sync.cloud.SyncUploadResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.SpaceSync
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class MomentUploader(
    private val config: MomentSyncConfig,
    private val metadata: SyncMetadataService,
    private val transactions: SyncTransactionManager,
    private val tokens: SyncTokenRefresher,
    private val retries: SyncRetryCoordinator,
    private val profile: SyncProfile,
    private val device: () -> DeviceId,
    private val inbox: DownloadInbox,
    private val space: SpaceSync? = null,
) {
    suspend fun upload(): SyncResult {
        val kind = space?.activeKind(TYPE)
        if (space != null && kind != null) return space.uploadKind(kind, profile.uploadsUpdates)
        if (!config.enabled()) return SyncResult(success = true)
        val errors = mutableListOf<SyncError>()
        var uploaded = 0
        try {
            for (pending in metadata.getPendingUploads(TYPE)) {
                if (!config.enabled()) break
                if (settleDownloadedDeletion(pending)) continue
                if (!retries.shouldAttempt(TYPE, pending)) continue
                if (pending.operation == PendingOperation.UPDATE && !profile.uploadsUpdates) continue
                if (!retries.beginAttempt(TYPE, pending, errors)) continue
                try {
                    ensureScope(pending)
                    if (send(pending)) uploaded++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    retries.handleRetryFailure(TYPE, pending, error)
                    errors += SyncError(SyncErrorType.SERVER_ERROR, "A marked moment remains queued for upload")
                }
            }
        } finally {
            retries.abandonAttemptsInFlight()
        }
        return SyncResult(success = errors.isEmpty(), uploadedItems = uploaded, errors = errors)
    }

    private suspend fun settleDownloadedDeletion(pending: PendingUpload): Boolean {
        val request = tokens.currentRequestScope() ?: return false
        if (request != pending.scope) return false
        val selected = DownloadScope(request.ownerId, request.serverOrigin)
        val version = inbox.appliedDeletionVersion(TYPE.name, pending.entityId, selected) ?: return false
        val settled =
            transactions.withTransaction {
                ensureScope(pending)
                if (inbox.appliedDeletionVersion(TYPE.name, pending.entityId, selected) != version) return@withTransaction null
                val result = metadata.settleIfCurrent(TYPE, pending, Clock.System.now(), version)
                ensureScope(pending)
                result
            } ?: return false
        retries.finishPersistedSettlement(TYPE, pending, settled)
        return true
    }

    private suspend fun send(pending: PendingUpload): Boolean {
        val id = Uuid.parse(pending.entityId)
        if (pending.operation == PendingOperation.DELETE) return sendDeletion(pending, id)
        var mark = config.repository.getMark(id) ?: error("Queued marked moment has no local body")
        if (pending.expectedServerVersion != null) mark = mark.copy(syncVersion = pending.expectedServerVersion)
        if (pending.operation == PendingOperation.CREATE || mark.syncVersion <= 0) {
            when (val created = request(pending) { config.cloud.createIfAbsent(it, mark, device()) }) {
                is ConditionalCreateResult.Created -> {
                    acknowledge(pending, mark, SyncUploadResult(created.serverVersion, created.syncedAt))
                    return true
                }

                is ConditionalCreateResult.AlreadyExists -> {
                    require(created.serverVersion > 0)
                    val remote = request(pending) { config.cloud.get(it, id) }.atMillisecondPrecision()
                    require(remote.id == id && remote.syncVersion >= created.serverVersion)
                    mark = mergeRemote(pending, remote)
                    if (sameFields(mark, remote)) {
                        acknowledge(pending, mark, SyncUploadResult(remote.syncVersion, Clock.System.now()))
                        return true
                    }
                    if (!profile.uploadsUpdates) {
                        transactions.withTransaction {
                            ensureScope(pending)
                            metadata.bindCreateToServerVersion(TYPE, pending, remote.syncVersion)
                            ensureScope(pending)
                        }
                        retries.releaseAttempt(TYPE, pending)
                        return false
                    }
                }

                is ConditionalCreateResult.Deleted -> {
                    error("Deleted capture awaits its versioned download tombstone")
                }
            }
        }
        check(profile.uploadsUpdates) { "Watch edits must be relayed by the phone" }
        repeat(MAX_ATTEMPTS) { attempt ->
            ensureScope(pending)
            if (!metadata.isCurrentOperation(TYPE, pending)) {
                retries.releaseAttempt(TYPE, pending)
                return false
            }
            try {
                val receipt = request(pending) { config.cloud.update(it, mark, device()) }
                require(receipt.serverVersion > mark.syncVersion) { "Moment version did not advance" }
                acknowledge(pending, mark, receipt)
                return true
            } catch (error: CloudApiException) {
                if (error.errorCode != "CONFLICT" || attempt == MAX_ATTEMPTS - 1) throw error
                val remote = request(pending) { config.cloud.get(it, id) }.atMillisecondPrecision()
                require(remote.id == id && remote.syncVersion > 0)
                mark = mergeRemote(pending, remote)
            }
        }
        error("Moment retry limit reached")
    }

    private suspend fun sendDeletion(
        pending: PendingUpload,
        id: Uuid,
    ): Boolean {
        request(pending) { config.cloud.delete(it, id) }
        val settled =
            transactions.withTransaction {
                ensureScope(pending)
                val result = metadata.settleIfCurrent(TYPE, pending, Clock.System.now(), 0)
                ensureScope(pending)
                result
            }
        retries.finishPersistedSettlement(TYPE, pending, settled)
        return true
    }

    private suspend fun mergeRemote(
        pending: PendingUpload,
        remote: MarkedMoment,
    ): MarkedMoment =
        transactions.withTransaction {
            ensureScope(pending)
            val result = config.repository.applyFromSync(remote) ?: error("Marked moment no longer accepts this capture")
            ensureScope(pending)
            result
        }

    private suspend fun acknowledge(
        pending: PendingUpload,
        sent: MarkedMoment,
        receipt: SyncUploadResult,
    ) {
        require(receipt.serverVersion > 0)
        val settled =
            transactions.withTransaction {
                ensureScope(pending)
                val current = config.repository.getMark(sent.id)
                val merged = if (current != null) config.repository.applyFromSync(sent.copy(syncVersion = receipt.serverVersion)) else null
                if (current != null) check(merged != null) { "Uploaded capture could not be acknowledged locally" }
                metadata.advancePendingVersionAfterUpload(TYPE, pending, receipt.serverVersion)
                val result =
                    if (merged == null || sameFields(merged, sent)) {
                        metadata.settleIfCurrent(TYPE, pending, receipt.syncedAt, receipt.serverVersion)
                    } else {
                        false
                    }
                ensureScope(pending)
                result
            }
        retries.finishPersistedSettlement(TYPE, pending, settled)
    }

    private fun sameFields(
        a: MarkedMoment,
        b: MarkedMoment,
    ): Boolean = a.atMillisecondPrecision().copy(syncVersion = 0) == b.atMillisecondPrecision().copy(syncVersion = 0)

    private suspend fun <T> request(
        pending: PendingUpload,
        send: suspend (String) -> Result<T>,
    ): T {
        ensureScope(pending)
        check(config.enabled()) { "Moment sync is held" }
        return tokens.withFreshToken(send, "uploadMoment", pending.scope).getOrThrow().also { ensureScope(pending) }
    }

    private fun ensureScope(pending: PendingUpload) {
        val selected = tokens.currentRequestScope()
        if (pending.scope == null || pending.scope != selected) {
            throw CloudApiException("CLOUD_SCOPE_CHANGED", "Marked-moment account or server changed", 401)
        }
    }

    private companion object {
        val TYPE = EntityType.MOMENT
        const val MAX_ATTEMPTS = 3
    }
}
