package app.logdate.client.database.entities.media

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Entity for indexed video media.
 *
 * This entity represents video files that have been indexed by the application.
 * It extends the base media entity class with video-specific properties.
 */
@Entity(
    tableName = "indexed_media_videos",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("timestamp"),
    ],
)
data class IndexedVideoEntity(
    @PrimaryKey
    override val uid: Uuid,
    override val uri: String,
    override val timestamp: Instant,
    override val indexedAt: Instant,
    override val mimeType: String,
    override val fileSize: Long,
    @Embedded(prefix = "dimensions_")
    override val dimensions: MediaDimensions,
    @Embedded(prefix = "location_")
    override val location: LocationData?,
    /**
     * Duration of the video.
     */
    val duration: Duration,
) : BaseIndexedMediaEntity()
