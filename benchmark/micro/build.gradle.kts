import com.android.build.api.dsl.ManagedVirtualDevice
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "app.logdate.benchmark.micro"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    compileSdkMinor =
        libs.versions.android.compileSdkMinor
            .get()
            .toInt()

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.test.runner.monitoringInstrumentation.activityLaunchTimeoutMillis"] = "120000"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
                create("microBenchmarkDevices") {
                    targetDevices.add(flagshipPhoneApi36)
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testBuildType = "release"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.client.media)

    androidTestImplementation(projects.client.domain)
    androidTestImplementation(projects.client.healthConnect)
    androidTestImplementation(projects.client.repository)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.androidx.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
}
