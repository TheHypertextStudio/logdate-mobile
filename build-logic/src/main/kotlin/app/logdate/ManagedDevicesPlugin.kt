@file:Suppress("UnstableApiUsage")

package app.logdate

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.ManagedVirtualDevice
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle Managed Device matrices for LogDate's Android apps, applied as
 * `id("app.logdate.managed-devices")` and chosen with `managedDeviceMatrix { phone() }`,
 * `wear()` or `nativeRestore()`.
 *
 * Instrumented tests only ever run on these emulators, never on a connected device. The phone
 * matrix follows the Studio device testing standard: the oldest supported and target-SDK phones, a
 * foldable, a tablet, a desktop, and one phone per API level where LogDate's code branches on
 * platform behavior. `-Plogdate.managedDeviceProfile=tabletOnly` or `=recoveryAcceptance` swaps the
 * phone matrix for the single-tablet coverage lane or the restore acceptance devices.
 */
class ManagedDevicesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("managedDeviceMatrix", ManagedDeviceMatrixExtension::class.java, project)
    }
}

open class ManagedDeviceMatrixExtension(
    private val project: Project,
) {
    fun phone() {
        val profile = project.providers.gradleProperty("logdate.managedDeviceProfile").orNull
        apply(
            when (profile) {
                "tabletOnly" -> tabletOnlyMatrix
                "recoveryAcceptance" -> recoveryAcceptanceMatrix
                else -> phoneMatrix
            },
        )
    }

    fun wear() = apply(wearMatrix)

    fun nativeRestore() = apply(nativeRestoreMatrix)

    private fun apply(matrix: DeviceMatrix) {
        if (!project.file("src/androidTest").exists()) return
        project.extensions.configure(ApplicationExtension::class.java) {
            testOptions.managedDevices {
                val devicesByName =
                    matrix.devices.associate { config ->
                        val device =
                            localDevices.findByName(config.name)
                                ?: localDevices.maybeCreate(config.name).apply {
                                    device = config.hardwareProfile
                                    apiLevel = config.apiLevel
                                    if (config.minorApiLevel != 0) {
                                        // AGP consumes this task input but does not expose it in the
                                        // public device interface. Fail on incompatible upgrades rather
                                        // than silently exercising Android 36 instead of 36.1.
                                        (this as com.android.build.gradle.internal.dsl.ManagedVirtualDevice).sdkMinorVersion =
                                            config.minorApiLevel
                                    }
                                    systemImageSource = config.systemImageSource
                                    pageAlignment = config.pageAlignment
                                }
                        config.name to device
                    }
                matrix.groups.forEach { (groupName, deviceNames) ->
                    if (groups.findByName(groupName) == null) {
                        groups.create(groupName) {
                            deviceNames.forEach { targetDevices.add(checkNotNull(devicesByName[it])) }
                        }
                    }
                }
            }
        }
    }
}

private data class DeviceConfig(
    val name: String,
    val hardwareProfile: String,
    val apiLevel: Int,
    val systemImageSource: String,
    val minorApiLevel: Int = 0,
    val pageAlignment: ManagedVirtualDevice.PageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES,
)

private class DeviceMatrix(
    val devices: List<DeviceConfig>,
    val groups: Map<String, List<String>>,
)

private val fourKbPages = ManagedVirtualDevice.PageAlignment.FORCE_4KB_PAGES

private val phoneMatrix =
    DeviceMatrix(
        devices =
            listOf(
                DeviceConfig("oldestSupportedPhoneApi30", "Pixel 2", 30, "google", pageAlignment = fourKbPages),
                DeviceConfig("phoneApi31", "Pixel 9 Pro", 31, "google", pageAlignment = fourKbPages),
                DeviceConfig("phoneApi33", "Pixel 9 Pro", 33, "google", pageAlignment = fourKbPages),
                DeviceConfig("phoneApi34", "Pixel 9 Pro", 34, "google", pageAlignment = fourKbPages),
                // Android 17: also used by the OS integration captures (./run screenshots:os).
                DeviceConfig("flagshipPhoneApi37", "Pixel 9 Pro", 37, "google"),
                DeviceConfig("foldableApi37", "Pixel 9 Pro Fold", 37, "google"),
                DeviceConfig("largeScreenTabletApi37", "Pixel Tablet", 37, "google"),
                DeviceConfig("desktopApi34", "Medium Desktop", 34, "android-desktop", pageAlignment = fourKbPages),
            ),
        groups =
            mapOf(
                "smokeDevices" to listOf("flagshipPhoneApi37", "largeScreenTabletApi37"),
                "phoneDevices" to listOf("oldestSupportedPhoneApi30", "flagshipPhoneApi37"),
                "tabletDevices" to listOf("largeScreenTabletApi37"),
                "deviceMatrix" to
                    listOf("oldestSupportedPhoneApi30", "flagshipPhoneApi37", "foldableApi37", "largeScreenTabletApi37", "desktopApi34"),
                "apiMatrix" to listOf("oldestSupportedPhoneApi30", "phoneApi31", "phoneApi33", "phoneApi34", "flagshipPhoneApi37"),
            ),
    )

private val tabletOnlyMatrix =
    DeviceMatrix(
        devices = listOf(DeviceConfig("largeScreenTabletApi35", "Pixel Tablet", 35, "google")),
        groups =
            mapOf(
                "smokeDevices" to listOf("largeScreenTabletApi35"),
                "tabletDevices" to listOf("largeScreenTabletApi35"),
                "deviceMatrix" to listOf("largeScreenTabletApi35"),
            ),
    )

private val recoveryAcceptanceMatrix =
    DeviceMatrix(
        devices =
            listOf(
                DeviceConfig("recoveryAcceptanceDeviceAApi35", "Pixel Tablet", 35, "google"),
                DeviceConfig("recoveryAcceptanceTabletApi35", "Pixel Tablet", 35, "google"),
                DeviceConfig("recoveryAcceptanceRestartApi35", "Pixel Tablet", 35, "google"),
            ),
        groups =
            mapOf(
                "recoveryAcceptanceDevices" to
                    listOf("recoveryAcceptanceDeviceAApi35", "recoveryAcceptanceTabletApi35", "recoveryAcceptanceRestartApi35"),
            ),
    )

private val nativeRestoreMatrix =
    DeviceMatrix(
        devices =
            listOf(
                DeviceConfig("restorePairApi34", "Pixel 9 Pro", 34, "google", pageAlignment = fourKbPages),
                DeviceConfig("d2dPairApi34", "Pixel 9 Pro", 34, "google_apis_playstore", pageAlignment = fourKbPages),
                DeviceConfig(
                    "restoreCallbackApi36_1",
                    "Pixel 9 Pro",
                    36,
                    "google_apis_playstore",
                    minorApiLevel = 1,
                    pageAlignment = fourKbPages,
                ),
            ),
        groups =
            mapOf(
                "nativeRestorePair" to listOf("restorePairApi34"),
                "nativeD2dPair" to listOf("d2dPairApi34"),
            ),
    )

private val wearMatrix =
    DeviceMatrix(
        devices = listOf(DeviceConfig("wearSmallRoundApi34", "Wear OS Small Round", 34, "android-wear", pageAlignment = fourKbPages)),
        groups = mapOf("wearDevices" to listOf("wearSmallRoundApi34")),
    )
