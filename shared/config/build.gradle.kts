@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.buildConfig)
}

// Single source of truth for the default cloud API host the client talks to. Sourced from the
// `logdate.backendUrl` Gradle property so a staging/debug build can repoint the client with
// `-Plogdate.backendUrl=https://cloud-staging.logdate.app`; falls back to production otherwise.
buildConfig {
    packageName("app.logdate.shared.config")
    buildConfigField(
        "DEFAULT_BACKEND_URL",
        providers.gradleProperty("logdate.backendUrl").orElse("https://cloud.logdate.app").get(),
    )
    // Web OAuth client ID that Google ID tokens are issued for ("Sign in with Google"). Empty by
    // default; set with -Plogdate.googleServerClientId=<id> (or in gradle.properties) once a Google
    // OAuth client exists. Blank disables the Google sign-in option in the client.
    buildConfigField(
        "GOOGLE_SERVER_CLIENT_ID",
        providers
            .gradleProperty("logdate.googleServerClientId")
            .orElse(providers.environmentVariable("LOGDATE_GOOGLE_SERVER_CLIENT_ID"))
            .orElse("")
            .get(),
    )
}

kotlin {
    jvm()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(projects.shared.model)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
    }
}
