plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "app.logdate.client.screenshot.scenes"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(projects.client.billing)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.domain)
            implementation(projects.client.feature.core)
            implementation(projects.client.feature.timeline)
            implementation(projects.client.feature.events)
            implementation(projects.client.feature.onboarding)
            implementation(projects.client.feature.rewind)
            implementation(projects.client.feature.search)
            implementation(projects.client.location)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.media)
            implementation(projects.client.repository)
            implementation(projects.client.sync)
            implementation(projects.client.ui)
            implementation(projects.client.device)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.datetime)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
