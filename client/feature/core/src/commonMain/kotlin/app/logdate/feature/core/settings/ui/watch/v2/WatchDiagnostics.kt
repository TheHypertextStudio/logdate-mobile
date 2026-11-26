package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponse
import app.logdate.client.sync.datalayer.v2.WearCompatibility
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
    val watchName: String? = null,
    val phoneVersion: String? = peer.phoneVersion,
    val watchVersion: String? = peer.watchVersion ?: reply?.appVersionName,
    val compatibility: WearCompatibility = peer.compatibility,
)

fun interface WatchDiagnosticsAccess {
    suspend fun inspect(): WatchDiagnosticSnapshot
}

enum class WatchDiagnosticOutcome { PASSED, NEEDS_ACTION, WAITING }

enum class WatchDiagnosticCheck { APP_VERSION, CONNECTION, SYNC, NOTIFICATIONS }

enum class WatchDiagnosticFix { PAIR, INSTALL, UPDATE_WATCH, UPDATE_PHONE, OPEN_WATCH, SYNC }

sealed interface WatchDiagnosticDetail {
    data class VersionSame(
        val version: String,
    ) : WatchDiagnosticDetail

    data class VersionCompatibleDifferent(
        val watchVersion: String,
        val phoneVersion: String,
    ) : WatchDiagnosticDetail

    data class AppInstalled(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class AppMissing(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class AppUnconfirmed(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class WatchNeedsUpdate(
        val watchVersion: String?,
        val phoneVersion: String?,
    ) : WatchDiagnosticDetail

    data class PhoneNeedsUpdate(
        val watchVersion: String?,
        val phoneVersion: String?,
    ) : WatchDiagnosticDetail

    data class ConnectionReady(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class ConnectionNeedsLink(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class ConnectionUnresponsive(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data class ConnectionWaitingOnApp(
        val watchName: String,
    ) : WatchDiagnosticDetail

    data object SyncUpToDate : WatchDiagnosticDetail

    data class SyncPending(
        val waiting: Int,
    ) : WatchDiagnosticDetail

    data class SyncFailed(
        val failed: Int,
    ) : WatchDiagnosticDetail

    data object SyncWaitingOnConnection : WatchDiagnosticDetail

    data object NotificationsAllowed : WatchDiagnosticDetail

    data object NotificationsDisabled : WatchDiagnosticDetail

    data object NotificationsWaitingOnConnection : WatchDiagnosticDetail
}

data class WatchDiagnosticStep(
    val check: WatchDiagnosticCheck,
    val outcome: WatchDiagnosticOutcome,
    val detail: WatchDiagnosticDetail,
    val fix: WatchDiagnosticFix? = null,
)

object WatchDiagnostics {
    fun evaluate(snapshot: WatchDiagnosticSnapshot): List<WatchDiagnosticStep> {
        val connection = snapshot.connection
        if (connection.nodeId.isNullOrBlank()) return emptyList()
        val watchName = snapshot.watchName ?: connection.displayWatchName() ?: "your watch"
        val appVersionStep = evaluateAppVersion(snapshot, watchName)
        val connectionStep = evaluateConnection(snapshot, watchName, appVersionStep.outcome == WatchDiagnosticOutcome.PASSED)
        val syncStep =
            evaluateSync(
                snapshot,
                appReady = appVersionStep.outcome == WatchDiagnosticOutcome.PASSED,
                connectionReady =
                    connectionStep.outcome == WatchDiagnosticOutcome.PASSED,
            )
        val notificationsStep = evaluateNotifications(snapshot, connectionReady = connectionStep.outcome == WatchDiagnosticOutcome.PASSED)
        return listOf(appVersionStep, connectionStep, syncStep, notificationsStep)
    }

    private fun evaluateAppVersion(
        snapshot: WatchDiagnosticSnapshot,
        watchName: String,
    ): WatchDiagnosticStep =
        when (val compatibility = snapshot.compatibility) {
            is WearCompatibility.WatchUpdateRequired -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.APP_VERSION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.WatchNeedsUpdate(compatibility.watchVersion, compatibility.phoneVersion),
                    fix = WatchDiagnosticFix.UPDATE_WATCH,
                )
            }

            is WearCompatibility.PhoneUpdateRequired -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.APP_VERSION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.PhoneNeedsUpdate(compatibility.watchVersion, compatibility.phoneVersion),
                    fix = WatchDiagnosticFix.UPDATE_PHONE,
                )
            }

            is WearCompatibility.Compatible -> {
                evaluateCompatibleAppVersion(snapshot, watchName)
            }
        }

    private fun evaluateCompatibleAppVersion(
        snapshot: WatchDiagnosticSnapshot,
        watchName: String,
    ): WatchDiagnosticStep =
        when (val connection = snapshot.connection) {
            is WatchConnectionState.AppNotInstalled -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.APP_VERSION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.AppMissing(watchName),
                    fix = WatchDiagnosticFix.INSTALL,
                )
            }

            is WatchConnectionState.AppUnknown -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.APP_VERSION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.AppUnconfirmed(watchName),
                    fix = if (connection.knownInstalled) WatchDiagnosticFix.OPEN_WATCH else WatchDiagnosticFix.INSTALL,
                )
            }

            else -> {
                val watchVer = snapshot.watchVersion
                val phoneVer = snapshot.phoneVersion
                val detail =
                    when {
                        !watchVer.isNullOrBlank() && !phoneVer.isNullOrBlank() && watchVer == phoneVer -> {
                            WatchDiagnosticDetail.VersionSame(watchVer)
                        }

                        !watchVer.isNullOrBlank() && !phoneVer.isNullOrBlank() -> {
                            WatchDiagnosticDetail.VersionCompatibleDifferent(watchVer, phoneVer)
                        }

                        else -> {
                            WatchDiagnosticDetail.AppInstalled(watchName)
                        }
                    }
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.APP_VERSION,
                    outcome = WatchDiagnosticOutcome.PASSED,
                    detail = detail,
                )
            }
        }

    private fun evaluateConnection(
        snapshot: WatchDiagnosticSnapshot,
        watchName: String,
        appReady: Boolean,
    ): WatchDiagnosticStep {
        if (!appReady) {
            return WatchDiagnosticStep(
                check = WatchDiagnosticCheck.CONNECTION,
                outcome = WatchDiagnosticOutcome.WAITING,
                detail = WatchDiagnosticDetail.ConnectionWaitingOnApp(watchName),
            )
        }
        val watch = snapshot.peer.watch
        val features = snapshot.peer.features
        val linked =
            snapshot.peer.v2Active &&
                WearFeatures.LINK_CLOUD in features &&
                (watch?.link == PeerLinkState.PAIRED || watch?.link == PeerLinkState.LINKED)
        return when {
            snapshot.connection is WatchConnectionState.NeedsAssociation ||
                snapshot.connection == WatchConnectionState.AssociationPending ||
                !snapshot.associated ||
                !linked -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.CONNECTION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.ConnectionNeedsLink(watchName),
                    fix = WatchDiagnosticFix.PAIR,
                )
            }

            snapshot.reply == null -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.CONNECTION,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.ConnectionUnresponsive(watchName),
                    fix = WatchDiagnosticFix.OPEN_WATCH,
                )
            }

            else -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.CONNECTION,
                    outcome = WatchDiagnosticOutcome.PASSED,
                    detail = WatchDiagnosticDetail.ConnectionReady(watchName),
                )
            }
        }
    }

    private fun evaluateSync(
        snapshot: WatchDiagnosticSnapshot,
        appReady: Boolean,
        connectionReady: Boolean,
    ): WatchDiagnosticStep {
        val watch = snapshot.peer.watch
        val features = snapshot.peer.features
        if (!appReady || !connectionReady || !snapshot.peer.v2Active || WearFeatures.RECEIPTS !in features || watch == null) {
            return WatchDiagnosticStep(
                check = WatchDiagnosticCheck.SYNC,
                outcome = WatchDiagnosticOutcome.WAITING,
                detail = WatchDiagnosticDetail.SyncWaitingOnConnection,
            )
        }
        return when {
            watch.deadLetterCount > 0 -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.SYNC,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.SyncFailed(watch.deadLetterCount),
                    fix = WatchDiagnosticFix.SYNC,
                )
            }

            watch.outboxCount > 0 -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.SYNC,
                    outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                    detail = WatchDiagnosticDetail.SyncPending(watch.outboxCount),
                    fix = WatchDiagnosticFix.SYNC,
                )
            }

            else -> {
                WatchDiagnosticStep(
                    check = WatchDiagnosticCheck.SYNC,
                    outcome = WatchDiagnosticOutcome.PASSED,
                    detail = WatchDiagnosticDetail.SyncUpToDate,
                )
            }
        }
    }

    private fun evaluateNotifications(
        snapshot: WatchDiagnosticSnapshot,
        connectionReady: Boolean,
    ): WatchDiagnosticStep {
        val reply = snapshot.reply
        if (!connectionReady || reply == null) {
            return WatchDiagnosticStep(
                check = WatchDiagnosticCheck.NOTIFICATIONS,
                outcome = WatchDiagnosticOutcome.WAITING,
                detail = WatchDiagnosticDetail.NotificationsWaitingOnConnection,
            )
        }
        return if (reply.notificationsAllowed) {
            WatchDiagnosticStep(
                check = WatchDiagnosticCheck.NOTIFICATIONS,
                outcome = WatchDiagnosticOutcome.PASSED,
                detail = WatchDiagnosticDetail.NotificationsAllowed,
            )
        } else {
            WatchDiagnosticStep(
                check = WatchDiagnosticCheck.NOTIFICATIONS,
                outcome = WatchDiagnosticOutcome.NEEDS_ACTION,
                detail = WatchDiagnosticDetail.NotificationsDisabled,
                fix = WatchDiagnosticFix.OPEN_WATCH,
            )
        }
    }
}

fun WatchConnectionState.displayWatchName(): String? =
    when (this) {
        is WatchConnectionState.Connected -> watchName

        is WatchConnectionState.OutOfRange -> watchName

        is WatchConnectionState.NeedsAssociation -> watchName

        is WatchConnectionState.AppNotInstalled -> watchName

        is WatchConnectionState.AppUnknown -> watchName

        WatchConnectionState.Loading,
        WatchConnectionState.NoPairedWatch,
        WatchConnectionState.AssociationPending,
        WatchConnectionState.Unavailable,
        -> null
    }
