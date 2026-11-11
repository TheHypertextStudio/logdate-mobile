plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.dokka)
}

kotlin {
    android {
        namespace = "app.logdate.client.calendar"
    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
        }
        commonMain.dependencies {
            implementation(projects.shared.model)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.napier)

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.koin.test)
        }
    }
}
