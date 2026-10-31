import app.logdate.GenerateConformanceFixtures
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.dokka)
}

// The conversion tests check the number rules against the same known answers the Mac and Linux apps
// use. They are embedded at build time because common tests run on iOS too, where there is no
// repository to read files from.
val lexiconCheckDirectory = rootProject.layout.projectDirectory.dir("tools/lexicon-check")
val generateConversionVectors =
    tasks.register<GenerateConformanceFixtures>("generateConversionVectors") {
        description = "Embed the number conversion known answers in the test sources."
        root.set(lexiconCheckDirectory)
        fixtures.from(rootProject.fileTree(lexiconCheckDirectory) { include("conversion-vectors.json") })
        packageName.set("app.logdate.client.sync.space")
        objectName.set("ConversionVectorFixtures")
        outputDirectory.set(layout.buildDirectory.dir("generated/conversion-vectors/kotlin"))
    }

kotlin {
    android {
        namespace = "app.logdate.client.sync"
        withHostTestBuilder {}
        // The backup worker's foreground notification needs strings, and the module has to own
        // them: it is the module Android holds responsible for the service.
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            implementation(projects.client.networking)
            implementation(projects.client.repository)
            implementation(projects.client.database)
            implementation(projects.client.datastore)
            implementation(projects.client.device)
            implementation(projects.client.media)
            implementation(projects.client.text)
            implementation(projects.client.util)
            implementation(projects.shared.space)
            implementation(projects.shared.atprotoRepo)
            implementation(projects.shared.atprotoXrpc)

            // External dependencies
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.provider.optimal)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.napier)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
        }

        commonTest {
            kotlin.srcDir(generateConversionVectors.flatMap { it.outputDirectory })
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.ktor.client.mock)
            implementation(projects.client.datastore)
            implementation(projects.client.util)
            implementation(libs.okio)
        }
        findByName("androidHostTest")?.dependencies {
            implementation(libs.mockk)
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
        }
        androidMain.dependencies {
            implementation(projects.client.database)
            implementation(projects.client.notifications)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            implementation(libs.koin.androidx.workmanager)
            implementation(libs.androidx.work.runtime)
            // Room for transaction support
            implementation(libs.room.runtime)
        }
        desktopMain.dependencies {
            implementation(projects.client.database)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

// The error-code contract reads server documentation outside this module.
tasks.named<Test>("desktopTest") {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("server/src/main/kotlin/app/logdate/server/routes/docs"))
        .withPropertyName("documentedServerErrors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
