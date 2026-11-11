plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.logdate.client.permissions"
        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(projects.shared.model)
            implementation(projects.client.networking)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.napier)
        }
        androidMain.dependencies {
            implementation(libs.accompanist.permissions)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services)
            // Google Identity Services GetGoogleIdOption for "Sign in with Google" via Credential
            // Manager. Declared inline rather than in the version catalog, which is mid dependency
            // upgrade in a separate change; move to libs.versions.toml once that lands.
            implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.health.connect)
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.permissions.generated.resources"
}
