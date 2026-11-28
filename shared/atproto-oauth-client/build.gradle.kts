plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol OAuth client for confidential clients, with DPoP."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.oauth.client"
    }

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
            implementation(libs.ktor.client.mock)
        }

        jvmTest.dependencies {
            // The server container build (gradle/server-docker.settings.gradle.kts) leaves out
            // test-only projects, so the fixtures are optional.
            project.findProject(":integration:atproto-oauth-fixtures")?.let { implementation(it) }
            implementation(libs.kotlin.test.junit)
            implementation(libs.nimbus.jose.jwt)
        }
    }
}
