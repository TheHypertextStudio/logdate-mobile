plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.events"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.client.ui)
            implementation(projects.client.domain)
            implementation(projects.client.repository)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.calendarSync)
            implementation(projects.client.permissions)
            implementation(projects.client.util)
            // Compose
            implementation(libs.compose.material.icons.extended)
            implementation(libs.material3.adaptive)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Navigation
            implementation(libs.nav3.runtime)
            // Core
            implementation(libs.kotlinx.serialization.core)
            // External
            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(libs.androidx.datastore.preferences)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.androidx.activity.compose)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
