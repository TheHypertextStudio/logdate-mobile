package app.logdate.feature.postcards.fakes

import app.logdate.client.repository.media.ExifMetadata
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * In-memory [IndexedMediaRepository] for tests.
 */
class FakeIndexedMediaRepository(
    initial: List<IndexedMedia> = emptyList(),
) : IndexedMediaRepository {
    private val media = MutableStateFlow(initial)

    override suspend fun indexImage(
        uri: String,
        timestamp: Instant,
    ): IndexedMedia.Image {
        val image = IndexedMedia.Image(Uuid.random(), uri, timestamp)
        media.value = media.value + image
        return image
    }

    override suspend fun indexVideo(
        uri: String,
        timestamp: Instant,
        duration: Duration,
    ): IndexedMedia.Video {
        val video = IndexedMedia.Video(Uuid.random(), uri, timestamp, duration = duration)
        media.value = media.value + video
        return video
    }

    override suspend fun getByUid(uid: Uuid): IndexedMedia? = media.value.find { it.uid == uid }

    override fun getForPeriod(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<IndexedMedia>> = media.map { all -> all.filter { it.timestamp in startTime..endTime } }

    override suspend fun isIndexed(uri: String): Boolean = media.value.any { it.uri == uri }

    override suspend fun remove(uid: Uuid): Boolean {
        val before = media.value
        media.value = before.filterNot { it.uid == uid }
        return media.value.size < before.size
    }

    override suspend fun updateCaption(
        uid: Uuid,
        caption: String?,
    ): IndexedMedia? = null

    override fun observeAllMedia(): Flow<List<IndexedMedia>> = media

    override fun getMediaCount(): Flow<Int> = media.map { it.size }

    override suspend fun getExifMetadata(uid: Uuid): ExifMetadata? = null
}
