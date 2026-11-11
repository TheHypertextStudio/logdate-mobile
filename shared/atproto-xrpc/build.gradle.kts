plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol XRPC runtime."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.xrpc"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoSyntax)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
