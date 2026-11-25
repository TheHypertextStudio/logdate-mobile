package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSetupCoordinatorTest {
    @Test
    fun `installed app is ready without prompting or opening Play`() =
        runTest {
            val manager = Manager(WatchConnectionState.Connected("Watch"))
            assertEquals(listOf(WatchSetupState.Ready), DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList())
            assertEquals(0, manager.installCalls)
            assertEquals(0, manager.associationCalls)
        }

    @Test
    fun `installation waits for app arrival before advancing`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppNotInstalled("Watch"))
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList(states) }
            runCurrent()
            assertEquals(listOf(WatchSetupState.OpeningPlayOnWatch, WatchSetupState.WaitingForInstall), states)
            manager.state.value = WatchConnectionState.Connected("Watch")
            job.join()
            assertEquals(WatchSetupState.Ready, states.last())
            assertEquals(1, manager.installCalls)
        }

    @Test
    fun `failed Play request does not wait or claim success`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppNotInstalled("Watch"))
            manager.installResult = WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND)
            assertEquals(
                listOf(WatchSetupState.OpeningPlayOnWatch, WatchSetupState.Failed),
                DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList(),
            )
        }

    @Test
    fun `cancelling installation wait never emits a failure or ready state`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppNotInstalled("Watch"))
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList(states) }
            runCurrent()
            job.cancel()
            job.join()
            assertEquals(listOf(WatchSetupState.OpeningPlayOnWatch, WatchSetupState.WaitingForInstall), states)
        }

    @Test
    fun `installed app waits for verified setup and times out without false ready`() =
        runTest {
            val manager = Manager(WatchConnectionState.Connected("Watch"))
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { false }.addToWatch().toList(states) }
            runCurrent()
            assertEquals(listOf<WatchSetupState>(WatchSetupState.SettingUp), states)
            advanceTimeBy(120_000)
            job.join()
            assertEquals(listOf(WatchSetupState.SettingUp, WatchSetupState.Failed), states)
        }

    @Test
    fun `verified setup completes waiting installed app without another prompt`() =
        runTest {
            val manager = Manager(WatchConnectionState.Connected("Watch"))
            var verified = false
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { verified }.addToWatch().toList(states) }
            runCurrent()
            verified = true
            advanceTimeBy(500)
            job.join()
            assertEquals(listOf(WatchSetupState.SettingUp, WatchSetupState.Ready), states)
            assertEquals(0, manager.installCalls)
        }

    @Test
    fun `another installed watch cannot finish the selected watch installation`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppNotInstalled("Watch B", "watch-b"))
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList(states) }
            runCurrent()
            manager.state.value = WatchConnectionState.Connected("Watch A", "watch-a")
            runCurrent()
            assertEquals(listOf(WatchSetupState.OpeningPlayOnWatch, WatchSetupState.WaitingForInstall), states)
            manager.state.value = WatchConnectionState.Connected("Watch B", "watch-b")
            job.join()
            assertEquals(WatchSetupState.Ready, states.last())
        }

    @Test fun `unknown first time setup asks before claiming absence or opening Play`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppUnknown("Watch", "watch-node"))
            assertEquals(
                listOf(WatchSetupState.CheckOnWatch("Watch", "watch-node", false)),
                DefaultWatchSetupCoordinator(manager) {
                    false
                }.addToWatch().toList(),
            )
            assertEquals(0, manager.installCalls)
        }

    @Test fun `known installed history never opens Play even after missing confirmation`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppUnknown("Watch", "watch-node", knownInstalled = true))
            assertEquals(
                listOf(WatchSetupState.CheckOnWatch("Watch", "watch-node", true)),
                DefaultWatchSetupCoordinator(manager) {
                    false
                }.addAfterMissingConfirmation("watch-node").toList(),
            )
            assertEquals(0, manager.installCalls)
        }

    @Test fun `confirmed first time setup waits for this exact watch to become installed`() =
        runTest {
            val manager = Manager(WatchConnectionState.AppUnknown("Watch", "watch-node"))
            val states = mutableListOf<WatchSetupState>()
            val job = launch { DefaultWatchSetupCoordinator(manager) { true }.addAfterMissingConfirmation("watch-node").toList(states) }
            runCurrent()
            assertEquals(listOf(WatchSetupState.OpeningPlayOnWatch, WatchSetupState.WaitingForInstall), states)
            manager.state.value = WatchConnectionState.Connected("Other", "other")
            runCurrent()
            assertEquals(2, states.size)
            manager.state.value = WatchConnectionState.Connected("Watch", "watch-node")
            job.join()
            assertEquals(WatchSetupState.Ready, states.last())
        }

    @Test
    fun confirmedTargetChangeCannotPrepareAnotherWatch() =
        runTest {
            val manager = Manager(WatchConnectionState.Connected("Other watch", "watch-b"))
            val states = DefaultWatchSetupCoordinator(manager) { true }.addAfterMissingConfirmation("watch-a").toList()
            assertEquals(listOf(WatchSetupState.Failed), states)
            assertEquals(emptyList(), manager.preparedNodes)
            assertEquals(0, manager.installCalls)
        }

    @Test
    fun missingNodeIsNotImplicitConfirmation() =
        runTest {
            val manager = Manager(WatchConnectionState.AppUnknown("Watch"))
            val states = DefaultWatchSetupCoordinator(manager) { true }.addToWatch().toList()
            assertEquals(listOf(WatchSetupState.CheckOnWatch("Watch", null, false)), states)
            assertEquals(emptyList(), manager.preparedNodes)
            assertEquals(0, manager.installCalls)
        }

    @Test
    fun openOnWatchDelegatesToConnectionManager() =
        runTest {
            val manager = Manager(WatchConnectionState.AppUnknown("Watch", "watch-node"))
            val result = DefaultWatchSetupCoordinator(manager) { true }.openOnWatch("watch-node")
            assertEquals(WatchRequestResult.Sent, result)
            assertEquals(listOf<String?>("watch-node"), manager.openedNodes)
        }

    private class Manager(
        initial: WatchConnectionState,
    ) : WatchConnectionManager {
        val state = MutableStateFlow(initial)
        val preparedNodes = mutableListOf<String?>()
        val openedNodes = mutableListOf<String?>()
        var installCalls = 0
        var associationCalls = 0
        var installResult: WatchRequestResult = WatchRequestResult.Sent

        override fun observeConnectionState() = state

        override suspend fun prepareSetup(nodeId: String?) {
            preparedNodes += nodeId
        }

        override suspend fun beginAssociation() {
            associationCalls++
        }

        override suspend fun requestSync() = Unit

        override suspend fun installAppOnWatch(): WatchRequestResult {
            installCalls++
            return installResult
        }

        override suspend fun openAppOnWatch() = WatchRequestResult.Sent

        override suspend fun openAppOnWatch(nodeId: String?): WatchRequestResult {
            openedNodes += nodeId
            return WatchRequestResult.Sent
        }
    }
}
