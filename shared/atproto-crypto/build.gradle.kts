plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol crypto helpers."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.crypto"
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}
