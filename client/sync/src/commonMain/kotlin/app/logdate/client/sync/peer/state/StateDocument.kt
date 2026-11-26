package app.logdate.client.sync.peer.state

import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class PeerRole {
    PHONE,
    WATCH,
}

/** A shared preference value with the time, by the writer's clock, it was written. */
@Serializable
data class Stamped(
    val value: JsonElement,
    val at: Long,
)

/** The campfire as the phone computed it; the watch's window is too short to compute a long run. */
@Serializable
data class CampfireSnapshot(
    val phase: String,
    val runDays: Int,
    val litToday: Boolean,
    val computedAt: Long,
)

/**
 * A device's activity under way and the last one that ended, as epoch milliseconds. Kinds travel as
 * names, so a kind a newer device adds reads as no activity instead of making the document unreadable.
 */
@Serializable
data class ActivitySnapshot(
    val kind: String? = null,
    val startedAt: Long? = null,
    val lastEndedKind: String? = null,
    val lastStartedAt: Long? = null,
    val lastEndedAt: Long? = null,
)

/**
 * Fields only the phone writes.
 *
 * @property flags Feature flag values by [app.logdate.client.datastore.featureflags.FeatureFlag.key].
 * @property features The version 2 features this phone supports, from [app.logdate.client.sync.datalayer.v2.WearFeatures].
 */
@Serializable
data class PhoneState(
    val flags: Map<String, Boolean> = emptyMap(),
    val cloud: CloudPresence = CloudPresence.UNKNOWN,
    val campfire: CampfireSnapshot? = null,
    val dayStartHour: Int = 0,
    val zone: String? = null,
    val openMarks: Int = 0,
    val features: Set<String> = emptySet(),
    val reminderHours: ReminderHours? = null,
    val activity: ActivitySnapshot? = null,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val protocolVersion: Int? = null,
    val minPeerProtocolVersion: Int? = null,
)

@Serializable
enum class PeerLinkState {
    UNLINKED,
    PAIRED,
    LINKED,
    NEEDS_RELINK,
}

@Serializable
enum class PresenceState {
    ON_WRIST,
    OFF_WRIST,
    UNKNOWN,
}

@Serializable
data class Presence(
    val state: PresenceState = PresenceState.UNKNOWN,
    val observedAt: Long = 0,
)

/**
 * Fields only the watch writes.
 *
 * @property linkProblem Why the last account link failed, as a `LinkProblem` name. It is a string so a
 *   value a newer watch adds never makes the document unreadable.
 * @property linkId The verified exchange this installation report belongs to.
 */
@Serializable
data class WatchState(
    val outboxCount: Int = 0,
    val deadLetterCount: Int = 0,
    val freeBytes: Long? = null,
    val link: PeerLinkState = PeerLinkState.UNLINKED,
    val presence: Presence = Presence(),
    val features: Set<String> = emptySet(),
    val linkProblem: String? = null,
    val linkId: String? = null,
    val sessionId: String? = null,
    val activity: ActivitySnapshot? = null,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val protocolVersion: Int? = null,
    val minPeerProtocolVersion: Int? = null,
)

/**
 * One device's state, written as a data item at [app.logdate.client.sync.datalayer.v2.WearPaths.STATE].
 *
 * Every field has one owner: a phone document's [watch] and a watch document's [phone] are ignored by
 * [StateMerger]. [shared] entries are written by both and merged last-writer-wins.
 */
@Serializable
data class StateDocument(
    val schema: Int = WearProtocol.STATE_SCHEMA,
    val role: PeerRole,
    val writtenAt: Long,
    val phone: PhoneState? = null,
    val watch: WatchState? = null,
    val shared: Map<String, Stamped> = emptyMap(),
    val lastReminderAt: Long? = null,
)

/** Reads and writes [StateDocument]s, enforcing their size limit. */
object StateDocumentCodec {
    /** The data item key the document's JSON is stored under. */
    const val KEY = "doc"

    /** Throws [IllegalArgumentException] for a document over [WearProtocol.MAX_STATE_BYTES]. */
    fun encode(document: StateDocument): String {
        val text = WearJson.encode(StateDocument.serializer(), document)
        val size = text.encodeToByteArray().size
        require(size <= WearProtocol.MAX_STATE_BYTES) { "A state document is $size bytes; the limit is ${WearProtocol.MAX_STATE_BYTES}" }
        return text
    }

    fun decode(text: String): Decoded<StateDocument> = WearJson.decode(text, WearProtocol.STATE_SCHEMA, StateDocument.serializer())

    fun toDataMap(document: StateDocument): Map<String, String> = mapOf(KEY to encode(document))

    fun fromDataMap(data: Map<String, String>): Decoded<StateDocument> {
        val text = data[KEY] ?: return Decoded.Malformed("The data item has no state document")
        return decode(text)
    }
}
