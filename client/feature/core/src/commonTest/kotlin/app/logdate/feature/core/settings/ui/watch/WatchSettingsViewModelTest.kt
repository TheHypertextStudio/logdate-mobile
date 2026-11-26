package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.domain.watch.WatchNotificationSettings
import app.logdate.client.domain.watch.WatchSettingsRepository
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.WatchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [WatchSettingsViewModel].
 *
 * Verifies the coordination between the phone and a paired Wear OS device,
 * including association flows, connection state monitoring, and synchronization
 * of notifications and data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchSettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `begin association delegates to connection manager`() =
        runTest {
            val connectionManager = FakeWatchConnectionManager()
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager = connectionManager,
                    settingsRepository = FakeWatchSettingsRepository(),
                )

            viewModel.beginAssociation()
            advanceUntilIdle()

            assertEquals(1, connectionManager.beginAssociationCalls)
        }

    @Test
    fun `connection state is exposed from connection manager`() =
        runTest {
            val connectionManager =
                FakeWatchConnectionManager(
                    initialState = WatchConnectionState.NeedsAssociation("Pixel Watch"),
                )
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager = connectionManager,
                    settingsRepository = FakeWatchSettingsRepository(),
                )
            backgroundScope.launch { viewModel.connectionState.collect {} }
            advanceUntilIdle()

            assertEquals(
                WatchConnectionState.NeedsAssociation("Pixel Watch"),
                viewModel.connectionState.value,
            )
        }

    @Test
    fun `status combines the connection with what the watch reported`() =
        runTest {
            val report =
                WatchPeerSnapshot(
                    v2Active = true,
                    watch = WatchState(outboxCount = 3),
                    features = setOf(WearFeatures.RECEIPTS, WearFeatures.WINDOW),
                )
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager =
                        FakeWatchConnectionManager(
                            initialState = WatchConnectionState.Connected(watchName = "Pixel Watch"),
                        ),
                    settingsRepository = FakeWatchSettingsRepository(),
                    peerSnapshots = { flowOf(report) },
                    windowDays = flowOf(30),
                )
            backgroundScope.launch { viewModel.status.collect {} }
            advanceUntilIdle()

            assertEquals(
                WatchStatus.Ready(
                    watchName = "Pixel Watch",
                    nearby = true,
                    sync = WatchSyncLine(waiting = 3),
                    window = WatchWindowLine(days = 30),
                    capabilities = setOf(WearFeatures.RECEIPTS, WearFeatures.WINDOW),
                ),
                viewModel.status.value,
            )
        }

    @Test
    fun `the connection shows before the watch has reported anything`() =
        runTest {
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager =
                        FakeWatchConnectionManager(
                            initialState = WatchConnectionState.Connected(watchName = "Pixel Watch"),
                        ),
                    settingsRepository = FakeWatchSettingsRepository(),
                    peerSnapshots = { flow { awaitCancellation() } },
                )
            backgroundScope.launch { viewModel.status.collect {} }
            advanceUntilIdle()

            assertEquals(WatchStatus.Ready(watchName = "Pixel Watch", nearby = true), viewModel.status.value)
        }

    @Test
    fun `a request the watch could not take is shown until the next request`() =
        runTest {
            val connectionManager =
                FakeWatchConnectionManager(
                    openResult = WatchRequestResult.Failed(WatchRequestFailure.NOT_INSTALLED_ON_WATCH),
                )
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager = connectionManager,
                    settingsRepository = FakeWatchSettingsRepository(),
                )

            viewModel.openAppOnWatch()
            advanceUntilIdle()
            assertEquals(WatchRequestFailure.NOT_INSTALLED_ON_WATCH, viewModel.requestFailure.value)

            viewModel.installAppOnWatch()
            advanceUntilIdle()
            assertNull(viewModel.requestFailure.value)
            assertEquals(true, viewModel.installRequestSent.value)
        }

    @Test
    fun `failed install request never claims Play opened on the watch`() =
        runTest {
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager =
                        FakeWatchConnectionManager(
                            installResult = WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND),
                        ),
                    settingsRepository = FakeWatchSettingsRepository(),
                )

            viewModel.installAppOnWatch()
            advanceUntilIdle()

            assertEquals(false, viewModel.installRequestSent.value)
            assertEquals(WatchRequestFailure.WATCH_DID_NOT_RESPOND, viewModel.requestFailure.value)
        }

    @Test
    fun `updateAppOnWatch sends Play update request and moving into Connected clears transient setup flags`() =
        runTest {
            val connectionManager = FakeWatchConnectionManager(initialState = WatchConnectionState.AppUnknown("Pixel Watch", "watch-a"))
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager = connectionManager,
                    settingsRepository = FakeWatchSettingsRepository(),
                )
            backgroundScope.launch { viewModel.connectionState.collect {} }
            advanceUntilIdle()

            viewModel.updateAppOnWatch()
            advanceUntilIdle()
            assertEquals(true, viewModel.installRequestSent.value)
            assertEquals(listOf<String?>("watch-a"), connectionManager.updateRequests)

            connectionManager.emit(WatchConnectionState.Connected("Pixel Watch", "watch-a"))
            advanceUntilIdle()
            assertEquals(false, viewModel.installRequestSent.value)
            assertNull(viewModel.requestFailure.value)
        }

    @Test
    fun `sendingRequest and refreshing reflect in-flight calls and block duplicate taps`() =
        runTest {
            val installGate = CompletableDeferred<WatchRequestResult>()
            val refreshGate = CompletableDeferred<Unit>()
            val connectionManager =
                FakeWatchConnectionManager(
                    installAction = { installGate.await() },
                    refreshAction = { refreshGate.await() },
                )
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager = connectionManager,
                    settingsRepository = FakeWatchSettingsRepository(),
                )

            viewModel.installAppOnWatch()
            viewModel.installAppOnWatch()
            runCurrent()
            assertEquals(true, viewModel.sendingRequest.value)
            assertEquals(1, connectionManager.installRequests)

            installGate.complete(WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND))
            advanceUntilIdle()
            assertEquals(false, viewModel.sendingRequest.value)
            assertEquals(WatchRequestFailure.WATCH_DID_NOT_RESPOND, viewModel.requestFailure.value)

            viewModel.refresh()
            viewModel.refresh()
            runCurrent()
            assertEquals(true, viewModel.refreshing.value)
            assertEquals(1, connectionManager.refreshCalls)

            refreshGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(false, viewModel.refreshing.value)
        }

    @Test
    fun `failed retry after sent install clears installRequestSent`() =
        runTest {
            var calls = 0
            val viewModel =
                WatchSettingsViewModel(
                    connectionManager =
                        FakeWatchConnectionManager(
                            installAction = {
                                calls++
                                if (calls == 1) {
                                    WatchRequestResult.Sent
                                } else {
                                    WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND)
                                }
                            },
                        ),
                    settingsRepository = FakeWatchSettingsRepository(),
                )

            viewModel.installAppOnWatch()
            advanceUntilIdle()
            assertEquals(true, viewModel.installRequestSent.value)

            viewModel.installAppOnWatch()
            advanceUntilIdle()
            assertEquals(false, viewModel.installRequestSent.value)
            assertEquals(WatchRequestFailure.WATCH_DID_NOT_RESPOND, viewModel.requestFailure.value)
        }

    private class FakeWatchConnectionManager(
        initialState: WatchConnectionState = WatchConnectionState.Loading,
        private val installResult: WatchRequestResult = WatchRequestResult.Sent,
        private val openResult: WatchRequestResult = WatchRequestResult.Sent,
        private val installAction: (suspend () -> WatchRequestResult)? = null,
        private val refreshAction: (suspend () -> Unit)? = null,
    ) : WatchConnectionManager {
        private val state = MutableStateFlow(initialState)

        var beginAssociationCalls: Int = 0
        var installRequests: Int = 0
        var refreshCalls: Int = 0
        val updateRequests = mutableListOf<String?>()

        fun emit(newState: WatchConnectionState) {
            state.value = newState
        }

        override fun observeConnectionState(): Flow<WatchConnectionState> = state.asStateFlow()

        override suspend fun refresh() {
            refreshCalls++
            refreshAction?.invoke()
        }

        override suspend fun beginAssociation() {
            beginAssociationCalls++
        }

        override suspend fun requestSync() {
        }

        override suspend fun installAppOnWatch(): WatchRequestResult {
            installRequests++
            return installAction?.invoke() ?: installResult
        }

        override suspend fun updateAppOnWatch(nodeId: String?): WatchRequestResult {
            updateRequests += nodeId
            return installResult
        }

        override suspend fun openAppOnWatch(): WatchRequestResult = openResult
    }

    private class FakeWatchSettingsRepository : WatchSettingsRepository {
        private val notificationSettings = MutableStateFlow(WatchNotificationSettings())

        override fun observeNotificationSettings(): StateFlow<WatchNotificationSettings> = notificationSettings.asStateFlow()

        override suspend fun setShowEntryNotifications(enabled: Boolean) {
        }
    }
}
