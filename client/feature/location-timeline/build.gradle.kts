plugins {
    alias(libs.plugins.app.logdate.compose.feature)
}

kotlin {
    android {
        namespace = "app.logdate.feature.location.timeline"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir("src/jvmMain/kotlin")
        }
        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.client.device)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.domain)
            implementation(projects.client.ui)
            implementation(projects.client.awareness)
            implementation(projects.client.repository)
            implementation(projects.client.location)
            implementation(projects.client.permissions)
            implementation(projects.client.util)

            // Compose
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)

            // External dependencies
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.serialization.core)

            // Navigation

            // Architecture
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.google.maps.compose)
            implementation(libs.androidx.activity.compose)
        }
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.feature.location.timeline.generated.resources"
}
