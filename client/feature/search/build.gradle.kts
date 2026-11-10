plugins {
    id("app.logdate.compose-feature")
}

kotlin {
    android {
        namespace = "app.logdate.feature.search"
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.model)
            implementation(projects.client.ui)
            implementation(projects.client.repository)
            implementation(projects.client.domain)
            implementation(projects.client.util)

            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
        }

        sourceSets.getByName("desktopMain").dependencies {
            implementation(libs.compose.desktop)
        }
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.feature.search.generated.resources"
}
