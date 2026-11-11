plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.onboarding"
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
            implementation(projects.client.device)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.billing)
            implementation(projects.client.media)
            implementation(projects.client.intelligence)
            implementation(projects.client.permissions)
            implementation(projects.client.location)
            implementation(projects.client.feature.core)
            // Compose plugin dependencies
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.nav3.runtime)
            // External dependencies
            implementation(libs.coil.compose)
        }

        commonTest.dependencies {
            implementation(projects.client.healthConnect)
            implementation(projects.client.logdateDatastore)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            // Renders onboarding screens headlessly on the JVM.
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.video)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.extensions)
            implementation(libs.androidx.biometric)
            implementation(libs.accompanist.permissions)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
