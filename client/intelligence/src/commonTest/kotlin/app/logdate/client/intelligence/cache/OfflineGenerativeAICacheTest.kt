package app.logdate.client.intelligence.cache

import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests the [OfflineGenerativeAICache] to ensure efficient and reliable storage of AI-generated
 * content.
 *
 * This suite validates critical cache behaviors such as Time-To-Live (TTL) expiration,
 * entry eviction when reaching maximum capacity, and correct interaction with the
 * underlying persistent data source.
 */
class OfflineGenerativeAICacheTest {
    @Test
    fun `oversized requests bypass normalization hashing and persistence`() =
        runTest {
            val dataSource = TestLocalDataSource()
            val cache =
                OfflineGenerativeAICache(
                    dataSource,
                    keyStrategy =
                        object : AICacheKeyStrategy {
                            override fun createKey(input: AICacheKeyInput): AICacheKey = error("Oversized input reached key construction")
                        },
                )
            val request = requestFor("q".repeat(MAX_AI_INPUT_CHARACTERS + 1), 60)
            cache.putEntry(request, "summary")
            assertNull(cache.getEntry(request))
            assertEquals(0, dataSource.entries().size)
        }

    @Test
    fun `get entry expires after ttl`() =
        runTest {
            val clock = TestClock(Instant.parse("2024-01-01T00:00:00Z"))
            val dataSource = TestLocalDataSource()
            val cache =
                OfflineGenerativeAICache(
                    dataSource = dataSource,
                    clock = clock,
                    config = AICacheConfig(memoryMaxEntries = 10, memoryMaxBytes = 10_000),
                )
            val request = requestFor("Hello world", ttlSeconds = 5)

            cache.putEntry(request, "summary")
            val fresh = cache.getEntry(request)
            assertNotNull(fresh)

            clock.advance(seconds = 6)
            val expired = cache.getEntry(request)
            assertNull(expired)
        }

    @Test
    fun `put entry enforces persistent max entries`() =
        runTest {
            val clock = TestClock(Instant.parse("2024-01-01T00:00:00Z"))
            val dataSource = TestLocalDataSource()
            val cache =
                OfflineGenerativeAICache(
                    dataSource = dataSource,
                    clock = clock,
                    config =
                        AICacheConfig(
                            memoryMaxEntries = 10,
                            memoryMaxBytes = 10_000,
                            persistentMaxEntries = 1,
                            persistentMaxBytes = 10_000,
                        ),
                )

            val firstRequest = requestFor("First", ttlSeconds = 60)
            val secondRequest = requestFor("Second", ttlSeconds = 60)

            cache.putEntry(firstRequest, "first-content")
            clock.advance(seconds = 1)
            cache.putEntry(secondRequest, "second-content")

            val remaining = dataSource.entries()
            assertEquals(1, remaining.size)
            assertEquals("second-content", remaining.first().content)
        }

    @Test
    fun `storing many answers lists the persistent cache once`() =
        runTest {
            val clock = TestClock(Instant.parse("2024-01-01T00:00:00Z"))
            val dataSource = TestLocalDataSource()
            val cache =
                OfflineGenerativeAICache(
                    dataSource = dataSource,
                    clock = clock,
                    config =
                        AICacheConfig(
                            memoryMaxEntries = 10,
                            memoryMaxBytes = 10_000,
                            persistentMaxEntries = 5,
                            persistentMaxBytes = 10_000,
                        ),
                )

            repeat(20) { index ->
                cache.putEntry(requestFor("Entry $index", ttlSeconds = 60), "content-$index")
                clock.advance(seconds = 1)
            }

            assertEquals(1, dataSource.listings)
            assertEquals((15 until 20).map { "content-$it" }, dataSource.entries().map { it.content }.sorted())
        }

    private fun requestFor(
        inputText: String,
        ttlSeconds: Long,
    ): GenerativeAICacheRequest =
        GenerativeAICacheRequest(
            contentType = GenerativeAICacheContentType.Summary,
            inputText = inputText,
            providerId = "openai",
            model = "gpt-test",
            promptVersion = "v1",
            schemaVersion = "schema-v1",
            templateId = "summary",
            policy = AICachePolicy(ttlSeconds = ttlSeconds),
        )

    /**
     * A test implementation of [Clock] that allows manual time advancement for testing.
     */
    private class TestClock(
        private var current: Instant,
    ) : Clock {
        override fun now(): Instant = current

        fun advance(seconds: Long) {
            current = current + seconds.seconds
        }
    }

    /**
     * A test implementation of [AICacheLocalDataSource] that stores entries in memory.
     */
    private class TestLocalDataSource : AICacheLocalDataSource {
        private val store = mutableMapOf<String, GenerativeAICacheEntry>()

        override fun get(key: String): GenerativeAICacheEntry? = store[key]

        override fun set(
            key: String,
            entry: GenerativeAICacheEntry,
        ) {
            store[key] = entry
        }

        override fun remove(key: String) {
            store.remove(key)
        }

        var listings = 0
            private set

        override fun entries(): List<GenerativeAICacheEntry> {
            listings++
            return store.values.toList()
        }

        override fun clear() {
            store.clear()
        }
    }
}
