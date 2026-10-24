package app.logdate.client.domain.media

import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.repository.media.IndexedMediaRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Instant

/**
 * Use case to automatically index media from a specific time period.
 *
 * This use case is responsible for discovering media on the device within a specified
 * time period and registering it with the app's indexed media system. This ensures that
 * all media referenced in rewinds is properly indexed within the app before being
 * included in a rewind.
 *
 * Indexing media provides several benefits:
 * - Establishes a stable reference system via UIDs
 * - Ensures media can be consistently accessed
 * - Allows for metadata enrichment (captions, etc.)
 * - Enables proper organization and categorization of media
 */
class IndexMediaForPeriodUseCase(
    private val mediaManager: MediaManager,
    private val indexedMediaRepository: IndexedMediaRepository,
) {
    /**
     * Indexes all media found within the specified time period.
     *
     * The process:
     * 1. Queries the device's media store for media created within the time period
     * 2. For each media item, checks if it's already indexed
     * 3. If not already indexed, adds it to the app's indexed media repository
     * 4. Returns a count of newly indexed items
     *
     * @param startTime Start of the time period (inclusive)
     * @param endTime End of the time period (exclusive)
     * @return Number of media items newly indexed
     * @throws Exception if media indexing fails (individual item failures are logged but don't stop the process)
     */
    suspend operator fun invoke(
        startTime: Instant,
        endTime: Instant,
    ): Int {
        Napier.d("Indexing media for period: $startTime to $endTime")

        try {
            // Query media from the device for the specified period
            val mediaItemsFlow = mediaManager.queryMediaByDate(startTime, endTime)
            val mediaItems = mediaItemsFlow.firstOrNull() ?: emptyList()
            Napier.d("Found ${mediaItems.size} media items to index")

            if (mediaItems.isEmpty()) {
                return 0
            }

            // Index each media item
            var indexedCount = 0
            mediaItems.forEach { mediaItem ->
                try {
                    // Skip media that's already indexed
                    if (indexedMediaRepository.isIndexed(mediaItem.uri)) {
                        Napier.d("Media already indexed: ${mediaItem.uri}")
                        return@forEach
                    }

                    when (mediaItem) {
                        is MediaObject.Image -> {
                            val indexedItem =
                                indexedMediaRepository.indexImage(
                                    uri = mediaItem.uri,
                                    timestamp = mediaItem.timestamp,
                                )
                            Napier.d("Indexed image: ${indexedItem.uid}")
                            indexedCount++
                        }

                        is MediaObject.Video -> {
                            val indexedItem =
                                indexedMediaRepository.indexVideo(
                                    uri = mediaItem.uri,
                                    timestamp = mediaItem.timestamp,
                                    duration = mediaItem.duration,
                                )
                            Napier.d("Indexed video: ${indexedItem.uid}")
                            indexedCount++
                        }
                    }
                } catch (e: Exception) {
                    Napier.w("Failed to index media item: ${mediaItem.uri}", e)
                    // Continue with next item to ensure partial success
                }
            }

            Napier.d("Successfully indexed $indexedCount/${mediaItems.size} media items")
            return indexedCount
        } catch (e: Exception) {
            Napier.e("Failed to index media for period", e)
            throw e
        }
    }
}
