plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.dokka)
}

kotlin {
    android {
        namespace = "app.logdate.client.billing"
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.napier)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.android.billingKtx)
            implementation(libs.koin.android)
        }
    }
}
