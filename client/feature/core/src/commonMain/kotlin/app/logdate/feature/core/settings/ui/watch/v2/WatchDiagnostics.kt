package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponse
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshot

/** Every result is observed, never inferred from sending a message. */
data class WatchDiagnosticSnapshot(
    val connection: WatchConnectionState,
    val peer: WatchPeerSnapshot,
    val associated: Boolean,
    val reply: WatchDiagnosticsResponse?,
)

fun interface WatchDiagnosticsAccess {
    suspend fun inspect(): WatchDiagnosticSnapshot
}

enum class WatchDiagnosticOutcome { PASSED, FIXABLE, FAILED }

enum class WatchDiagnosticCheck { WATCH, APP, PROTOCOL, ASSOCIATION, LINK, PING, SYNC, NOTIFICATIONS }

enum class WatchDiagnosticFix { PAIR, INSTALL, UPDATE, OPEN_WATCH, SYNC }

data class WatchDiagnosticStep(
    val check: WatchDiagnosticCheck,
    val outcome: WatchDiagnosticOutcome,
    val fix: WatchDiagnosticFix? = null,
)

object WatchDiagnostics {
    fun evaluate(snapshot: WatchDiagnosticSnapshot): List<WatchDiagnosticStep> {
        val connection = snapshot.connection
        if (connection.nodeId.isNullOrBlank()) return emptyList()
        val installed = connection is WatchConnectionState.Connected || connection is WatchConnectionState.OutOfRange
        val seen =
            installed ||
                connection is WatchConnectionState.NeedsAssociation ||
                connection is WatchConnectionState.AppNotInstalled ||
                connection == WatchConnectionState.AssociationPending
        val watch = snapshot.peer.watch
        val features = snapshot.peer.features
        val linked =
            snapshot.peer.v2Active &&
                WearFeatures.LINK_CLOUD in features &&
                (watch?.link == PeerLinkState.PAIRED || watch?.link == PeerLinkState.LINKED)
        return listOf(
            step(WatchDiagnosticCheck.WATCH, seen, WatchDiagnosticFix.PAIR),
            step(
                WatchDiagnosticCheck.APP,
                installed,
                if (connection is WatchConnectionState.AppNotInstalled) WatchDiagnosticFix.INSTALL else WatchDiagnosticFix.OPEN_WATCH,
            ),
            step(WatchDiagnosticCheck.PROTOCOL, snapshot.peer.v2Active && watch != null, WatchDiagnosticFix.OPEN_WATCH),
            step(WatchDiagnosticCheck.ASSOCIATION, snapshot.associated, WatchDiagnosticFix.PAIR),
            step(WatchDiagnosticCheck.LINK, linked, WatchDiagnosticFix.OPEN_WATCH),
            step(WatchDiagnosticCheck.PING, snapshot.reply != null),
            when {
                !snapshot.peer.v2Active || WearFeatures.RECEIPTS !in features || watch == null -> step(WatchDiagnosticCheck.SYNC, false)
                watch.outboxCount == 0 && watch.deadLetterCount == 0 -> step(WatchDiagnosticCheck.SYNC, true)
                else -> step(WatchDiagnosticCheck.SYNC, false, WatchDiagnosticFix.SYNC)
            },
            step(
                WatchDiagnosticCheck.NOTIFICATIONS,
                snapshot.reply?.notificationsAllowed == true,
                WatchDiagnosticFix.OPEN_WATCH.takeIf { snapshot.reply != null },
            ),
        )
    }

    private fun step(
        check: WatchDiagnosticCheck,
        passed: Boolean,
        fix: WatchDiagnosticFix? = null,
    ) = WatchDiagnosticStep(
        check,
        if (passed) {
            WatchDiagnosticOutcome.PASSED
        } else if (fix !=
            null
        ) {
            WatchDiagnosticOutcome.FIXABLE
        } else {
            WatchDiagnosticOutcome.FAILED
        },
        fix.takeUnless { passed },
    )
}
