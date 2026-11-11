plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol PDS contracts and wire models."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.pds"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoIdentity)
            api(projects.shared.atprotoRepo)
            api(projects.shared.atprotoSyntax)
            api(projects.shared.atprotoXrpc)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
    }
}
