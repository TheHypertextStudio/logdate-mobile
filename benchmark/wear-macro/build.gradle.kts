import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("app.logdate.benchmark")
}

android {
    namespace = "app.logdate.benchmark.wear"
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
            groups {
                create("wearBenchmarkDevices") {
                    targetDevices.add(wearApi35)
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}
