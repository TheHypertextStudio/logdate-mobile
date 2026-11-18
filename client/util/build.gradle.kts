@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.logdate.client.util"
    }

    jvm()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.datetime)
        }

        findByName("androidHostTest")?.dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}
