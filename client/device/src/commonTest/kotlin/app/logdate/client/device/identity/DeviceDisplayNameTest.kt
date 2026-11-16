package app.logdate.client.device.identity

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceDisplayNameTest {
    @Test
    fun `system name is preferred when readable`() {
        assertEquals("Alice's Pixel", safeDeviceDisplayName(" Alice's Pixel ", "Android device"))
    }

    @Test
    fun `internal emulator models use a neutral name`() {
        assertEquals("Android device", safeDeviceDisplayName("sdk_gphone16k_arm64", "Android device"))
        assertEquals("Android device", safeDeviceDisplayName("Android SDK built for x86", "Android device"))
    }

    @Test
    fun `blank and unknown names use a neutral name`() {
        assertEquals("Android device", safeDeviceDisplayName(" ", "Android device"))
        assertEquals("Android device", safeDeviceDisplayName("unknown", "Android device"))
    }
}
