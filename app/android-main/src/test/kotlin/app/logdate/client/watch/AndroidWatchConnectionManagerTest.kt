package app.logdate.client.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import app.logdate.client.sync.WatchMessageSender
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.feature.core.settings.ui.watch.DiscoveredWatch
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchLinking
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ExecutionException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "Open LogDate on watch" sent an ACTION_MAIN intent, which RemoteActivityHelper refuses before
 * anything leaves the phone, and both buttons picked whichever watch was listed first. These pin
 * which watch each request goes to, which link it sends, and how each failure reads.
 */
class AndroidWatchConnectionManagerTest {
    private val withLogDate = watch(nodeId = "watch-with-logdate")
    private val withoutLogDate = watch(nodeId = "watch-without-logdate")
    private val nodeClient = mockk<NodeClient>()
    private val capabilityClient = mockk<CapabilityClient>()
    private val remoteActivityHelper = mockk<RemoteActivityHelper>()
    private val linkIntent = mockk<Intent>()
    private val sentLinks = mutableListOf<String>()
    private val accepted = mockk<ListenableFuture<Void>> { every { get(any(), any()) } returns null }
    private val sentMessages = mutableListOf<String>()
    private var messageDelivered = true

    @Test
    fun `open sends the Today link to the watch that has LogDate`() =
        runTest {
            connected(withoutLogDate, withLogDate)
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted

            val result = manager().openAppOnWatch()

            assertEquals(WatchRequestResult.Sent, result)
            assertEquals(listOf("studio.hypertext.logdate://watch/today"), sentLinks)
            verify(exactly = 1) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-with-logdate") }
        }

    @Test
    fun `open prepares watch link after opening LogDate on the watch`() =
        runTest {
            connected(withoutLogDate, withLogDate)
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted
            val prepared = mutableListOf<String>()

            assertEquals(WatchRequestResult.Sent, manager(prepareWatch = { prepared += it }).openAppOnWatch("watch-without-logdate"))
            assertEquals(listOf("watch-without-logdate"), prepared)
        }

    @Test
    fun `an unconfirmed advertisement never opens the store`() =
        runTest {
            connected(withoutLogDate)
            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.INSTALLATION_UNKNOWN), manager().installAppOnWatch())
            verify(exactly = 0) { remoteActivityHelper.startRemoteActivity(any(), any()) }
        }

    @Test
    fun `explicit first time missing app confirmation opens only that watch listing`() =
        runTest {
            connected(withLogDate, withoutLogDate)
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted
            assertEquals(WatchRequestResult.Sent, manager().confirmMissingAppAndInstall("watch-without-logdate"))
            assertEquals(listOf("market://details?id=studio.hypertext.logdate"), sentLinks)
            verify(exactly = 1) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-without-logdate") }
            verify(exactly = 0) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-with-logdate") }
        }

    @Test
    fun `installed watch never receives an install handoff even after confirmation`() =
        runTest {
            connected(withLogDate)
            val manager = manager()
            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.ALREADY_INSTALLED), manager.installAppOnWatch())
            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.ALREADY_INSTALLED), manager.installAppOnWatch("watch-with-logdate"))
            assertEquals(
                WatchRequestResult.Failed(WatchRequestFailure.ALREADY_INSTALLED),
                manager.confirmMissingAppAndInstall("watch-with-logdate"),
            )
            verify(exactly = 0) { remoteActivityHelper.startRemoteActivity(any(), any()) }
        }

    @Test
    fun `a target that disconnects cannot redirect installation to another watch`() =
        runTest {
            connected(withLogDate)
            assertEquals(
                WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED),
                manager().confirmMissingAppAndInstall("watch-without-logdate"),
            )
            verify(exactly = 0) { remoteActivityHelper.startRemoteActivity(any(), any()) }
        }

    @Test
    fun `opening an unconfirmed app stays on the selected watch without claiming absence`() =
        runTest {
            connected(withLogDate, withoutLogDate)
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted
            assertEquals(WatchRequestResult.Sent, manager().openAppOnWatch("watch-without-logdate"))
            verify(exactly = 1) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-without-logdate") }
            verify(exactly = 0) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-with-logdate") }
        }

    @Test
    fun `both requests report when no watch is connected`() =
        runTest {
            connected()
            val manager = manager()

            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED), manager.openAppOnWatch())
            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED), manager.installAppOnWatch())
        }

    @Test
    fun `a watch that refuses the request is reported`() =
        runTest {
            connected(withLogDate)
            val refused =
                mockk<ListenableFuture<Void>> {
                    every { get(any(), any()) } throws
                        ExecutionException(RemoteActivityHelper.RemoteIntentException("There was an error while starting remote activity."))
                }
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns refused

            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.OPEN_FAILED), manager().openAppOnWatch())
        }

    @Test
    fun `a Wearable API failure never claims no watch exists`() =
        runTest {
            every { nodeClient.connectedNodes } returns Tasks.forException(IllegalStateException("Wearable API unavailable"))

            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE), manager().openAppOnWatch())
        }

    @Test
    fun `a sync request goes to the watches running LogDate`() =
        runTest {
            manager().requestSync()

            assertEquals(listOf(WearAudioRequestPaths.SYNC_REQUEST_PATH), sentMessages)
        }

    @Test
    fun `a sync request no watch received fails with the reason`() =
        runTest {
            messageDelivered = false

            val error = assertFailsWith<IllegalStateException> { manager().requestSync() }

            assertEquals("No watch running LogDate received the sync request", error.message)
        }

    @Test
    fun `an actual remote timeout stays distinct from a refused open`() =
        runTest {
            connected(withLogDate)
            val silent = mockk<ListenableFuture<Void>> { every { get(any(), any()) } throws java.util.concurrent.TimeoutException() }
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns silent
            assertEquals(WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND), manager().openAppOnWatch())
        }

    private fun connected(vararg watches: Node) {
        every { nodeClient.connectedNodes } returns Tasks.forResult(watches.toList())
        val withApp = mockk<CapabilityInfo> { every { nodes } returns watches.filter { it === withLogDate }.toSet() }
        every { capabilityClient.getCapability("logdate_watch_app", CapabilityClient.FILTER_ALL) } returns Tasks.forResult(withApp)
    }

    @Test
    fun `setting up prepares the watch running LogDate even beside another connected node`() =
        runTest {
            connected(withoutLogDate, withLogDate)
            val prepared = mutableListOf<String>()

            manager(verifiedAppNodes = flowOf(emptySet()), prepareWatch = { prepared += it }).beginAssociation()

            assertEquals(listOf("watch-with-logdate"), prepared)
        }

    @Test
    fun `a watch with LogDate that is not linked yet is linked at once and says why it is waiting`() =
        runTest {
            connected(withLogDate)
            listening()
            val retried = mutableListOf<String>()
            val manager =
                manager(
                    verifiedAppNodes = flowOf(emptySet()),
                    linkStatus = { WatchLinking(WatchLinkProblem.RETRYING) },
                    retryWatch = { retried += it },
                    linkingScope = backgroundScope,
                )

            val first = manager.observeConnectionState().first()
            val again = manager.observeConnectionState().first()
            runCurrent()

            val expected =
                WatchConnectionState.NeedsAssociation(
                    "Pixel Watch",
                    "watch-with-logdate",
                    WatchLinking(WatchLinkProblem.RETRYING),
                    availableWatches = listOf(DiscoveredWatch("watch-with-logdate", "Pixel Watch", isNearby = true, appInstalled = true)),
                )
            assertEquals(expected, first)
            assertEquals(expected, again)
            assertEquals(listOf("watch-with-logdate"), retried)
        }

    @Test
    fun `a failure only the person can fix is not retried just because the page opened`() =
        runTest {
            connected(withLogDate)
            listening()
            val retried = mutableListOf<String>()
            val manager =
                manager(
                    verifiedAppNodes = flowOf(emptySet()),
                    linkStatus = { WatchLinking(WatchLinkProblem.DEVICE_LIMIT) },
                    retryWatch = { retried += it },
                    linkingScope = backgroundScope,
                )

            manager.observeConnectionState().first()
            runCurrent()

            assertTrue(retried.isEmpty())
        }

    @Test
    fun `a watch the person removed is not linked again until they ask`() =
        runTest {
            connected(withLogDate)
            listening()
            val retried = mutableListOf<String>()
            val manager =
                manager(
                    verifiedAppNodes = flowOf(emptySet()),
                    linkStatus = { WatchLinking(WatchLinkProblem.REMOVED) },
                    retryWatch = { retried += it },
                    linkingScope = backgroundScope,
                )

            val state = manager.observeConnectionState().first()
            runCurrent()

            assertEquals(WatchLinking(WatchLinkProblem.REMOVED), (state as WatchConnectionState.NeedsAssociation).linking)
            assertTrue(retried.isEmpty())
        }

    @Test
    fun `active watch ping links a connected watch even when capability advertisement is empty`() =
        runTest {
            connected(withoutLogDate)
            listening()
            val retried = mutableListOf<String>()
            val manager =
                manager(
                    verifiedAppNodes = flowOf(emptySet()),
                    linkStatus = { WatchLinking() },
                    retryWatch = { retried += it },
                    linkingScope = backgroundScope,
                    pingWatch = { it == "watch-without-logdate" },
                )

            val state = manager.observeConnectionState().first()
            runCurrent()

            assertEquals(
                WatchConnectionState.NeedsAssociation(
                    "Pixel Watch",
                    "watch-without-logdate",
                    WatchLinking(),
                    availableWatches =
                        listOf(
                            DiscoveredWatch(
                                "watch-without-logdate",
                                "Pixel Watch",
                                isNearby = true,
                                appInstalled = true,
                            ),
                        ),
                ),
                state,
            )
            assertEquals(listOf("watch-without-logdate"), retried)
        }

    @Test
    fun `active watch ping reports already installed instead of unknown`() =
        runTest {
            connected(withoutLogDate)
            val manager = manager(pingWatch = { it == "watch-without-logdate" })

            assertEquals(
                WatchRequestResult.Failed(WatchRequestFailure.ALREADY_INSTALLED),
                manager.installAppOnWatch("watch-without-logdate"),
            )
        }

    @Test
    fun `uninstalling LogDate on a previously linked watch evicts stale install state and lets install reopen Play`() =
        runTest {
            var installedOnWatch = true
            every { nodeClient.connectedNodes } returns Tasks.forResult(listOf(withLogDate))
            every { capabilityClient.getCapability("logdate_watch_app", CapabilityClient.FILTER_ALL) } answers {
                val nodes = if (installedOnWatch) setOf(withLogDate) else emptySet()
                Tasks.forResult(mockk<CapabilityInfo> { every { this@mockk.nodes } returns nodes })
            }
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted
            listening()
            val manager =
                manager(
                    verifiedAppNodes = flowOf(setOf(withLogDate.id)),
                    pingWatch = { installedOnWatch },
                )

            assertEquals(WatchConnectionState.Connected("Pixel Watch", withLogDate.id), manager.observeConnectionState().first())

            installedOnWatch = false
            manager.refresh()

            assertEquals(
                WatchConnectionState.AppUnknown(
                    watchName = "Pixel Watch",
                    nodeId = withLogDate.id,
                    knownInstalled = false,
                    isNearby = true,
                    availableWatches = listOf(DiscoveredWatch(withLogDate.id, "Pixel Watch", isNearby = true, appInstalled = false)),
                ),
                manager.observeConnectionState().first(),
            )
            assertEquals(WatchRequestResult.Sent, manager.confirmMissingAppAndInstall(withLogDate.id))
            verify(exactly = 1) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-with-logdate") }
        }

    @Test
    fun `updateAppOnWatch opens Play listing on a connected watch that already has LogDate`() =
        runTest {
            connected(withLogDate)
            every { remoteActivityHelper.startRemoteActivity(linkIntent, any()) } returns accepted

            assertEquals(WatchRequestResult.Sent, manager().updateAppOnWatch(withLogDate.id))
            assertEquals(listOf("market://details?id=studio.hypertext.logdate"), sentLinks)
            verify(exactly = 1) { remoteActivityHelper.startRemoteActivity(linkIntent, "watch-with-logdate") }
        }

    private fun listening() {
        every { capabilityClient.addListener(any(), any<String>()) } returns Tasks.forResult(null)
        every { capabilityClient.removeListener(any(), any<String>()) } returns Tasks.forResult(true)
    }

    @AfterTest
    fun tearDown() = Unit

    private fun manager(
        verifiedAppNodes: Flow<Set<String>?> = flowOf(null),
        prepareWatch: suspend (String) -> Unit = {},
        linkStatus: suspend (String) -> WatchLinking = { WatchLinking() },
        retryWatch: suspend (String) -> Unit = {},
        linkingScope: CoroutineScope? = null,
        pingWatch: suspend (String) -> Boolean = { false },
    ) = AndroidWatchConnectionManager(
        context = mockk<Context> { every { packageName } returns "studio.hypertext.logdate" },
        associationManager =
            mockk(relaxed = true) {
                every { observeAssociationState() } returns MutableStateFlow(WatchAssociationSnapshot.Unsupported)
            },
        verifiedAppNodes = verifiedAppNodes,
        prepareWatch = prepareWatch,
        linkStatus = linkStatus,
        retryWatch = retryWatch,
        linkingScope = linkingScope,
        pingWatch = pingWatch,
        ioDispatcher = Dispatchers.Unconfined,
        nodeClient = nodeClient,
        capabilityClient = capabilityClient,
        messageSender =
            WatchMessageSender { path, _ ->
                sentMessages += path
                messageDelivered
            },
        remoteActivityHelper = remoteActivityHelper,
        linkIntent = { link ->
            sentLinks += link
            linkIntent
        },
    )

    private fun watch(nodeId: String) =
        mockk<Node> {
            every { id } returns nodeId
            every { displayName } returns "Pixel Watch"
            every { isNearby } returns true
        }
}
