package app.logdate.client.reminders

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.reminders.PhoneReminderEnvironment
import app.logdate.client.domain.reminders.ReminderWatch
import app.logdate.client.domain.streak.DEFAULT_STREAK_DAY_START_HOUR
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.WatchPreferencesRepository
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import app.logdate.shared.config.LogDateConfigRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PhoneReminderEnvironmentReader(
    private val sessions: SessionStorage,
    private val config: LogDateConfigRepository,
    private val preferences: LogdatePreferencesDataSource,
    private val shared: WatchPreferencesRepository,
    private val flags: FeatureFlagStore,
    private val peers: PhoneDeliveryPeers,
    private val states: PeerStateStore,
    private val connectedNodes: suspend () -> Set<String>,
    private val locationAllowed: () -> Boolean = { false },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    suspend fun read(): PhoneReminderEnvironment {
        val bound = sessions.getOriginBoundSession()
        val session = sessions.getSession()
        val backend = config.getCurrentBackendUrl()
        val audience =
            when {
                session == null && bound == null -> {
                    "local"
                }

                bound != null && bound.session == session && bound.origin == backend && bound.session.accountId.isNotBlank() -> {
                    Json.encodeToString(listOf("cloud", bound.origin, bound.session.accountId))
                }

                else -> {
                    ""
                }
            }
        val enabled = flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) && shared.get(SharedPreferenceKey.RemindersEnabled)
        val capturePlaces = shared.get(SharedPreferenceKey.CapturePlaces) && locationAllowed()
        val dayStart = (preferences.getPreferences().dayStartHour ?: DEFAULT_STREAK_DAY_START_HOUR).coerceIn(0, 23)
        val watch = if (enabled && audience.isNotBlank()) readWatch() else null
        val stable =
            sessions.getSession() == session && sessions.getOriginBoundSession() == bound && config.getCurrentBackendUrl() == backend
        return PhoneReminderEnvironment(
            audience = if (stable) audience else "",
            enabled = stable && audience.isNotBlank() && enabled,
            capturePlaces = capturePlaces,
            zone = zone(),
            dayStartHour = dayStart,
            watch = if (stable) watch else null,
        )
    }

    private suspend fun readWatch(): ReminderWatch? {
        val contexts = peers.all()
        if (contexts.isEmpty()) return null
        val reachable =
            try {
                connectedNodes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Could not read reminder watch reachability", e)
                emptySet()
            }
        val documents = states.documents(PeerRole.WATCH).first()
        return contexts
            .mapNotNull { context ->
                val watch = documents[context.watchNode]?.watch?.takeIf { it.linkId == context.linkId } ?: return@mapNotNull null
                ReminderWatch(
                    context,
                    context.watchNode in reachable,
                    WearFeatures.REMINDERS in watch.features,
                    WearFeatures.REMINDER_SUPPLEMENTS in watch.features,
                    WearFeatures.ACTIVITY_REMINDERS in watch.features,
                )
            }.sortedWith(
                compareByDescending<ReminderWatch> { it.reachable && it.supportsReminders }.thenBy { it.context.watchNode },
            ).firstOrNull()
    }
}
