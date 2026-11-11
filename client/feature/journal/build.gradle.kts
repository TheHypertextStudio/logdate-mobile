plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.journal"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.client.ui)
            implementation(projects.client.repository)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.domain)
            implementation(projects.client.sharing)
            implementation(projects.client.awareness)
            implementation(projects.client.util)
            implementation(projects.client.media)
            implementation(projects.client.feature.editor)
            // Compose plugin dependencies
            implementation(libs.compose.material.icons.extended)
            implementation(libs.material3.adaptive)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.nav3.runtime)
            // External dependencies
            implementation(libs.coil.compose)
            // Logging
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(projects.client.sync)
            implementation(libs.androidx.datastore.preferences)
        }

        desktopTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
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
