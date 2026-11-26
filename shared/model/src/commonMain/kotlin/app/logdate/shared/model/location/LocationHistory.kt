package app.logdate.shared.model.location

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

@Serializable
data class LocationObservation(
    val id: String,
    val ownerId: String,
    val deviceId: String,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val activity: TravelMode = TravelMode.UNKNOWN,
    val timeZoneId: String? = null,
    val source: String = "UNKNOWN",
    val isMock: Boolean = false,
    val altitudeMeters: Double? = null,
    val provider: String? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val elapsedRealtimeNanos: Long? = null,
)

@Serializable
enum class TravelMode {
    STILL,
    WALKING,
    RUNNING,
    CYCLING,
    VEHICLE,
    DRIVING,
    BUS,
    TRAIN,
    UNKNOWN,
}

@Serializable
data class ActivityObservation(
    val id: String,
    val ownerId: String,
    val deviceId: String,
    val timestamp: Instant,
    val activityType: String,
    val transitionType: String,
    val timeZoneId: String? = null,
)

@Serializable
enum class PlaceCategory {
    HOME,
    WORK,
    FOOD,
    COFFEE,
    SHOPPING,
    OUTDOORS,
    FITNESS,
    TRANSIT,
    LODGING,
    HEALTH,
    ENTERTAINMENT,
    OTHER,
}

/**
 * A named place a visit is matched to.
 *
 * @property name The place's own name, such as "Blue Bottle Coffee" or "Market Street", without
 *   wording about how close the person was. Records written before [nameQualifier] existed may
 *   still hold a localized "Near …" phrase here.
 * @property nameQualifier How the visit relates to [name], so each device can word it in its own
 *   language. Null for places recorded before the qualifier existed.
 */
@Serializable
data class SemanticPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val locality: String? = null,
    val externalId: String? = null,
    val userConfirmed: Boolean = false,
    val nameQualifier: PlaceNameQualifier? = null,
    val radiusMeters: Double = 75.0,
    val category: String? = null,
)

/** How a visit relates to the name of the [SemanticPlace] it is matched to. */
@Serializable
enum class PlaceNameQualifier {
    /** The person was at the named place. */
    AT,

    /** The person was near the named place, which is the closest named landmark or street. */
    NEAR,
}

sealed interface LocationDayItem {
    val id: String
    val start: Instant
    val end: Instant
    val evidenceIds: List<String>
}

data class PlaceVisit(
    override val id: String,
    override val start: Instant,
    override val end: Instant,
    override val evidenceIds: List<String>,
    val latitude: Double,
    val longitude: Double,
    val confirmedStay: Boolean,
    val place: SemanticPlace? = null,
    val memoryIds: List<String> = emptyList(),
    val uncertaintyRadiusMeters: Float = 50f,
    val confidence: Float = 1f,
    val sampleCount: Int = 0,
    val candidatePlaceIds: List<String> = emptyList(),
    val needsDisambiguation: Boolean = false,
    val displayZoneId: String? = null,
    val sourceDeviceId: String? = null,
) : LocationDayItem

data class JourneyLeg(
    override val id: String,
    override val start: Instant,
    override val end: Instant,
    override val evidenceIds: List<String>,
    val mode: TravelMode,
    val route: List<LocationObservation>,
    val userConfirmed: Boolean = false,
    val smoothedRoute: List<LocationObservation> = route,
    val distanceMeters: Double = 0.0,
    val endpointCoordinates: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null,
    val displayZoneId: String? = null,
    val sourceDeviceId: String? = null,
) : LocationDayItem

data class HistoryGap(
    override val id: String,
    override val start: Instant,
    override val end: Instant,
    override val evidenceIds: List<String> = emptyList(),
) : LocationDayItem

@Serializable(with = HistoryFieldSerializer::class)
enum class HistoryField {
    LABEL,
    PLACE,
    ACTIVITY,
    START,
    END,
    DELETE,
    SUGGESTED_PLACE,
    RADIUS,
    CATEGORY,
    MERGE_WITH,
    SPLIT_AT,
    UNKNOWN,
}

object HistoryFieldSerializer : KSerializer<HistoryField> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HistoryField", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: HistoryField,
    ) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): HistoryField {
        val raw = decoder.decodeString()
        return HistoryField.entries.firstOrNull { it.name == raw } ?: HistoryField.UNKNOWN
    }
}

/**
 * Immutable edits retain their parent IDs so concurrent changes remain distinguishable.
 *
 * @property createdAt When the person made the correction, when known. Corrections made on this
 *   device don't record it; one that arrives from another device keeps it.
 */
@Serializable
data class HistoryCorrection(
    val id: String,
    val targetEvidenceId: String,
    val field: HistoryField,
    val value: String,
    val supersedes: List<String> = emptyList(),
    val createdAt: Instant? = null,
)

/** @property createdAt When the entry was connected, when known; only a link from another device has it. */
@Serializable
data class VisitMemoryLink(
    val noteId: String,
    val targetEvidenceId: String,
    val createdAt: Instant? = null,
)

/**
 * A visit the person added to their history.
 *
 * @property latitude Where exactly the person was, when it differs from the place's center. A
 *   visit added on this device never has it; one from another device can. Both coordinates or neither.
 * @property createdAt When the visit was added, when known; only a visit from another device has it.
 */
@Serializable
data class ManualVisit(
    val id: String,
    val start: Instant,
    val end: Instant,
    val placeId: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Instant? = null,
) {
    init {
        require((latitude == null) == (longitude == null)) { "A visit's own location needs both a latitude and a longitude" }
    }
}

/**
 * Travel between two visits that the person's apps recorded. Nothing writes one yet; the record
 * type exists so that a device that does will find the others reading it.
 *
 * @property fromVisitId The manual visit the journey started from.
 * @property toVisitId The manual visit it ended at.
 */
@Serializable
data class HistoryJourney(
    val id: String,
    val start: Instant,
    val createdAt: Instant,
    val fromVisitId: String? = null,
    val toVisitId: String? = null,
    val end: Instant? = null,
    val mode: TravelMode? = null,
    val distanceMeters: Long? = null,
)

@Serializable
data class VisitMemoryContext(
    val evidenceId: String,
    val placeId: String?,
    val placeName: String?,
    val latitude: Double,
    val longitude: Double,
    val visitStart: Instant,
)

@Serializable
sealed interface HistoryPayload {
    @Serializable
    data class Observation(
        val value: LocationObservation,
    ) : HistoryPayload

    @Serializable
    data class Activity(
        val value: ActivityObservation,
    ) : HistoryPayload

    @Serializable
    data class Place(
        val value: SemanticPlace,
    ) : HistoryPayload

    @Serializable
    data class Correction(
        val value: HistoryCorrection,
    ) : HistoryPayload

    @Serializable
    data class MemoryLink(
        val value: VisitMemoryLink,
    ) : HistoryPayload

    @Serializable
    data class Manual(
        val value: ManualVisit,
    ) : HistoryPayload

    @Serializable
    data class Journey(
        val value: HistoryJourney,
    ) : HistoryPayload
}
