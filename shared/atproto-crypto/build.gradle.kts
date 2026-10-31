plugins {
    id("app.logdate.atproto-published-module")
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform AT Protocol crypto helpers."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.crypto"
        withHostTestBuilder {}
    }

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}
