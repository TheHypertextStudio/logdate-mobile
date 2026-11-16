import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
}

description = "Kotlin Multiplatform AT Protocol crypto helpers."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.crypto"
    }

    // The JVM and Android share one BouncyCastle implementation of the curve operations.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                // withAndroidTarget() matches only the older Android Gradle plugin's target type.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        matching { it.name == "jvmAndAndroidMain" }.configureEach {
            dependencies {
                implementation(libs.bouncycastle.bcprov)
            }
        }

        iosMain.dependencies {
            // OpenSSL, because Apple's own cryptography has no secp256k1.
            implementation(libs.cryptography.provider.openssl3.prebuilt)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.bouncycastle.bcprov)
        }
    }
}
