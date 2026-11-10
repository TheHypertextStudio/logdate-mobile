plugins {
    id("app.logdate.compose-feature")
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.editor"
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
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
        val desktopMain by getting {
            dependencies {
                // VLCJ for in-app video playback on Desktop. Requires libVLC on
                // the host; if missing at runtime, the player falls back to
                // handing the URI off to the user's system player.
                implementation(libs.vlcj)
            }
        }

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.client.awareness)
            implementation(projects.client.ui)
            implementation(projects.client.domain)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.repository)
            implementation(projects.client.util)
            implementation(projects.client.media)
            implementation(projects.client.location)
            implementation(projects.client.permissions)
            implementation(projects.client.feature.postcards)
            implementation(projects.shared.model)
            // Compose plugin dependencies
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.nav3.runtime)
            implementation(libs.jetbrains.navigation3.ui)
            // Koin
            // Others
            implementation(libs.coil.compose)
            implementation(libs.filekit.compose)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // Location logging resolves its owner and device through these providers, so the
            // editor tests that exercise entry saving need to stand them in.
            implementation(projects.client.device)
        }

        androidMain.dependencies {
            implementation(projects.client.device)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            // CameraX
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.compose)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.video)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.extensions)
            // Media3 / ExoPlayer for video playback
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.common)
            // Coil video frame extraction
            implementation(libs.coil.video)
            // Permissions
            implementation(libs.accompanist.permissions)
        }
        findByName("androidDeviceTest")?.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.espresso.core)
            implementation(libs.androidx.ui.test.junit4)
        }
    }
}
// dependencies {
//    implementation(project(":core:data"))
//    implementation(project(":core:ui"))
//    implementation(project(":core:model"))
//    implementation(project(":core:util"))
//    implementation(project(":core:world"))
//    implementation(project(":core:media"))
//
//    // Compose in logdate.compose build logic
//    implementation(libs.androidx.hilt.navigation.compose)
// //    implementation(libs.androidx.compose.material3.carousel)
// //    implementation(libs.androidx.compose.material3)
//    // TODO: Figure out what dependency is causing this hell
//    implementation("com.google.j2objc:j2objc-annotations:3.0.0")
// //    api(libs.guava)
// }

// TODO: Revisit Android tooling/test runtime dependencies once Compose dependency accessors are migrated.
