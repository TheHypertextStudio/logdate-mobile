package app.logdate.client.networking

import app.logdate.client.networking.saver.ConfigurableNetworkSaverModeProvider
import app.logdate.client.networking.saver.NetworkConnectionType
import app.logdate.client.networking.saver.NetworkSaverState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Validates the [DefaultDataUsagePolicy] logic for determining network usage restrictions.
 *
 * These tests ensure that the application correctly transitions between unrestricted,
 * conservative, and restricted data modes based on the combination of network type
 * (Wi-Fi, Cellular, etc.) and the system's data saver state. It also verifies the
 * behavior of extension functions that control specific features like media sync and
 * AI calls.
 */
class DefaultDataUsagePolicyTest {
    private val networkSaverProvider = ConfigurableNetworkSaverModeProvider()
    private val policy = DefaultDataUsagePolicy(networkSaverProvider)

    @Test
    fun `Bluetooth proxy remains restricted by Data Saver when a provider calls it unmetered`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.BLUETOOTH_PROXY, isMetered = false),
            )
            assertIs<DataUsageMode.Restricted>(policy.currentMode())
            assertEquals(DataRestriction.BACKGROUND_DATA_BLOCKED, policy.currentRestriction())
        }

    @Test
    fun `a watch's Bluetooth link through its phone is conservative`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.BLUETOOTH_PROXY),
            )

            assertIs<DataUsageMode.Conservative>(policy.currentMode())
        }

    @Test
    fun `the Bluetooth link with data saver on is restricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.BLUETOOTH_PROXY),
            )

            assertIs<DataUsageMode.Restricted>(policy.currentMode())
        }

    @Test
    fun `a network is validated unless there is none`() {
        assertTrue(NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.WIFI).isValidated)
        assertFalse(NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.NONE).isValidated)
    }

    @Test
    fun `no connection returns restricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.NONE),
            )
            assertIs<DataUsageMode.Restricted>(policy.currentMode())
        }

    @Test
    fun `cellular with data saver on returns restricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.CELLULAR),
            )
            assertIs<DataUsageMode.Restricted>(policy.currentMode())
        }

    @Test
    fun `cellular with data saver off returns conservative`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.CELLULAR),
            )
            assertIs<DataUsageMode.Conservative>(policy.currentMode())
        }

    @Test
    fun `wifi with data saver off returns unrestricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.WIFI),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.currentMode())
        }

    @Test
    fun `unmetered wifi with data saver on still allows backup`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.WIFI),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.currentMode())
        }

    @Test
    fun `ethernet with data saver off returns unrestricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.ETHERNET),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.currentMode())
        }

    @Test
    fun `unmetered ethernet with data saver on still allows backup`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.ETHERNET),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.currentMode())
        }

    @Test
    fun `metered wifi with background restriction does not pretend it is unmetered`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(true, NetworkConnectionType.WIFI, isMetered = true),
            )
            assertIs<DataUsageMode.Restricted>(policy.currentMode())
        }

    @Test
    fun `other connection with data saver off returns unrestricted`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.OTHER),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.currentMode())
        }

    @Test
    fun `policy flow emits on state change`() =
        runTest {
            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.WIFI),
            )
            assertIs<DataUsageMode.Unrestricted>(policy.policy.first())

            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = false, connectionType = NetworkConnectionType.CELLULAR),
            )
            assertIs<DataUsageMode.Conservative>(policy.policy.first())

            networkSaverProvider.setNetworkSaverState(
                NetworkSaverState(isDataSaverEnabled = true, connectionType = NetworkConnectionType.CELLULAR),
            )
            assertIs<DataUsageMode.Restricted>(policy.policy.first())
        }

    // Extension function tests

    @Test
    fun `should sync media only unrestricted`() {
        assertTrue(DataUsageMode.Unrestricted.shouldSyncMedia())
        assertFalse(DataUsageMode.Conservative.shouldSyncMedia())
        assertFalse(DataUsageMode.Restricted.shouldSyncMedia())
    }

    @Test
    fun `should sync metadata unrestricted and conservative`() {
        assertTrue(DataUsageMode.Unrestricted.shouldSyncMetadata())
        assertTrue(DataUsageMode.Conservative.shouldSyncMetadata())
        assertFalse(DataUsageMode.Restricted.shouldSyncMetadata())
    }

    @Test
    fun `should load full res images only unrestricted`() {
        assertTrue(DataUsageMode.Unrestricted.shouldLoadFullResImages())
        assertFalse(DataUsageMode.Conservative.shouldLoadFullResImages())
        assertFalse(DataUsageMode.Restricted.shouldLoadFullResImages())
    }

    @Test
    fun `should load reduced images only conservative`() {
        assertFalse(DataUsageMode.Unrestricted.shouldLoadReducedImages())
        assertTrue(DataUsageMode.Conservative.shouldLoadReducedImages())
        assertFalse(DataUsageMode.Restricted.shouldLoadReducedImages())
    }

    @Test
    fun `should prefetch images only unrestricted`() {
        assertTrue(DataUsageMode.Unrestricted.shouldPrefetchImages())
        assertFalse(DataUsageMode.Conservative.shouldPrefetchImages())
        assertFalse(DataUsageMode.Restricted.shouldPrefetchImages())
    }

    @Test
    fun `should allow ai calls unrestricted and conservative`() {
        assertTrue(DataUsageMode.Unrestricted.shouldAllowAICalls())
        assertTrue(DataUsageMode.Conservative.shouldAllowAICalls())
        assertFalse(DataUsageMode.Restricted.shouldAllowAICalls())
    }
}
