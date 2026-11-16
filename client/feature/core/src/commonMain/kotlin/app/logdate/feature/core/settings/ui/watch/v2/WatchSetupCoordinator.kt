package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WatchSetupState {
    data object ConfirmingOnPhone : WatchSetupState

    data object OpeningPlayOnWatch : WatchSetupState

    data object WaitingForInstall : WatchSetupState

    data object SettingUp : WatchSetupState

    data object Ready : WatchSetupState

    data object Failed : WatchSetupState

    data class CheckOnWatch(
        val watchName: String,
        val nodeId: String?,
        val knownInstalled: Boolean,
    ) : WatchSetupState
}

interface WatchSetupCoordinator {
    fun connection(): Flow<WatchConnectionState>

    fun addToWatch(): Flow<WatchSetupState>

    fun addAfterMissingConfirmation(nodeId: String): Flow<WatchSetupState> = flowOf(WatchSetupState.Failed)

    suspend fun isReady(connection: WatchConnectionState): Boolean = false
}

class DefaultWatchSetupCoordinator(
    private val manager: WatchConnectionManager,
    private val verifiedReady: suspend (WatchConnectionState) -> Boolean,
) : WatchSetupCoordinator {
    override fun connection(): Flow<WatchConnectionState> = manager.observeConnectionState()

    override suspend fun isReady(connection: WatchConnectionState): Boolean = verifiedReady(connection)

    override fun addToWatch(): Flow<WatchSetupState> = setup(null)

    override fun addAfterMissingConfirmation(nodeId: String): Flow<WatchSetupState> = setup(nodeId)

    private fun setup(confirmedNodeId: String?): Flow<WatchSetupState> =
        flow {
            try {
                val initial = withTimeoutOrNull(5_000) { settledConnection() }
                val early = earlyOutcome(initial, confirmedNodeId)
                if (early != null) {
                    emit(early)
                    return@flow
                }
                val ready = withTimeoutOrNull(120_000) { linkAndVerify(initial ?: settledConnection(), confirmedNodeId) }
                emit(if (ready == true) WatchSetupState.Ready else WatchSetupState.Failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e("Could not set up LogDate on the watch", failure)
                emit(WatchSetupState.Failed)
            }
        }

    private suspend fun settledConnection(): WatchConnectionState = connection().first { it != WatchConnectionState.Loading }

    /** The result to report before any setup step, when the watch is not the confirmed one or must be checked on the watch. */
    private fun earlyOutcome(
        initial: WatchConnectionState?,
        confirmedNodeId: String?,
    ): WatchSetupState? {
        if (confirmedNodeId != null && initial?.nodeId != confirmedNodeId) return WatchSetupState.Failed
        if (initial is WatchConnectionState.AppUnknown &&
            (confirmedNodeId == null || initial.knownInstalled || confirmedNodeId != initial.nodeId)
        ) {
            return WatchSetupState.CheckOnWatch(initial.watchName, initial.nodeId, initial.knownInstalled)
        }
        return null
    }

    /** Associates, installs and waits for the watch to report it is set up; false when a step cannot finish. */
    private suspend fun FlowCollector<WatchSetupState>.linkAndVerify(
        start: WatchConnectionState,
        confirmedNodeId: String?,
    ): Boolean {
        manager.prepareSetup(start.nodeId)
        val associated = confirmAssociation(start)
        val targetNode = start.nodeId ?: associated.nodeId
        val installed =
            when (associated) {
                is WatchConnectionState.AppUnknown -> installAfterMissingConfirmation(associated, confirmedNodeId, targetNode)
                is WatchConnectionState.AppNotInstalled -> installOnWatch(associated, targetNode)
                else -> associated
            }
        if (installed == null || !installed.hasApp()) return false
        awaitVerifiedReady(installed)
        return true
    }

    /** Asks the person to confirm the watch on this phone when the system has not associated it yet. */
    private suspend fun FlowCollector<WatchSetupState>.confirmAssociation(start: WatchConnectionState): WatchConnectionState {
        if (start !is WatchConnectionState.NeedsAssociation && start != WatchConnectionState.AssociationPending) return start
        emit(WatchSetupState.ConfirmingOnPhone)
        if (start is WatchConnectionState.NeedsAssociation) manager.beginAssociation()
        val targetNode = start.nodeId
        return connection().first {
            (targetNode == null || it.nodeId == targetNode) &&
                (it is WatchConnectionState.AppNotInstalled || it.hasApp())
        }
    }

    /** Installs on a watch whose missing app the person confirmed; null when that confirmation does not apply. */
    private suspend fun FlowCollector<WatchSetupState>.installAfterMissingConfirmation(
        current: WatchConnectionState.AppUnknown,
        confirmedNodeId: String?,
        targetNode: String?,
    ): WatchConnectionState? {
        if (current.nodeId != confirmedNodeId || current.knownInstalled) return null
        emit(WatchSetupState.OpeningPlayOnWatch)
        if (manager.confirmMissingAppAndInstall(checkNotNull(current.nodeId)) != WatchRequestResult.Sent) return null
        emit(WatchSetupState.WaitingForInstall)
        return connection().first { it.nodeId == targetNode && it.hasApp() }
    }

    /** Opens Play on a watch that reports LogDate is missing, then waits for the app; null when Play could not open. */
    private suspend fun FlowCollector<WatchSetupState>.installOnWatch(
        current: WatchConnectionState.AppNotInstalled,
        targetNode: String?,
    ): WatchConnectionState? {
        emit(WatchSetupState.OpeningPlayOnWatch)
        if (manager.installAppOnWatch(current.nodeId) != WatchRequestResult.Sent) return null
        emit(WatchSetupState.WaitingForInstall)
        return connection().first { (targetNode == null || it.nodeId == targetNode) && it.hasApp() }
    }

    private suspend fun FlowCollector<WatchSetupState>.awaitVerifiedReady(current: WatchConnectionState) {
        if (verifiedReady(current)) return
        emit(WatchSetupState.SettingUp)
        while (!verifiedReady(current)) delay(500)
    }

    private fun WatchConnectionState.hasApp(): Boolean = this is WatchConnectionState.Connected || this is WatchConnectionState.OutOfRange
}

object NoWatchSetupCoordinator : WatchSetupCoordinator {
    override fun connection(): Flow<WatchConnectionState> = flowOf(WatchConnectionState.NoPairedWatch)

    override fun addToWatch(): Flow<WatchSetupState> = flowOf(WatchSetupState.Failed)
}
