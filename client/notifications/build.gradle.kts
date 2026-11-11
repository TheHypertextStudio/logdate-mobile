plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.dokka)
}

kotlin {
    android {
        namespace = "app.logdate.client.notifications"
        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.napier)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }

        androidMain.dependencies {
            // Live Update builder methods and ProgressStyle need core 1.17 or later.
            implementation(libs.androidx.core.ktx)
        }
    }
}
