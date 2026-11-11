import app.logdate.GenerateConformanceFixtures

plugins {
    alias(libs.plugins.app.logdate.kmp.library)
}

description = "LogDate's private and shared spaces: sealed records, and the data homes that store them."

// The sealed-record tests read the shared client conformance fixtures, so this module, the Mac app
// and the Linux app are held to the same known answers. They're embedded at build time because
// common tests run on iOS too, where there's no repository to read files from.
val conformanceDirectory = rootProject.layout.projectDirectory.dir("conformance")
val generateSealedConformanceFixtures =
    tasks.register<GenerateConformanceFixtures>("generateSealedConformanceFixtures") {
        description = "Embed the conformance manifest and sealed fixtures in the test sources."
        root.set(conformanceDirectory)
        fixtures.from(rootProject.fileTree(conformanceDirectory) { include("manifest.json", "sealed/*.json") })
        packageName.set("app.logdate.space")
        objectName.set("SealedConformanceFixtures")
        outputDirectory.set(layout.buildDirectory.dir("generated/conformance/kotlin"))
    }

kotlin {
    explicitApi()

    android {
        namespace = "app.logdate.space"
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.lexicon)
            api(libs.kotlinx.io.core)
            implementation(projects.shared.atprotoRepo)
            implementation(projects.shared.atprotoXrpc)
            implementation(libs.cryptography.provider.optimal)
            implementation(libs.ktor.client.core)
        }

        commonTest {
            kotlin.srcDir(generateSealedConformanceFixtures.flatMap { it.outputDirectory })
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
