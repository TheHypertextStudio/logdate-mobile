package app.logdate.wear.sync

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** A sent snapshot survives restart so an old acknowledgement cannot clear a later edit. */
class WearAudioDeliveryStore(
    private val storage: KeyValueStorage? = null,
) {
    private var memory = emptyMap<String, Map<String, String>>()

    suspend fun capture(
        pending: PendingUpload,
        snapshot: Map<String, String>,
    ): WearAudioDelivery {
        val records = read()
        val encoded = snapshotFingerprint(snapshot)
        records.entries
            .firstOrNull { (_, record) ->
                record[SNAPSHOT] == encoded && WearAudioDelivery("", record).matches(pending)
            }?.let { return WearAudioDelivery(it.key, it.value) }
        val token = Uuid.random().toString()
        val record =
            mapOf(
                ENTITY to pending.entityId,
                OPERATION to pending.operation.name,
                OPERATION_ID to pending.operationId.orEmpty(),
                OWNER to pending.scope?.ownerId.orEmpty(),
                ORIGIN to pending.scope?.serverOrigin.orEmpty(),
                VERSION to pending.expectedServerVersion?.toString().orEmpty(),
                SNAPSHOT to encoded,
                LEGACY_ALLOWED to
                    (
                        pending.operationId == null &&
                            pending.scope == null &&
                            pending.operation == PendingOperation.CREATE &&
                            records.values.none { it[ENTITY] == pending.entityId }
                    ).toString(),
            )
        write(records + (token to record))
        return WearAudioDelivery(token, record)
    }

    suspend fun deliveries(noteId: String): List<WearAudioDelivery> =
        read().filterValues { it[ENTITY] == noteId && it[COMPLETED] != "true" }.map { WearAudioDelivery(it.key, it.value) }

    suspend fun remove(token: String) {
        val records = read()
        val record = records[token] ?: return
        // Keep only a receipt that prevents an ambiguous legacy ACK clearing another operation.
        write(records + (token to mapOf(ENTITY to record.getValue(ENTITY), COMPLETED to "true")))
    }

    private suspend fun read(): Map<String, Map<String, String>> {
        if (storage == null) return memory
        return storage.getString(STORAGE_KEY)?.let { Json.decodeFromString(it) } ?: emptyMap()
    }

    private suspend fun write(records: Map<String, Map<String, String>>) {
        storage?.putString(STORAGE_KEY, Json.encodeToString(records))
        memory = records
    }

    companion object {
        private const val STORAGE_KEY = "wear_audio_deliveries_v1"
        internal const val ENTITY = "entity"
        internal const val OPERATION = "operation"
        internal const val OPERATION_ID = "operationId"
        internal const val OWNER = "owner"
        internal const val ORIGIN = "origin"
        internal const val VERSION = "version"
        internal const val SNAPSHOT = "snapshot"
        internal const val COMPLETED = "completed"
        internal const val LEGACY_ALLOWED = "legacyAllowed"
    }
}

class WearAudioDelivery internal constructor(
    val token: String,
    private val record: Map<String, String>,
) {
    val allowsLegacy: Boolean get() = record[WearAudioDeliveryStore.LEGACY_ALLOWED] == "true"

    val operationId: String? get() = record[WearAudioDeliveryStore.OPERATION_ID]?.ifEmpty { null }

    fun matches(pending: PendingUpload): Boolean =
        record[WearAudioDeliveryStore.ENTITY] == pending.entityId &&
            record[WearAudioDeliveryStore.OPERATION] == pending.operation.name &&
            record[WearAudioDeliveryStore.OPERATION_ID] == pending.operationId.orEmpty() &&
            record[WearAudioDeliveryStore.OWNER] == pending.scope?.ownerId.orEmpty() &&
            record[WearAudioDeliveryStore.ORIGIN] == pending.scope?.serverOrigin.orEmpty() &&
            record[WearAudioDeliveryStore.VERSION] == pending.expectedServerVersion?.toString().orEmpty()

    val retryKey: String
        get() {
            val entity = record[WearAudioDeliveryStore.ENTITY].orEmpty()
            val operation = operationId ?: return entity
            val owner = record[WearAudioDeliveryStore.OWNER].orEmpty()
            val origin = record[WearAudioDeliveryStore.ORIGIN].orEmpty()
            return "operation:${owner.length}:$owner:${origin.length}:$origin:$operation"
        }

    fun matchesFailure(failure: SyncDeadLetterRecord): Boolean =
        record[WearAudioDeliveryStore.ENTITY] == failure.entityId &&
            record[WearAudioDeliveryStore.OPERATION] == failure.operation &&
            record[WearAudioDeliveryStore.OPERATION_ID] == failure.operationId.orEmpty() &&
            record[WearAudioDeliveryStore.OWNER] == failure.scope?.ownerId.orEmpty() &&
            record[WearAudioDeliveryStore.ORIGIN] == failure.scope?.serverOrigin.orEmpty() &&
            record[WearAudioDeliveryStore.VERSION] == failure.expectedServerVersion?.toString().orEmpty()

    fun matchesSnapshot(snapshot: Map<String, String>): Boolean = record[WearAudioDeliveryStore.SNAPSHOT] == snapshotFingerprint(snapshot)
}

/** The delivery record retains no note text or local media paths. */
private fun snapshotFingerprint(snapshot: Map<String, String>): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(Json.encodeToString<Map<String, String>>(snapshot.toSortedMap()).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
