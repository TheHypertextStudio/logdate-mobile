package app.logdate.feature.core.settings.account.hosting

import app.logdate.feature.core.settings.account.ConnectedServer
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.ServerHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class HostingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val cloud =
        ConnectedServerInfo(
            origin = "https://cloud.logdate.app",
            displayName = "LogDate Cloud",
            isLogDateCloud = true,
            publishesIdentityChanges = false,
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a reachable server shows its version`() =
        runTest {
            val viewModel = HostingViewModel(FakeServer(cloud, ServerHealth.Reachable("1.4.0")))

            assertEquals(HostingUiState(server = cloud, health = ServerHealth.Reachable("1.4.0")), viewModel.state.value)
        }

    @Test
    fun `an unreachable server says so and can be checked again`() =
        runTest {
            val server = FakeServer(cloud, ServerHealth.Unreachable)
            val viewModel = HostingViewModel(server)

            assertEquals(ServerHealth.Unreachable, viewModel.state.value.health)

            server.health = ServerHealth.Reachable(null)
            viewModel.checkAgain()

            assertEquals(ServerHealth.Reachable(null), viewModel.state.value.health)
            assertEquals(2, server.checks)
        }

    @Test
    fun `a connected server change owns its own health check`() =
        runTest {
            val server = FakeServer(cloud, ServerHealth.Reachable("old"))
            val model = HostingViewModel(server)
            server.health = ServerHealth.Reachable("new")
            server.info.value = cloud.copy(origin = "https://journal.example")
            assertEquals(2, server.checks)
            assertEquals(ServerHealth.Reachable("new"), model.state.value.health)
        }

    @Test
    fun `checking retains the known server and health and coalesces repeats`() =
        runTest {
            val server = FakeServer(cloud, ServerHealth.Reachable("1"))
            val model = HostingViewModel(server)
            val pending = CompletableDeferred<ServerHealth>()
            server.pending = pending
            model.checkAgain()
            model.checkAgain()
            assertEquals(2, server.checks)
            assertEquals(cloud, model.state.value.server)
            assertEquals(ServerHealth.Reachable("1"), model.state.value.health)
            assertEquals(ServerCheckState.Checking, model.state.value.check)
            pending.complete(ServerHealth.Unreachable)
            assertEquals(ServerCheckState.Idle, model.state.value.check)
        }

    @Test
    fun `a thrown check failure stays local and retry clears it`() =
        runTest {
            val server = FakeServer(cloud, ServerHealth.Reachable("1"))
            val model = HostingViewModel(server)
            server.fail = true
            model.checkAgain()
            assertEquals(cloud, model.state.value.server)
            assertEquals(ServerHealth.Reachable("1"), model.state.value.health)
            assertIs<ServerCheckState.Failed>(model.state.value.check)
            server.fail = false
            model.checkAgain()
            assertEquals(ServerCheckState.Idle, model.state.value.check)
        }

    private class FakeServer(
        info: ConnectedServerInfo,
        var health: ServerHealth,
    ) : ConnectedServer {
        var checks = 0
        var pending: CompletableDeferred<ServerHealth>? = null
        var fail = false
        override val info = MutableStateFlow(info)

        override suspend fun refresh(): ServerHealth {
            checks++
            if (fail) error("Test server failure")
            return pending?.await() ?: health
        }
    }
}
