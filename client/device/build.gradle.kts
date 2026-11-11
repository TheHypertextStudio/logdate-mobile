
plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.dokka)
    alias(libs.plugins.buildConfig)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.logdate.client.device"
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.shared.atprotoCrypto)
            implementation(projects.client.logdateDatastore)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            implementation(libs.cryptography.provider.optimal)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.napier)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.security.crypto)
            implementation(libs.bouncycastle.bcprov)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.installations)
            implementation(libs.koin.android)
        }
        desktopMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.napier)
        }
        desktopTest.dependencies {
            implementation(libs.mockk)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockk)
        }
        findByName("androidDeviceTest")?.dependencies {
            implementation(libs.kotlin.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

buildConfig {
    // Package name for the generated BuildConfig class
    packageName("app.logdate")

    // Common BuildConfig fields
    buildConfigField("APP_NAME", rootProject.name)
    buildConfigField("APP_VERSION", rootProject.version.toString())
    buildConfigField("APP_PACKAGE_NAME", "app.logdate")
}
