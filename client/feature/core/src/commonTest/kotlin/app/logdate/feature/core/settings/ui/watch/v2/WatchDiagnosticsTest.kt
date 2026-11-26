package app.logdate.feature.core.settings.ui.watch.v2

import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponse
import app.logdate.client.sync.datalayer.v2.WearCompatibility
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.WatchState
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshot
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class WatchDiagnosticsTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val snapshot =
        WatchDiagnosticSnapshot(
            connection = WatchConnectionState.Connected("Pixel Watch 5", "watch-node"),
            peer =
                WatchPeerSnapshot(
                    v2Active = true,
                    watch = WatchState(link = PeerLinkState.PAIRED, appVersionName = "0.2.0"),
                    features = setOf(WearFeatures.RECEIPTS, WearFeatures.LINK_CLOUD),
                    phoneVersion = "0.2.0",
                    watchVersion = "0.2.0",
                    compatibility = WearCompatibility.Compatible("0.2.0", "0.2.0"),
                ),
            associated = true,
            reply =
                WatchDiagnosticsResponse(
                    context = PeerDeliveryContext("key", "phone", "watch", LinkMode.PAIR),
                    nonce = "echo",
                    notificationsAllowed = true,
                    microphoneAllowed = true,
                    appVersionName = "0.2.0",
                ),
            watchName = "Pixel Watch 5",
        )

    @Test
    fun `missing watch node exposes no dependent checks`() {
        assertEquals(emptyList(), WatchDiagnostics.evaluate(snapshot.copy(connection = WatchConnectionState.NoPairedWatch)))
        assertEquals(emptyList(), WatchDiagnostics.evaluate(snapshot.copy(connection = WatchConnectionState.Connected("Watch"))))
    }

    @Test
    fun `verified healthy snapshot passes all four consolidated checks`() {
        val checks = WatchDiagnostics.evaluate(snapshot)
        assertEquals(WatchDiagnosticCheck.entries, checks.map { it.check })
        assertEquals(List(4) { WatchDiagnosticOutcome.PASSED }, checks.map { it.outcome })
        assertEquals(WatchDiagnosticDetail.VersionSame("0.2.0"), checks.single { it.check == WatchDiagnosticCheck.APP_VERSION }.detail)
    }

    @Test
    fun `unresponsive watch offers open on watch and puts dependent checks in waiting state`() {
        val checks = WatchDiagnostics.evaluate(snapshot.copy(reply = null))
        val app = checks.single { it.check == WatchDiagnosticCheck.APP_VERSION }
        val connection = checks.single { it.check == WatchDiagnosticCheck.CONNECTION }
        val sync = checks.single { it.check == WatchDiagnosticCheck.SYNC }
        val notifications = checks.single { it.check == WatchDiagnosticCheck.NOTIFICATIONS }

        assertEquals(WatchDiagnosticOutcome.PASSED, app.outcome)
        assertEquals(WatchDiagnosticOutcome.NEEDS_ACTION, connection.outcome)
        assertEquals(WatchDiagnosticFix.OPEN_WATCH, connection.fix)
        assertEquals(WatchDiagnosticOutcome.WAITING, sync.outcome)
        assertEquals(WatchDiagnosticOutcome.WAITING, notifications.outcome)
    }

    @Test
    fun `watch or phone version skew surfaces actionable update fix without cascading false errors`() {
        val watchOutdated =
            WatchDiagnostics.evaluate(
                snapshot.copy(
                    compatibility = WearCompatibility.WatchUpdateRequired("0.3.0", "0.1.0"),
                ),
            )
        val appStep = watchOutdated.single { it.check == WatchDiagnosticCheck.APP_VERSION }
        assertEquals(WatchDiagnosticOutcome.NEEDS_ACTION, appStep.outcome)
        assertEquals(WatchDiagnosticFix.UPDATE_WATCH, appStep.fix)
        assertEquals(WatchDiagnosticOutcome.WAITING, watchOutdated.single { it.check == WatchDiagnosticCheck.CONNECTION }.outcome)

        val phoneOutdated =
            WatchDiagnostics.evaluate(
                snapshot.copy(
                    compatibility = WearCompatibility.PhoneUpdateRequired("0.1.0", "0.3.0"),
                ),
            )
        val phoneStep = phoneOutdated.single { it.check == WatchDiagnosticCheck.APP_VERSION }
        assertEquals(WatchDiagnosticOutcome.NEEDS_ACTION, phoneStep.outcome)
        assertEquals(WatchDiagnosticFix.UPDATE_PHONE, phoneStep.fix)
    }

    @Test
    fun `pending captures and disabled notifications offer concrete repairs`() {
        val checks =
            WatchDiagnostics.evaluate(
                snapshot.copy(
                    peer = snapshot.peer.copy(watch = snapshot.peer.watch!!.copy(outboxCount = 2)),
                    reply = snapshot.reply!!.copy(notificationsAllowed = false),
                ),
            )
        assertEquals(WatchDiagnosticFix.SYNC, checks.single { it.check == WatchDiagnosticCheck.SYNC }.fix)
        assertEquals(WatchDiagnosticFix.OPEN_WATCH, checks.single { it.check == WatchDiagnosticCheck.NOTIFICATIONS }.fix)
    }

    @Test
    fun `unknown installation keeps dependent checks waiting and uses confirmMissingAppAndInstall`() =
        runTest {
            val unknownSnapshot =
                snapshot.copy(
                    connection = WatchConnectionState.AppUnknown("Pixel Watch 5", "watch-node", knownInstalled = false),
                    reply = null,
                    peer = WatchPeerSnapshot.Inactive,
                )
            val confirmed = mutableListOf<String>()
            val manager =
                object : WatchConnectionManager {
                    override fun observeConnectionState(): Flow<WatchConnectionState> =
                        MutableStateFlow(unknownSnapshot.connection).asStateFlow()

                    override suspend fun beginAssociation() = Unit

                    override suspend fun requestSync() = Unit

                    override suspend fun installAppOnWatch() = WatchRequestResult.Sent

                    override suspend fun openAppOnWatch() = WatchRequestResult.Sent

                    override suspend fun confirmMissingAppAndInstall(nodeId: String): WatchRequestResult {
                        confirmed += nodeId
                        return WatchRequestResult.Sent
                    }
                }
            val viewModel =
                WatchDiagnosticsViewModel(
                    access = WatchDiagnosticsAccess { unknownSnapshot },
                    manager = manager,
                    postFixRecheckDelayMs = 0L,
                )

            advanceUntilIdle()
            val ready = assertIs<WatchDiagnosticsState.Ready>(viewModel.state.value)
            val appStep = ready.steps.single { it.check == WatchDiagnosticCheck.APP_VERSION }
            assertEquals(WatchDiagnosticFix.INSTALL, appStep.fix)
            assertEquals(WatchDiagnosticOutcome.WAITING, ready.steps.single { it.check == WatchDiagnosticCheck.CONNECTION }.outcome)

            viewModel.fix(WatchDiagnosticFix.INSTALL)
            // Refreshing in place must never blow away Ready back into Checking.
            assertIs<WatchDiagnosticsState.Ready>(viewModel.state.value)
            advanceUntilIdle()

            assertEquals(listOf("watch-node"), confirmed)
            val updated = assertIs<WatchDiagnosticsState.Ready>(viewModel.state.value)
            assertEquals(WatchDiagnosticFeedback.Sent(WatchDiagnosticFix.INSTALL), updated.feedback)
        }
}
