package app.logdate.client.sync.v2

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.peer.state.ActivitySnapshot
import app.logdate.client.sync.peer.state.CampfireSnapshot
import app.logdate.client.sync.peer.state.LocalCapabilities
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.PhoneState
import app.logdate.client.sync.peer.state.Stamped
import app.logdate.client.sync.peer.state.StateDocument
import app.logdate.client.sync.peer.state.StateDocumentChannel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

data class PhoneStateParts(
    val cloud: CloudPresence = CloudPresence.UNKNOWN,
    val dayStartHour: Int = 0,
    val campfire: CampfireSnapshot? = null,
    val reminderHours: app.logdate.client.sync.peer.state.ReminderHours? = null,
    val activityAwareness: Boolean = false,
    val activity: ActivitySnapshot? = null,
)

/** The campfire as the phone computed it; the watch's window is too short to compute a long run itself. */
fun CampfireState.toSnapshot(at: Instant): CampfireSnapshot =
    CampfireSnapshot(phase = phase.name, runDays = runDays, litToday = loggedToday, computedAt = at.toEpochMilliseconds())

/**
 * Publishes this phone's state document whenever the Labs watch switch, account state or shared preferences
 * change, and advertises [WearProtocol.PHONE_CAPABILITY_V2] only while the switch is on.
 *
 * The document goes out whatever the switch says, because the watch takes the switch from it. It lists
 * the supported [features]. Preferences a watch wrote are handed to [adoptWatchPreferences],
 * which takes the entries that are later than this phone's.
 */
class PhoneStatePublisher(
    private val v2Enabled: Flow<Boolean>,
    private val channel: StateDocumentChannel,
    private val capabilities: LocalCapabilities,
    private val peerStates: PeerStateStore,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val sharedPreferences: Flow<Map<String, Stamped>> = flowOf(emptyMap()),
    private val lastReminderAt: Flow<Long?> = flowOf(null),
    private val adoptWatchReminderAt: suspend (String, Long) -> Unit = { _, _ -> },
    private val features: Set<String> = setOf(WearFeatures.PREFS),
    private val parts: Flow<PhoneStateParts> = flowOf(PhoneStateParts()),
    private val adoptWatchPreferences: suspend (Map<String, Stamped>) -> Unit = {},
    private val acceptsWatchPreferences: suspend (String, StateDocument) -> Boolean = { _, _ -> true },
    private val appVersionName: String? = null,
    private val appVersionCode: Int? = null,
    private val protocolVersion: Int? = null,
    private val minPeerProtocolVersion: Int? = null,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            catchUpWithWatches()
            launch { adoptPreferencesFromWatches() }
            var advertised: Boolean? = null
            combine(
                v2Enabled.distinctUntilChanged(),
                sharedPreferences.distinctUntilChanged(),
                parts.distinctUntilChanged(),
                lastReminderAt.distinctUntilChanged(),
            ) { enabled, shared, current, postedAt ->
                Publication(enabled, shared, current, postedAt)
            }.collect { (enabled, shared, current, postedAt) ->
                publish(enabled, shared, current, postedAt)
                if (enabled == advertised) return@collect
                if (updateCapability(enabled)) advertised = enabled
            }
        }

    /** Reads the documents the watches published while this phone's app was not running. */
    suspend fun catchUpWithWatches() {
        channel.peerDocuments().forEach { peer -> peerStates.accept(peer.nodeId, peer.decoded, PeerRole.WATCH) }
    }

    /** Republish complete current inputs; presence must never publish placeholder account/campfire state. */
    suspend fun refresh() {
        catchUpWithWatches()
        publish(v2Enabled.first(), sharedPreferences.first(), parts.first(), lastReminderAt.first())
    }

    suspend fun publish(
        v2Enabled: Boolean,
        shared: Map<String, Stamped> = emptyMap(),
        current: PhoneStateParts = PhoneStateParts(),
        postedAt: Long? = null,
    ) {
        val document =
            StateDocument(
                role = PeerRole.PHONE,
                writtenAt = clock.now().toEpochMilliseconds(),
                phone =
                    PhoneState(
                        // Activity awareness is listed only while it is on: a watch reads its absence,
                        // including from an older phone, as off.
                        flags =
                            buildMap {
                                put(FeatureFlag.WATCH_EXPERIENCE_V2.key, v2Enabled)
                                if (current.activityAwareness) put(FeatureFlag.ACTIVITY_AWARENESS.key, true)
                            },
                        zone = timeZone().id,
                        features = features,
                        cloud = current.cloud,
                        dayStartHour = current.dayStartHour,
                        campfire = current.campfire,
                        reminderHours =
                            current.reminderHours.takeIf {
                                v2Enabled && SharedPreferenceKey.RemindersEnabled.read(shared[SharedPreferenceKey.RemindersEnabled.name])
                            },
                        activity = current.activity.takeIf { current.activityAwareness },
                        appVersionName = appVersionName,
                        appVersionCode = appVersionCode,
                        protocolVersion = protocolVersion,
                        minPeerProtocolVersion = minPeerProtocolVersion,
                    ),
                shared = shared,
                lastReminderAt = postedAt,
            )
        if (!channel.publish(document)) {
            Napier.e(
                "Could not publish this phone's state; watches use the old Labs setting and preferences until the next change or restart",
            )
        }
    }

    private suspend fun updateCapability(v2Enabled: Boolean): Boolean {
        val capabilityUpdated =
            if (v2Enabled) {
                capabilities.add(WearProtocol.PHONE_CAPABILITY_V2)
            } else {
                capabilities.remove(WearProtocol.PHONE_CAPABILITY_V2)
            }
        if (!capabilityUpdated) {
            Napier.e(
                "Could not update ${WearProtocol.PHONE_CAPABILITY_V2}; watches use the old protocol until the next change or restart",
            )
        }
        return capabilityUpdated
    }

    private data class Publication(
        val enabled: Boolean,
        val shared: Map<String, Stamped>,
        val parts: PhoneStateParts,
        val postedAt: Long?,
    )

    private suspend fun adoptPreferencesFromWatches() {
        val adopted = mutableMapOf<String, Map<String, Stamped>>()
        peerStates
            .documents(PeerRole.WATCH)
            .collect { documents ->
                documents.forEach { (node, document) ->
                    if (!acceptsWatchPreferences(node, document)) return@forEach
                    if (adopted[node] != document.shared) {
                        adoptWatchPreferences(document.shared)
                        adopted[node] = document.shared
                    }
                    if (acceptsWatchPreferences(node, document)) {
                        document.lastReminderAt?.let { adoptWatchReminderAt(node, it) }
                    }
                }
            }
    }
}
