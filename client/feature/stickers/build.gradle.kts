plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.stickers"
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
            // Compose
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.tooling.preview)
            // Image loading
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(libs.mlkit.subject.segmentation)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
