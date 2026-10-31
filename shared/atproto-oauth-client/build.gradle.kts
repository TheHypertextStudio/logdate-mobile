plugins {
    id("app.logdate.atproto-published-module")
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform AT Protocol OAuth client for confidential clients, with DPoP."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.oauth.client"
        withHostTestBuilder {}
    }

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
            implementation(libs.kotlin.test.junit)
            implementation(libs.nimbus.jose.jwt)
        }
    }
}
