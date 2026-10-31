package app.logdate.client.intelligence.cache

import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import app.logdate.client.util.platformIODispatcher
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A generative AI cache that stores entries using the device's local persistent storage.
 *
 * The size of each stored entry is kept in memory after the first write, so enforcing the
 * persistent limits never lists and decodes the whole cache again.
 */
class OfflineGenerativeAICache(
    private val dataSource: AICacheLocalDataSource,
    private val keyStrategy: AICacheKeyStrategy = DefaultAICacheKeyStrategy(),
    private val config: AICacheConfig = AICacheConfig(),
    private val clock: Clock = Clock.System,
    private val memoryStore: AICacheMemoryStore =
        LruAICacheMemoryStore(
            maxEntries = config.memoryMaxEntries,
            maxBytes = config.memoryMaxBytes,
        ),
    private val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) : GenerativeAICache {
    private val indexMutex = Mutex()
    private var persistentIndex: MutableMap<String, StoredEntrySize>? = null

    override suspend fun getEntry(request: GenerativeAICacheRequest): GenerativeAICacheEntry? {
        return withContext(ioDispatcher) {
            if (request.inputText.length > MAX_AI_INPUT_CHARACTERS) return@withContext null
            val cacheKey = keyStrategy.createKey(request.toKeyInput())
            val memoryHit = memoryStore.get(cacheKey.value)
            if (memoryHit != null && isEntryValid(memoryHit, cacheKey, request)) {
                return@withContext memoryHit
            }
            if (memoryHit != null) {
                memoryStore.remove(cacheKey.value)
            }
            val entry =
                runCatching { dataSource.get(cacheKey.value) }
                    .onFailure { error ->
                        Napier.w(message = "Failed to read AI cache entry ${cacheKey.value}", throwable = error)
                    }.getOrNull()
            if (entry == null) {
                return@withContext null
            }
            if (!isEntryValid(entry, cacheKey, request)) {
                indexMutex.withLock { removeEntry(cacheKey.value) }
                return@withContext null
            }
            memoryStore.put(cacheKey.value, entry)
            entry
        }
    }

    override suspend fun putEntry(
        request: GenerativeAICacheRequest,
        content: String,
    ) = withContext(ioDispatcher) {
        if (request.inputText.length > MAX_AI_INPUT_CHARACTERS) return@withContext
        val cacheKey = keyStrategy.createKey(request.toKeyInput())
        val now = clock.now()
        val expiresAt = now + request.policy.ttlSeconds.seconds
        val metadata =
            GenerativeAICacheEntryMetadata(
                contentTypeId = request.contentType.id,
                providerId = request.providerId,
                model = request.model,
                promptVersion = request.promptVersion,
                schemaVersion = request.schemaVersion,
                templateId = request.templateId,
                ttlSeconds = request.policy.ttlSeconds,
                expiresAt = expiresAt,
                sourceHash = cacheKey.sourceHash,
                debugPrefix = cacheKey.debugPrefix,
                contentBytes = content.encodeToByteArray().size.toLong(),
            )
        val entry =
            GenerativeAICacheEntry(
                key = cacheKey.value,
                content = content,
                lastUpdated = now,
                metadata = metadata,
            )
        memoryStore.put(cacheKey.value, entry)
        val stored =
            runCatching { dataSource.set(cacheKey.value, entry) }
                .onFailure { error ->
                    Napier.w(message = "Failed to write AI cache entry ${cacheKey.value}", throwable = error)
                }.isSuccess
        indexMutex.withLock {
            val index = loadIndex()
            if (stored) index[cacheKey.value] = StoredEntrySize(entry.lastUpdated, metadata.expiresAt, metadata.contentBytes)
            enforcePersistentLimits(index)
        }
    }

    override suspend fun purge() =
        withContext(ioDispatcher) {
            memoryStore.clear()
            runCatching { dataSource.clear() }
                .onFailure { error ->
                    Napier.w(message = "Failed to purge AI cache", throwable = error)
                }
            indexMutex.withLock { persistentIndex = null }
            Unit
        }

    private fun isEntryValid(
        entry: GenerativeAICacheEntry,
        key: AICacheKey,
        request: GenerativeAICacheRequest,
    ): Boolean {
        if (entry.metadata.expiresAt <= clock.now()) {
            return false
        }
        if (entry.metadata.sourceHash != key.sourceHash) {
            return false
        }
        if (entry.metadata.contentTypeId != request.contentType.id) {
            return false
        }
        if (request.policy.includeProviderInKey && entry.metadata.providerId != request.providerId) {
            return false
        }
        if (request.policy.includeModelInKey && entry.metadata.model != request.model) {
            return false
        }
        if (request.policy.includePromptVersionInKey && entry.metadata.promptVersion != request.promptVersion) {
            return false
        }
        if (request.policy.includeSchemaVersionInKey && entry.metadata.schemaVersion != request.schemaVersion) {
            return false
        }
        if (request.policy.includeTemplateIdInKey && entry.metadata.templateId != request.templateId) {
            return false
        }
        return true
    }

    /** Call with [indexMutex] held. */
    private fun removeEntry(key: String) {
        memoryStore.remove(key)
        runCatching { dataSource.remove(key) }
            .onFailure { error ->
                Napier.w(message = "Failed to remove AI cache entry $key", throwable = error)
            }
        persistentIndex?.remove(key)
    }

    /** Call with [indexMutex] held. Lists the stored entries only the first time. */
    private fun loadIndex(): MutableMap<String, StoredEntrySize> {
        persistentIndex?.let { return it }
        val stored =
            runCatching { dataSource.entries() }
                .onFailure { error ->
                    Napier.w(message = "Failed to list AI cache entries", throwable = error)
                }.getOrDefault(emptyList())
        val index =
            stored.associateTo(mutableMapOf()) { entry ->
                entry.key to StoredEntrySize(entry.lastUpdated, entry.metadata.expiresAt, entry.metadata.contentBytes)
            }
        persistentIndex = index
        return index
    }

    /** Call with [indexMutex] held. Removes expired entries, then the oldest until the limits hold. */
    private fun enforcePersistentLimits(index: MutableMap<String, StoredEntrySize>) {
        val now = clock.now()
        val expired = index.filterValues { it.expiresAt <= now }.keys
        expired.forEach(::removeEntry)

        var totalBytes = index.values.sumOf { it.contentBytes }
        if (index.size <= config.persistentMaxEntries && totalBytes <= config.persistentMaxBytes) {
            return
        }
        val oldestFirst = index.entries.sortedBy { it.value.lastUpdated }.map { it.key to it.value.contentBytes }
        for ((key, bytes) in oldestFirst) {
            if (index.size <= config.persistentMaxEntries && totalBytes <= config.persistentMaxBytes) {
                break
            }
            removeEntry(key)
            totalBytes -= bytes
        }
    }

    private data class StoredEntrySize(
        val lastUpdated: Instant,
        val expiresAt: Instant,
        val contentBytes: Long,
    )

    private fun GenerativeAICacheRequest.toKeyInput(): AICacheKeyInput =
        AICacheKeyInput(
            contentType = contentType,
            inputText = inputText,
            providerId = providerId,
            model = model,
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            templateId = templateId,
            policy = policy,
        )
}
