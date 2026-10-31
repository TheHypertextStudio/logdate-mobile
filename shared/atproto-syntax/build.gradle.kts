plugins {
    id("app.logdate.atproto-published-module")
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform AT Protocol syntax types."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.syntax"
        withHostTestBuilder {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
