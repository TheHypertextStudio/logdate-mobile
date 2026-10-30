plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "app.logdate.client.media"
        withHostTestBuilder {}
        androidResources {
            enable = true
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("proguard-rules.pro")
            }
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            // MediaFileSource exposes kotlinx-io sources so uploads can stream from disk.
            api(libs.kotlinx.io.core)
            implementation(libs.okio)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            // Device labels and route messages travel to the screen as UiText.
            api(projects.client.text)
            // Logging
            implementation(libs.napier)
            // Repository
            implementation(projects.client.repository)
            implementation(projects.client.util)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(projects.client.device)
            implementation(projects.client.logdateDatastore)
            implementation(projects.shared.model)
            implementation(libs.koin.android)
            // Live Update builder methods (setRequestPromotedOngoing) need core 1.17 or later.
            implementation(libs.androidx.core.ktx)
            implementation(projects.client.networking)
            implementation(projects.client.notifications)
            implementation(libs.androidx.work.runtime)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.common)
            implementation(libs.media3.session)
            implementation(libs.play.feature.delivery.ktx)
            implementation(libs.kotlinx.coroutines.play.services)
        }
        desktopMain.dependencies {
            // Sherpa-ONNX JVM bindings (Java classes wrapping the JNI surface).
            // The native libs needed for runtime live in the per-platform
            // sherpa-onnx-native-lib-* jars below — Sherpa's LibraryUtils picks
            // up whichever one matches the host OS at first use.
            implementation(files("${rootProject.projectDir}/libs/sherpa-onnx-v1.12.35.jar"))
            implementation(files("${rootProject.projectDir}/libs/sherpa-onnx-native-lib-osx-aarch64-v1.12.35.jar"))
            // Runtime tar.bz2 unpacking for the on-demand model downloads,
            // mirroring the dependency the speechrecognition dynamic feature
            // module already pulls in on Android.
            implementation(libs.commons.compress)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.mockk)
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
