package app.logdate.client.database.entities

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Base class for all note entities.
 *
 * All notes support location capture with a hybrid approach:
 * - Embedded coordinates (latitude, longitude, altitude) for capture fidelity
 * - Optional Place reference for semantic meaning (e.g., "Home", "Work")
 *
 * This design preserves the exact GPS coordinates at note creation while
 * allowing places to be associated later for display purposes.
 */
abstract class GenericNoteData {
    abstract val uid: Uuid
    abstract val lastUpdated: Instant
    abstract val created: Instant

    abstract val syncVersion: Long

    // Every note entity stores it as the lastSynced Room column in the exported database schemas
    @Suppress("unused")
    abstract val lastSynced: Instant?
    abstract val deletedAt: Instant?

    // Location fields - nullable to support notes without location
    abstract val latitude: Double?
    abstract val longitude: Double?
    abstract val altitude: Double?
    abstract val locationAccuracy: Float?
    abstract val placeId: Uuid?

    /** Canonical note-local location, independent of whether the linked place exists here. */
    abstract val locationMetadata: String?

    /**
     * IANA id of the time zone the device was in when the note was captured, or null when it was
     * not recorded. Stored as written and not validated: an id newer than this device's tz data
     * is still worth keeping.
     */
    abstract val timeZoneId: String?

    /**
     * The wire name of the device the note was captured on (`phone` or `watch`), or null when it was
     * not recorded.
     */
    abstract val capturedOn: String?

    /**
     * The wire name of what the person was doing at capture (`moving` or `in_vehicle`), or null when it
     * was not recorded.
     */
    abstract val capturedDuring: String?
}
