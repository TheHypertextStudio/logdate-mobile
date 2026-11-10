plugins {
    id("app.logdate.compose-feature")
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.rewind"
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
            implementation(projects.client.repository)
            implementation(projects.client.domain)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.intelligence)
            implementation(projects.client.sharing)
            implementation(projects.client.util)
            implementation(projects.client.media)
            // Compose plugin dependencies
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.nav3.runtime)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.material3.adaptive.navigation)
            // External dependencies
            implementation(libs.coil.compose)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.accompanist.permissions)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.feature.rewind.generated.resources"
}
