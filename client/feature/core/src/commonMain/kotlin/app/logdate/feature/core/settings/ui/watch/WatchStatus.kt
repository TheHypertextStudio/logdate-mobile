package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.sync.peer.state.WatchState
import kotlinx.coroutines.flow.Flow

/**
 * What the phone can say about its watch, for the Watch page and, later, the device card in Settings.
 *
 * A line in [Ready] stays null until the watch reports the value behind it under a feature it lists.
 * The phone never fills a line with a guess: an older watch, or one with the Labs switch off, shows
 * only its name and whether it is nearby.
 */
sealed interface WatchStatus {
    val nodeId: String? get() = null

    /** This device cannot connect to a Wear OS watch at all. */
    data object Unsupported : WatchStatus

    /** No watch is paired with this phone. */
    data object NoWatch : WatchStatus

    /** Discovery is unavailable, or a reachable watch has not reported its app installation. */
    data class Unknown(
        val watchName: String? = null,
        override val nodeId: String? = null,
        val knownInstalled: Boolean = false,
    ) : WatchStatus

    /** A watch is paired, and LogDate is not installed on it. */
    data class NotInstalled(
        val watchName: String,
        override val nodeId: String? = null,
    ) : WatchStatus

    /** LogDate is being linked with a paired watch. */
    data class SettingUp(
        val step: WatchSetupStep,
        override val nodeId: String? = null,
    ) : WatchStatus

    /**
     * LogDate is on the watch.
     *
     * @property capabilities The version 2 features the watch lists while version 2 is active; empty otherwise.
     */
    data class Ready(
        val watchName: String,
        val nearby: Boolean,
        val link: WatchLinkLine? = null,
        val sync: WatchSyncLine? = null,
        val window: WatchWindowLine? = null,
        val capabilities: Set<String> = emptySet(),
        override val nodeId: String? = null,
    ) : WatchStatus

    /** The watch reported a problem the person needs to know about. */
    data class NeedsAttention(
        val watchName: String,
        val nearby: Boolean,
        val issue: WatchIssue,
        override val nodeId: String? = null,
    ) : WatchStatus
}

sealed interface WatchSetupStep {
    /** LogDate still needs the system's permission to manage [watchName]. */
    data class PairWithPhone(
        val watchName: String,
    ) : WatchSetupStep

    /** The system pairing prompt is open on this phone. */
    data object ConfirmOnPhone : WatchSetupStep

    /**
     * LogDate is linking [watchName] by itself, with no prompt for the person.
     *
     * @property problem Null while linking is under way; otherwise why it has not worked yet.
     */
    data class Linking(
        val watchName: String,
        val problem: WatchLinkProblem? = null,
    ) : WatchSetupStep
}

enum class WatchLinkLine {
    /** The watch syncs through this phone only. */
    THROUGH_PHONE,

    /** The watch backs up to the cloud by itself. */
    ON_ITS_OWN,
}

/** @property waiting Moments on the watch that have not reached this phone yet. */
data class WatchSyncLine(
    val waiting: Int,
)

/** @property days How many days of moments the watch library covers. */
data class WatchWindowLine(
    val days: Int,
)

sealed interface WatchIssue {
    /** The watch lost its link to the account and needs to be set up again. */
    data object SetUpAgain : WatchIssue

    /** [count] moments failed to send; the watch tries them again by itself. */
    data class CapturesNotSent(
        val count: Int,
    ) : WatchIssue
}

/**
 * The watch's own report, as this phone last received it.
 *
 * @property v2Active Whether the Labs switch is on and the watch advertises the version 2 capability.
 * @property watch The watch-owned section of its latest state document, or null before one arrives.
 * @property features The version 2 features the watch lists.
 */
data class WatchPeerSnapshot(
    val v2Active: Boolean,
    val watch: WatchState?,
    val features: Set<String>,
) {
    companion object {
        val Inactive = WatchPeerSnapshot(v2Active = false, watch = null, features = emptySet())
    }
}

/** Where the watch's report comes from; on Android, the peer state the Data Layer delivers. */
fun interface WatchPeerSnapshotSource {
    fun observe(): Flow<WatchPeerSnapshot>

    fun observeFor(nodeId: String): Flow<WatchPeerSnapshot> = observe()
}
