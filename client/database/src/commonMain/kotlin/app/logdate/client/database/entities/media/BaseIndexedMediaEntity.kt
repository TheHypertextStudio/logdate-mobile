package app.logdate.client.database.entities.media

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Base class for all indexed media entities.
 *
 * This abstract class defines the common fields that all indexed media
 * entities share. Specific media types (image, video) extend this class
 * with their own type-specific fields.
 *
 * NOTE: This is not an @Entity itself, but provides common properties for
 * concrete entity classes to inherit.
 */
abstract class BaseIndexedMediaEntity {
    /**
     * Unique identifier for this indexed media.
     */
    abstract val uid: Uuid

    /**
     * Original URI of the media on the device.
     */
    abstract val uri: String

    /**
     * The timestamp when the media was created.
     */
    abstract val timestamp: Instant

    /**
     * The timestamp when the media was indexed.
     *
     * IndexedImageEntity and IndexedVideoEntity store it as the indexedAt Room column in the exported schemas
     */
    @Suppress("unused")
    abstract val indexedAt: Instant

    /**
     * MIME type of the media (e.g., "image/jpeg", "video/mp4").
     */
    abstract val mimeType: String

    /**
     * Size of the media file in bytes.
     *
     * IndexedImageEntity and IndexedVideoEntity store it as the fileSize Room column in the exported schemas
     */
    @Suppress("unused")
    abstract val fileSize: Long

    /**
     * Dimensions of the media (width and height).
     * Note: The concrete implementation should use @Embedded(prefix = "dimensions_")
     */
    abstract val dimensions: MediaDimensions

    /**
     * Location data for the media, if available.
     * Note: The concrete implementation should use @Embedded(prefix = "location_")
     */
    abstract val location: LocationData?
}
