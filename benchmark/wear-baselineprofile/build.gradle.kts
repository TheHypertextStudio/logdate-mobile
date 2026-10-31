import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("app.logdate.benchmark")
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.logdate.benchmark.wear.baselineprofile"
    targetProjectPath = ":app:wear"

    defaultConfig {
        minSdk = 34
    }

    testOptions {
        managedDevices {
            lateinit var wearApi35: ManagedVirtualDevice
            localDevices {
                wearApi35 =
                    create("wearApi35") {
                        device = "Wear OS Small Round"
                        apiLevel = 35
                        pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
                        systemImageSource = "google"
                    }
            }
        }
    }
}

baselineProfile {
    managedDevices += "wearApi35"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}
