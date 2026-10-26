package app.logdate.client.sync.location

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.LocationHistoryApiClientContract
import app.logdate.client.networking.LocationHistoryTransportException
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.config.PinnedLogDateConfigRepository
import app.logdate.shared.model.sync.LocationHistoryUpload
import kotlinx.coroutines.CancellationException

/** Each run pins its account and server; no page cursor advances until the entire page decrypts. */
class LocationHistorySyncEngine(
    private val apiFactory: (LogDateConfigRepository) -> LocationHistoryApiClientContract,
    private val store: HistoryRecordStore,
    private val cipher: SyncPayloadCipher,
    private val sessions: SessionStorage,
    private val config: LogDateConfigRepository,
    private val enabled: suspend () -> Boolean,
    private val prepare: suspend (ownerId: String, origin: String) -> Boolean = { _, _ -> false },
) {
    suspend fun upload(accessToken: String): SyncResult =
        runPhase(accessToken) { namespace, api ->
            val moreToStage = prepare(namespace.owner, namespace.origin)
            var uploaded = 0
            repeat(MAX_PAGES_PER_RUN) {
                ensureCurrent(namespace)
                val pending = store.pending(namespace.owner, namespace.origin, PAGE_SIZE)
                if (pending.isEmpty()) return@runPhase SyncResult(success = true, uploadedItems = uploaded, hasMorePending = moreToStage)
                val uploads =
                    pending.map { record ->
                        LocationHistoryUpload(
                            id = record.id,
                            recordType = record.recordType,
                            payload =
                                if (record.deleted) {
                                    null
                                } else {
                                    cipher.encryptString(
                                        namespace.fieldId(record.id, record.recordType),
                                        requireNotNull(record.payload),
                                    )
                                },
                            deviceId = record.deviceId,
                            deviceVersion = record.deviceVersion,
                            expectedServerVersion = record.serverVersion,
                            deleted = record.deleted,
                        )
                    }
                ensureCurrent(namespace)
                val accepted = api.upload(accessToken, uploads)
                ensureCurrent(namespace)
                check(accepted.size == uploads.size && accepted.map { it.id }.toSet() == uploads.map { it.id }.toSet())
                accepted.forEach { record ->
                    val sent = uploads.single { it.id == record.id }
                    check(
                        record.deviceId == sent.deviceId &&
                            record.deviceVersion == sent.deviceVersion &&
                            record.deleted == sent.deleted &&
                            record.recordType == sent.recordType &&
                            record.serverVersion > 0,
                    )
                    store.acknowledge(namespace.owner, namespace.origin, record.id, sent.deviceVersion, record.serverVersion)
                }
                uploaded += accepted.size
            }
            SyncResult(
                success = true,
                uploadedItems = uploaded,
                hasMorePending =
                    moreToStage ||
                        store.pending(namespace.owner, namespace.origin, 1).isNotEmpty(),
            )
        }

    suspend fun download(accessToken: String): SyncResult =
        runPhase(accessToken) { namespace, api ->
            var downloaded = 0
            var cursor = store.cursor(namespace.owner, namespace.origin)
            repeat(MAX_PAGES_PER_RUN) {
                ensureCurrent(namespace)
                val page = api.changes(accessToken, cursor, PAGE_SIZE)
                ensureCurrent(namespace)
                check(page.nextCursor >= cursor && page.records.size <= PAGE_SIZE)
                check(!page.hasMore || page.nextCursor > cursor)
                val decoded =
                    page.records.map { record ->
                        check(record.payloadSchemaVersion == 1 && record.serverVersion > cursor && record.serverVersion <= page.nextCursor)
                        val payload =
                            if (record.deleted) {
                                check(record.payload == null)
                                null
                            } else {
                                val ciphertext = requireNotNull(record.payload)
                                check(ciphertext.startsWith("LDSE2:"))
                                cipher.decryptString(namespace.fieldId(record.id, record.recordType), ciphertext)
                            }
                        HistoryRecord(
                            record.id,
                            record.recordType,
                            payload,
                            record.deviceId,
                            record.deviceVersion,
                            record.serverVersion,
                            record.deleted,
                            dirty = false,
                        )
                    }
                ensureCurrent(namespace)
                store.applyPage(namespace.owner, namespace.origin, decoded, page.nextCursor)
                cursor = page.nextCursor
                downloaded += decoded.size
                if (!page.hasMore) return@runPhase SyncResult(success = true, downloadedItems = downloaded)
            }
            SyncResult(success = true, downloadedItems = downloaded, hasMorePending = true)
        }

    suspend fun isEnabled(): Boolean = enabled()

    /** Whether local location records are waiting to upload for the signed-in account. */
    suspend fun hasPendingUploads(accessToken: String): Boolean {
        if (!isEnabled()) return false
        val namespace = captureNamespace(accessToken)
        return store.pending(namespace.owner, namespace.origin, 1).isNotEmpty()
    }

    /** Checked before minting an identity key on a fresh device, including location-only accounts. */
    suspend fun hasRemoteRecords(accessToken: String): Boolean {
        val namespace = captureNamespace(accessToken)
        // Recovery must protect existing ciphertext even when this device has not enabled history.
        val hasRecords =
            try {
                pinnedApi(namespace).changes(accessToken, 0, 1).records.isNotEmpty()
            } catch (error: LocationHistoryTransportException) {
                // Older servers cannot contain this record type. Other failures leave the account unknown.
                if (error.statusCode !in setOf(404, 405, 501)) throw error
                false
            }
        ensureCurrent(namespace)
        return hasRecords
    }

    private suspend fun runPhase(
        accessToken: String,
        block: suspend (Namespace, LocationHistoryApiClientContract) -> SyncResult,
    ): SyncResult {
        if (!enabled()) return SyncResult(success = true)
        return try {
            val namespace = captureNamespace(accessToken)
            block(namespace, pinnedApi(namespace))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            app.logdate.client.sync
                .throwIfDeviceBlocked(error)
            val type =
                when ((error as? LocationHistoryTransportException)?.statusCode) {
                    401, 403 -> SyncErrorType.AUTHENTICATION_ERROR
                    409 -> SyncErrorType.CONFLICT_ERROR
                    else -> SyncErrorType.UNKNOWN_ERROR
                }
            // Do not include ciphertext or decoded records in error messages or logs.
            SyncResult(
                success = false,
                errors = listOf(SyncError(type, "Location history could not sync. Retry after checking your account and connection.")),
            )
        }
    }

    private fun captureNamespace(accessToken: String): Namespace {
        val session = requireNotNull(sessions.getSession())
        check(session.accessToken == accessToken)
        return Namespace(session.accountId, config.getCurrentBackendUrl().trimEnd('/'))
    }

    private fun pinnedApi(namespace: Namespace): LocationHistoryApiClientContract {
        ensureCurrent(namespace)
        return apiFactory(PinnedLogDateConfigRepository(namespace.origin, config.getCurrentServerDescriptor()))
    }

    private fun ensureCurrent(namespace: Namespace) {
        check(sessions.getSession()?.accountId == namespace.owner && config.getCurrentBackendUrl().trimEnd('/') == namespace.origin)
    }

    private data class Namespace(
        val owner: String,
        val origin: String,
    ) {
        fun fieldId(
            id: String,
            type: String,
        ): String = "location-history:$origin:$owner:$type:$id:v1"
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES_PER_RUN = 10
    }
}
