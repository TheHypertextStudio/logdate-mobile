plugins {
    id("app.logdate.compose-feature")
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.library"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.model)
            implementation(projects.client.ui)
            implementation(projects.client.domain)
            implementation(projects.client.repository)
            implementation(projects.client.location)
            implementation(projects.client.media)
            implementation(projects.client.util)
            implementation(projects.client.feature.editor)
            // Compose
            implementation(libs.compose.material.icons.extended)
            implementation(libs.material3.adaptive)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.nav3.runtime)
            // External
            implementation(libs.coil.compose)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
