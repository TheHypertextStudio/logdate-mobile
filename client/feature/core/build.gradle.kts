@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("app.logdate.settings-ui")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.dokka)
}

kotlin {
    android {
        namespace = "app.logdate.client.feature.core"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTestBuilder {}
        androidResources {
            enable = true
            noCompress += listOf("cvr")
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("proguard-rules.pro")
            }
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )

    jvm("desktop")

    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            compilerOptions.freeCompilerArgs.set(listOf("-Xexpect-actual-classes"))
        }

        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.client.ui)
            implementation(projects.client.awareness)
            implementation(projects.client.util)
            implementation(projects.client.repository)
            implementation(projects.client.domain)
            implementation(projects.client.data)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.database)
            implementation(projects.client.networking)
            implementation(projects.client.permissions)
            implementation(projects.client.device)
            implementation(projects.client.sync)
            implementation(projects.client.sharing)
            implementation(projects.client.media)
            implementation(projects.client.healthConnect)
            implementation(projects.client.location)
            implementation(projects.client.feature.timeline)
            implementation(projects.client.feature.rewind)
            implementation(projects.client.feature.journal)
            implementation(projects.client.feature.events)
            // Compose plugin dependencies
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            // Core dependencies
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.nav3.runtime)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.material3.adaptive.navigation)
            // External dependencies
            implementation(libs.okio)
            implementation(libs.coil.compose)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            // Logging
            implementation(libs.napier)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.datastore.preferences)
            implementation(projects.client.healthConnect)
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            implementation(libs.google.code.scanner)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.koin.android)
            implementation(projects.client.notifications)
            implementation(projects.client.media)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.video)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.extensions)
            implementation(libs.androidx.biometric)
            implementation(libs.accompanist.permissions)
            // WorkManager for background export tasks
            implementation(libs.androidx.work.runtime)
            implementation(libs.koin.androidx.workmanager)
        }

        desktopMain.dependencies {
            implementation(projects.client.media)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.client.feature.core.generated.resources"
}
