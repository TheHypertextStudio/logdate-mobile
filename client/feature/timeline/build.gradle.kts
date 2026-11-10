plugins {
    id("app.logdate.compose-feature")
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.timeline"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.client.ui)
            implementation(projects.client.domain)
            implementation(projects.client.permissions)
            implementation(projects.client.repository)
            implementation(projects.client.intelligence)
            implementation(projects.client.media)
            implementation(projects.client.networking)
            implementation(projects.client.awareness)
            implementation(projects.client.util)
            implementation(projects.client.feature.editor)
            // Compose plugin dependencies
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.androidx.paging.common)
//            implementation(libs.androidx.paging.compose)
            implementation(libs.material3.adaptive.navigation)
            // External dependencies
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.google.maps.compose)
            implementation(libs.accompanist.permissions)
//    implementation(libs.firebase.vertexai)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
