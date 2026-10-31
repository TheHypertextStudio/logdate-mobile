plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    android {
        namespace = "app.logdate.client.data"
        withHostTestBuilder {}
    }

    jvm("desktop")

    sourceSets {
        val desktopTest by getting { dependencies { implementation(libs.sqlite.bundled) } }
        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.shared.atprotoPlc)
            implementation(projects.client.domain)
            implementation(projects.client.repository)
            implementation(projects.client.database)
            implementation(projects.client.datastore)
            implementation(projects.client.device)
            implementation(projects.client.networking)
            implementation(projects.client.permissions)
            implementation(projects.client.sync)
            implementation(projects.client.util)
            implementation(projects.client.media)
            // External dependencies
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.datastore.preferences)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.napier)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.androidx.appsearch)
            implementation(libs.androidx.appsearch.platform.storage)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
        }
        desktopMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.androidx.test.core)
            implementation(libs.robolectric)
        }
    }
}
