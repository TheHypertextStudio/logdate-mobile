package app.logdate.client.sync.v2.link

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.StateDocument

/** What the phone does next about one watch. */
sealed interface PhoneLinkDecision {
    data object Wait : PhoneLinkDecision

    data object Exchange : PhoneLinkDecision

    /** The watch reports the link the phone made; the phone shows its one confirmation. */
    data object Confirm : PhoneLinkDecision

    /**
     * The watch could not install the phone's last account link; the phone records why and waits. A watch
     * whose own sign-in only ran out is linked again at once instead, with nothing shown.
     */
    data class WatchProblem(
        val failure: PhoneLinkFailure,
    ) : PhoneLinkDecision

    companion object {
        /** A report of no link written this soon after an exchange is the watch's state from before it. */
        const val RELINK_GRACE_MS = 2 * 60_000L

        private val RELINK_STATES = setOf(PeerLinkState.UNLINKED, PeerLinkState.NEEDS_RELINK)

        fun decide(
            record: PhoneWatchLink?,
            plan: LinkPlan,
            document: StateDocument?,
            now: Long,
        ): PhoneLinkDecision {
            if (record == null) return Exchange
            val planChanged = record.mode != plan.mode || record.accountId != plan.accountId || record.serverOrigin != plan.serverOrigin
            // A pairing that stands in for an account link the server could not start is kept until its wait ends.
            if (planChanged && !record.waitsForAccount(plan, now)) return Exchange
            if (record.failure != null) return if (now >= record.nextAttemptAt) Exchange else Wait
            val hash = record.linkId?.let(LinkWire::decodeKey)
            if (hash == null || hash.size != 32 || LinkWire.encodeKey(hash) != record.linkId) return Exchange
            val watch = document?.watch
            val fresh = document != null && document.writtenAt > record.seenWatchDocAt
            val belongsToExchange = watch?.linkId == record.linkId
            val problem = watch?.linkProblem
            if (fresh && belongsToExchange && watch.link == PeerLinkState.NEEDS_RELINK && problem == LinkProblem.SESSION_EXPIRED.name) {
                return Exchange
            }
            if (fresh && belongsToExchange && watch.link == PeerLinkState.NEEDS_RELINK && problem != null) {
                return WatchProblem(failureFor(problem))
            }
            val watchAsks = fresh && watch != null && watch.link in RELINK_STATES && now - record.lastAttemptAt >= RELINK_GRACE_MS
            if (watchAsks) return Exchange
            if (fresh &&
                belongsToExchange &&
                (record.confirmedAt == null || record.attempts > 0) &&
                watch.link == linkedState(record.mode)
            ) {
                return Confirm
            }
            return Wait
        }

        private fun failureFor(problem: String): PhoneLinkFailure =
            when (problem) {
                LinkProblem.DEVICE_LIMIT_REACHED.name -> PhoneLinkFailure.DEVICE_LIMIT_REACHED
                LinkProblem.BOUND_TO_ANOTHER_ACCOUNT.name -> PhoneLinkFailure.WATCH_BOUND_TO_ANOTHER_ACCOUNT
                LinkProblem.KEY_CONFLICT.name -> PhoneLinkFailure.WATCH_KEY_CONFLICT
                else -> PhoneLinkFailure.WATCH_INSTALL_FAILED
            }

        private fun linkedState(mode: LinkMode): PeerLinkState =
            when (mode) {
                LinkMode.PAIR -> PeerLinkState.PAIRED
                LinkMode.CLOUD -> PeerLinkState.LINKED
            }
    }
}
