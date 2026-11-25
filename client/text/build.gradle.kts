@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.logdate.client.text"
    }

    jvm()

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.components.resources)
        }
    }
}
