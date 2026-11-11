plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform runtime implementations for AT Protocol PDS contracts."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.pds.runtime"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoPds)
            api(projects.shared.atprotoRepo)
            api(projects.shared.atprotoIdentity)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
    }
}
