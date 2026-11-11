plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.postcards"
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
            implementation(projects.client.repository)
            implementation(projects.client.database)
            implementation(projects.client.media)
            implementation(projects.client.util)
            implementation(projects.client.feature.stickers)
            // Compose
            implementation(libs.nav3.runtime)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.material3.adaptive)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            // External
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.androidx.exifinterface)
            implementation("androidx.print:print:1.0.0")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
