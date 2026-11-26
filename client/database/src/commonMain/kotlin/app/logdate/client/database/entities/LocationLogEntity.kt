package app.logdate.client.database.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * A log of a user's location at a given time using a given device.
 *
 * Logs of this type are used as ground truth for other app functionality, like timelines and
 * location history.
 *
 * Note that the entries in this data are only expected to be as accurate as the device that created
 * them. For example, if a user's device is rooted or jailbroken, the data in this log may be
 * inaccurate or falsified.
 *
 * This data should not be used as a source of truth for a user's location but can be used to
 * approximate a user's location at a given time.
 */
@Entity(
    tableName = "location_logs",
    indices = [
        Index(
            value = ["user_id", "device_id", "timestamp", "sample_id"],
        ),
        Index(value = ["timestamp"]),
        Index(value = ["user_id", "device_id", "logged_at", "sample_id"]),
        Index(value = ["user_id", "device_id", "ingestion_seq", "sample_id"]),
    ],
)
data class LocationLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "sample_id")
    val sampleId: String,
    /**
     * The user that created this log.
     */
    @ColumnInfo(name = "user_id")
    val userId: String,
    /**
     * The device that created this log.
     *
     * @see [UserDeviceEntity]
     */
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    /**
     * When this log was created.
     */
    val timestamp: Instant,
    /**
     * When this log was persisted locally.
     */
    @ColumnInfo(name = "logged_at")
    val loggedAt: Instant,
    /**
     * The location of the user when this log was created.
     */
    @Embedded
    val location: Coordinates,
    /**
     * How likely this location is to be accurate based on the device's sensors.
     */
    val confidence: Float,
    /**
     * Whether this log was produced on a device that is known to be genuine.
     *
     * For Android devices, this means that the device is not rooted or jailbroken and that the app
     * binary has not been tampered with, as determined by the Play Integrity API.
     */
    @ColumnInfo(name = "is_genuine")
    val isGenuine: Boolean,
    /**
     * The capture pipeline responsible for this sample.
     */
    @ColumnInfo(name = "capture_pipeline")
    val capturePipeline: String,
    /**
     * The trigger source that produced this sample.
     */
    @ColumnInfo(name = "capture_source")
    val captureSource: String,
    /**
     * Best-known horizontal accuracy for this sample, if available.
     */
    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: Float? = null,
    @ColumnInfo(name = "speed_meters_per_second")
    val speedMetersPerSecond: Float? = null,
    @ColumnInfo(name = "bearing_degrees")
    val bearingDegrees: Float? = null,
    @ColumnInfo(name = "is_mock")
    val isMock: Boolean = false,
    @ColumnInfo(name = "activity_type")
    val activityType: String? = null,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String? = null,
    @ColumnInfo(name = "provider")
    val provider: String? = null,
    @ColumnInfo(name = "speed_accuracy_mps")
    val speedAccuracyMetersPerSecond: Float? = null,
    @ColumnInfo(name = "vertical_accuracy_meters")
    val verticalAccuracyMeters: Float? = null,
    @ColumnInfo(name = "elapsed_realtime_nanos")
    val elapsedRealtimeNanos: Long? = null,
    @ColumnInfo(name = "ingestion_seq", defaultValue = "0")
    val ingestionSeq: Long = 0L,
)

/**
 * A set of coordinates that represent a location on Earth.
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
)
