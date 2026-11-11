plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform PLC directory primitives and client helpers for AT Protocol."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.plc"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoIdentity)
            api(projects.shared.atprotoSyntax)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}
