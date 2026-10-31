import java.io.File

plugins {
    alias(libs.plugins.android.application)
    id("app.logdate.managed-devices")
}

managedDeviceMatrix { nativeRestore() }

// A separate test package keeps GMD's instrumentation alive when BackupManager
// kills and restarts the application being restored. This module is never shipped.
android {
    namespace = "app.logdate.tools.restoredriver"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    defaultConfig {
        applicationId = "app.logdate.tools.restoredriver"
        minSdk = 30
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlin.test.junit)
    val pinnedApp = providers.gradleProperty("logdate.nativeAcceptanceAppApk")
    val pinnedProbe = providers.gradleProperty("logdate.nativeAcceptanceProbeApk")
    val supportApks =
        if (pinnedApp.isPresent || pinnedProbe.isPresent) {
            require(pinnedApp.isPresent && pinnedProbe.isPresent) { "Supply both pinned native acceptance APKs" }
            val selected = listOf(pinnedApp.get(), pinnedProbe.get()).map(::File)
            require(selected.all { it.isAbsolute && it.isFile }) { "Pinned acceptance APKs must exist at absolute paths" }
            files(selected)
        } else {
            files(
                rootProject.file("app/android-main/build/outputs/apk/debug/android-main-debug.apk"),
                rootProject.file("app/android-main/build/outputs/apk/androidTest/debug/android-main-debug-androidTest.apk"),
            ).builtBy(":app:android-main:assembleDebug", ":app:android-main:assembleDebugAndroidTest")
        }
    androidTestUtil(supportApks)
}
