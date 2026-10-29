@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("app.logdate.atproto-published-module")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform AT Protocol OAuth client for confidential clients, with DPoP."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.oauth.client"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTestBuilder {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoSyntax)
            api(projects.shared.atprotoIdentity)
            api(projects.shared.atprotoXrpc)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(projects.shared.atprotoCrypto)
            implementation(libs.bouncycastle.bcprov)
            api(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        jvmTest.dependencies {
            implementation(projects.integration.atprotoOauthFixtures)
            implementation(libs.kotlin.test.junit)
            implementation(libs.nimbus.jose.jwt)
        }
    }
}
