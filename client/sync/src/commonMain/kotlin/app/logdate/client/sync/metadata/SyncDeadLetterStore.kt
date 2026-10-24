package app.logdate.client.sync.metadata

import app.logdate.client.datastore.KeyValueStorage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SyncDeadLetterRecord(
    val id: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val retryCount: Int,
    val lastError: String,
    val failedAt: Long,
    val reason: SyncDeadLetterReason = SyncDeadLetterReason.UNKNOWN,
    val scope: UploadScope? = null,
    val operationId: String? = null,
    val expectedServerVersion: Long? = null,
)

@Serializable
enum class SyncDeadLetterReason {
    UNKNOWN,
    MISSING_FILE,
    APP_CLOSED,
    SERVER_UNAVAILABLE,
    SIGN_IN_REQUIRED,
    NETWORK_UNAVAILABLE,
    FILE_TOO_LARGE,
}

/** Older saved records had only a message; interpret known legacy messages once at this boundary. */
fun SyncDeadLetterRecord.effectiveReason(): SyncDeadLetterReason {
    if (reason != SyncDeadLetterReason.UNKNOWN) return reason
    return when {
        lastError.contains("no longer exists", ignoreCase = true) ||
            lastError.contains("ENOENT", ignoreCase = true) ||
            lastError.contains("No such file", ignoreCase = true) -> SyncDeadLetterReason.MISSING_FILE

        lastError.contains("closed while uploading", ignoreCase = true) -> SyncDeadLetterReason.APP_CLOSED

        lastError.contains("Service Unavailable", ignoreCase = true) ||
            lastError.contains("HTTP 503", ignoreCase = true) ||
            lastError.contains("HTTP 502", ignoreCase = true) -> SyncDeadLetterReason.SERVER_UNAVAILABLE

        lastError.contains("HTTP 401", ignoreCase = true) ||
            lastError.contains("Unauthorized", ignoreCase = true) -> SyncDeadLetterReason.SIGN_IN_REQUIRED

        else -> SyncDeadLetterReason.UNKNOWN
    }
}

interface SyncDeadLetterStore {
    fun observe(): Flow<List<SyncDeadLetterRecord>>

    suspend fun list(): List<SyncDeadLetterRecord>

    suspend fun add(record: SyncDeadLetterRecord)

    suspend fun remove(id: String)

    suspend fun clear()
}

class KeyValueSyncDeadLetterStore(
    private val storage: KeyValueStorage,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SyncDeadLetterStore {
    private val mutex = Mutex()
    private val recordsFlow = MutableStateFlow<List<SyncDeadLetterRecord>>(emptyList())
    private var loaded = false
    private var corrupted = false

    override fun observe(): Flow<List<SyncDeadLetterRecord>> =
        flow {
            ensureLoaded()
            emitAll(recordsFlow.asStateFlow())
        }

    override suspend fun list(): List<SyncDeadLetterRecord> = ensureLoaded()

    override suspend fun add(record: SyncDeadLetterRecord) {
        mutate { current -> current.filterNot { it.id == record.id } + record.sanitized() }
    }

    override suspend fun remove(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    override suspend fun clear() {
        mutex.withLock {
            storage.remove(DEAD_LETTER_KEY)
            recordsFlow.value = emptyList()
            loaded = true
            corrupted = false
        }
    }

    private suspend fun mutate(update: (List<SyncDeadLetterRecord>) -> List<SyncDeadLetterRecord>) {
        mutex.withLock {
            val current =
                if (loaded) {
                    recordsFlow.value
                } else {
                    readFromStorage().also {
                        recordsFlow.value = it
                        loaded = true
                    }
                }
            if (corrupted) {
                Napier.w("Refusing to overwrite an undecodable dead-letter store")
                return
            }
            val next = update(current)
            storage.putString(DEAD_LETTER_KEY, json.encodeToString(next))
            recordsFlow.value = next
        }
    }

    private suspend fun ensureLoaded(): List<SyncDeadLetterRecord> =
        mutex.withLock {
            if (!loaded) {
                recordsFlow.value = readFromStorage()
                loaded = true
            }
            recordsFlow.value
        }

    private suspend fun readFromStorage(): List<SyncDeadLetterRecord> {
        val raw = storage.getString(DEAD_LETTER_KEY) ?: return emptyList()
        val records =
            try {
                json.decodeFromString<List<SyncDeadLetterRecord>>(raw)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.e("Sync dead-letter store could not be decoded; preserving it unread")
                corrupted = true
                return emptyList()
            }
        val sanitized = records.map { it.sanitized() }
        if (sanitized != records) {
            try {
                storage.putString(DEAD_LETTER_KEY, json.encodeToString(sanitized))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Dead-letter privacy migration will retry on the next write")
            }
        }
        return sanitized
    }

    private fun SyncDeadLetterRecord.sanitized(): SyncDeadLetterRecord {
        val category = effectiveReason()
        return copy(lastError = category.name, reason = category)
    }

    private companion object {
        const val DEAD_LETTER_KEY = "sync_dead_letter_queue"
    }
}
