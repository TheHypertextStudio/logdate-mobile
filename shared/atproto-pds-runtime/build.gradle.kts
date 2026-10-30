plugins {
    id("app.logdate.atproto-published-module")
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform runtime implementations for AT Protocol PDS contracts."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.pds.runtime"
        withHostTestBuilder {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoPds)
            api(projects.shared.atprotoRepo)
            api(projects.shared.atprotoIdentity)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
