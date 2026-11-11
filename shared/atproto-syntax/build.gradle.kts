plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol syntax types."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.syntax"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
