import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("app.logdate.benchmark")
}

android {
    namespace = "app.logdate.benchmark.phone"
    targetProjectPath = ":app:android-main"

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libbenchmarkNative.so"
            keepDebugSymbols += "**/libtracing_perfetto.so"
        }
    }

    testOptions {
        managedDevices {
            lateinit var flagshipPhoneApi36: ManagedVirtualDevice
            localDevices {
                flagshipPhoneApi36 =
                    create("flagshipPhoneApi36") {
                        device = "Pixel 9 Pro"
                        apiLevel = 36
                        pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
                        systemImageSource = "google"
                    }
            }
            groups {
                create("phoneBenchmarkDevices") {
                    targetDevices.add(flagshipPhoneApi36)
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
