package app.logdate.wear.v2.phone

import androidx.wear.phone.interactions.PhoneTypeHelper
import app.cash.turbine.test
import app.logdate.wear.sync.WearDataLayerClient
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneLinkRepositoryTest {
    private val capabilityClient = mockk<CapabilityClient>()
    private val nodeClient = mockk<NodeClient>()
    private val listener = slot<CapabilityClient.OnCapabilityChangedListener>()
    private var phoneType = PhoneTypeHelper.DEVICE_TYPE_ANDROID
    private val repository =
        PhoneLinkRepository(
            capabilityClient = capabilityClient,
            nodeClient = nodeClient,
            phoneType = { phoneType },
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    init {
        every { capabilityClient.addListener(capture(listener), WearDataLayerClient.PHONE_CAPABILITY) } returns Tasks.forResult<Void>(null)
        every { capabilityClient.removeListener(any(), WearDataLayerClient.PHONE_CAPABILITY) } returns Tasks.forResult(true)
        reachable()
        connected()
    }

    @Test
    fun `a reachable phone with LogDate is connected`() =
        runTest {
            reachable(node("phone-1", "Pixel 9"))

            assertEquals(PhoneLink.Connected(nodeId = "phone-1", phoneName = "Pixel 9"), repository.current())
        }

    @Test
    fun `a phone nearby is preferred over one reached through the cloud`() =
        runTest {
            reachable(node("cloud-phone", "Pixel 8", nearby = false), node("near-phone", "Pixel 9"))

            assertEquals(PhoneLink.Connected(nodeId = "near-phone", phoneName = "Pixel 9"), repository.current())
        }

    @Test
    fun `a missing advertisement does not establish app absence`() =
        runTest {
            connected(node("phone-1", "Pixel 9"))

            assertEquals(PhoneLink.AppUnknown(nodeId = "phone-1"), repository.current())
        }

    @Test
    fun `an iPhone is never asked to install LogDate`() =
        runTest {
            phoneType = PhoneTypeHelper.DEVICE_TYPE_IOS
            connected(node("iphone", "iPhone"))

            assertEquals(PhoneLink.Unsupported, repository.current())
        }

    @Test
    fun `a phone of unknown type without LogDate is not offered the app`() =
        runTest {
            phoneType = PhoneTypeHelper.DEVICE_TYPE_UNKNOWN
            connected(node("phone-1", "Phone"))

            assertEquals(PhoneLink.NotConnected, repository.current())
        }

    @Test
    fun `no phone in reach is not connected`() =
        runTest {
            assertEquals(PhoneLink.NotConnected, repository.current())
        }

    @Test
    fun `a failed lookup stays unknown`() =
        runTest {
            every {
                capabilityClient.getCapability(WearDataLayerClient.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            } returns Tasks.forException(IllegalStateException("Wearable API unavailable"))

            assertEquals(PhoneLink.Unknown, repository.current())
        }

    @Test
    fun `a lookup Play services never answers stays unknown`() =
        runTest {
            every {
                capabilityClient.getCapability(WearDataLayerClient.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            } returns TaskCompletionSource<CapabilityInfo>().task

            assertEquals(PhoneLink.Unknown, repository(UnconfinedTestDispatcher(testScheduler)).current())
        }

    @Test
    fun `the phone link is reported while Play services has not added the listener`() =
        runTest {
            connected(node("phone-1", "Pixel 9"))
            every { capabilityClient.addListener(any(), WearDataLayerClient.PHONE_CAPABILITY) } returns TaskCompletionSource<Void>().task

            repository(UnconfinedTestDispatcher(testScheduler)).observe().test {
                assertEquals(PhoneLink.AppUnknown(nodeId = "phone-1"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `LogDate arriving on the phone is noticed without polling`() =
        runTest {
            connected(node("phone-1", "Pixel 9"))

            repository.observe().test {
                assertEquals(PhoneLink.AppUnknown(nodeId = "phone-1"), awaitItem())

                reachable(node("phone-1", "Pixel 9"))
                listener.captured.onCapabilityChanged(mockk<CapabilityInfo>())

                assertEquals(PhoneLink.Connected(nodeId = "phone-1", phoneName = "Pixel 9"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun repository(dispatcher: CoroutineDispatcher) =
        PhoneLinkRepository(
            capabilityClient = capabilityClient,
            nodeClient = nodeClient,
            phoneType = { phoneType },
            ioDispatcher = dispatcher,
        )

    private fun reachable(vararg phones: Node) {
        val info = mockk<CapabilityInfo> { every { nodes } returns phones.toSet() }
        every {
            capabilityClient.getCapability(WearDataLayerClient.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        } returns Tasks.forResult(info)
    }

    private fun connected(vararg phones: Node) {
        every { nodeClient.connectedNodes } returns Tasks.forResult(phones.toList())
    }

    private fun node(
        nodeId: String,
        name: String,
        nearby: Boolean = true,
    ) = mockk<Node> {
        every { id } returns nodeId
        every { displayName } returns name
        every { isNearby } returns nearby
    }
}
