plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.logdate.client.domain"
        withHostTestBuilder {}
        androidResources {
            enable = true
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("proguard-rules.pro")
            }
        }
    }

    jvm()

    sourceSets {
        val commonTest by getting
        androidMain.dependencies {
            // Android Health Connect dependencies
            implementation(libs.androidx.health.connect)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
        }
        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.client.util)
            implementation(projects.client.permissions)
            implementation(projects.client.repository)
            implementation(projects.client.sync)
            implementation(projects.client.media)
            implementation(projects.client.location)
            implementation(projects.client.intelligence)
            implementation(projects.client.networking)
            implementation(projects.client.device)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.healthConnect)
            implementation(projects.client.calendarSync)
            implementation(projects.client.text)
            // External dependencies
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json.okio)
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.napier)
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.okio.fakefilesystem)
        }
        jvmTest.dependencies {
            implementation(libs.mockk)
            implementation(libs.json.schema.validator)
        }
    }
}
