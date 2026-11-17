package app.logdate.client.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Deterministic in-memory [MediaManager] used by cross-module tests.
 */
open class InMemoryMediaManager :
    MediaManager,
    ConditionalMediaSaver {
    private val deletedOwnedMedia = mutableSetOf<String>()
    private val payloads = mutableMapOf<String, MediaPayload>()

    /**
     * The URIs [deleteOwnedMedia] was asked to remove, in call order.
     *
     * Assertion hook for StoredMediaReferencePersistenceTest (client/data desktopTest) on this commonMain test double
     */
    @Suppress("unused")
    val deletedOwnedMediaUris: Set<String> get() = deletedOwnedMedia

    override suspend fun getMedia(uri: String): MediaObject =
        MediaObject.Video(
            name = "In-memory video",
            uri = uri,
            size = 0,
            timestamp = Instant.parse("2023-01-01T12:00:00Z"),
            duration = 30.seconds,
        )

    override suspend fun deleteOwnedMedia(uri: String): Boolean {
        payloads.remove(uri)
        return deletedOwnedMedia.add(uri)
    }

    override suspend fun exists(mediaId: String): Boolean = payloads.containsKey(mediaId)

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun addToDefaultCollection(uri: String) {
    }

    override suspend fun readMedia(uri: String): MediaPayload =
        payloads[uri] ?: MediaPayload(
            fileName = "memory.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 0,
            data = ByteArray(0),
        )

    override suspend fun saveMedia(payload: MediaPayload): String {
        val uri = "file:///tmp/${payload.fileName}"
        payloads[uri] = payload.copy(data = payload.data.copyOf())
        return uri
    }

    override suspend fun saveMedia(
        payload: MediaPayload,
        canCommit: () -> Boolean,
    ): String {
        if (!canCommit()) throw kotlinx.coroutines.CancellationException("Media hydration scope changed")
        val uri = "file:///tmp/${Uuid.random()}-${payload.fileName}"
        payloads[uri] = payload.copy(data = payload.data.copyOf())
        if (!canCommit()) {
            deleteOwnedMedia(uri)
            throw kotlinx.coroutines.CancellationException("Media hydration scope changed")
        }
        return uri
    }

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String = "file:///tmp/$fileName"

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
        canCommit: () -> Boolean,
    ): String {
        if (!canCommit()) throw kotlinx.coroutines.CancellationException("Media hydration scope changed")
        val uri = saveMediaFromFile(sourceFilePath, fileName, mimeType)
        if (!canCommit()) throw kotlinx.coroutines.CancellationException("Media hydration scope changed")
        return uri
    }
}
