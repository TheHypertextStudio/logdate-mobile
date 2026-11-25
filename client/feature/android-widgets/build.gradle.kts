@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.widgets"
        compileSdk {
            version =
                release(
                    libs.versions.android.compileSdk
                        .get()
                        .toInt(),
                ) {
                    minorApiLevel =
                        libs.versions.android.compileSdkMinor
                            .get()
                            .toInt()
                }
        }
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTestBuilder {}
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
        commonMain.dependencies {
            implementation(projects.client.domain)
            implementation(projects.client.media)
            implementation(projects.client.repository)
            implementation(projects.shared.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.napier)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val androidHostTest by getting
        androidHostTest.dependencies {
            implementation(libs.robolectric)
        }
        jvmMain.dependencies {
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(projects.client.device)
            implementation(projects.client.feature.core)
            // Glance
            implementation(libs.androidx.glance.appwidget)
            implementation(libs.androidx.glance.material3)

            // Compose (for config Activity — regular Compose, not Glance)
            implementation(projects.client.theme)
            implementation(projects.client.ui)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.biometric)

            // WorkManager
            implementation(libs.androidx.work.runtime)

            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.android)
            implementation(libs.koin.androidx.workmanager)
        }
    }
}
