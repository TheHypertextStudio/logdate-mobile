plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(kotlin("compiler-embeddable"))
    testImplementation(kotlin("compiler-embeddable"))
    testImplementation(kotlin("test"))
    implementation(libs.commons.compress)
    implementation(libs.dokka.gradlePlugin)
    implementation(libs.binaryCompatibilityValidator.gradlePlugin)
    // The root build puts these on every module's classpath, at the versions the catalog names.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

// TestKit builds that apply build-logic's convention plugins need the plugins those conventions
// configure, which the real build puts on the classpath from its root project.
val testPluginClasspath by configurations.creating
dependencies {
    testPluginClasspath(libs.android.gradlePlugin)
    testPluginClasspath(libs.kotlin.gradlePlugin)
    testPluginClasspath(libs.kotlin.serialization.gradlePlugin)
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPluginClasspath)
}

tasks.test {
    useJUnitPlatform()
    // Keeps TestKit's Gradle home, with its instrumented plugin jars, in this build's output.
    systemProperty(
        "logdate.testKitDir",
        layout.buildDirectory
            .dir("test-kit")
            .get()
            .asFile.absolutePath,
    )
}

gradlePlugin {
    plugins {
        register("settingsUi") {
            id = "app.logdate.settings-ui"
            implementationClass = "app.logdate.SettingsUiPlugin"
        }
        register("homeWorkspace") {
            id = "app.logdate.home-workspace"
            implementationClass = "app.logdate.HomeWorkspacePlugin"
        }
        register("screenshotCoverage") {
            id = "app.logdate.screenshot-coverage"
            implementationClass = "app.logdate.ScreenshotCoveragePlugin"
        }
        register("atprotoPublishedModule") {
            id = "app.logdate.atproto-published-module"
            implementationClass = "app.logdate.AtprotoPublishedModulePlugin"
        }
        register("speechModel") {
            id = "app.logdate.speech-model"
            implementationClass = "app.logdate.SpeechModelPlugin"
        }
        register("dynamicFeature") {
            id = "app.logdate.dynamic-feature"
            implementationClass = "app.logdate.DynamicFeaturePlugin"
        }
        register("testDatabaseEnvironment") {
            id = "app.logdate.test-database-environment"
            implementationClass = "app.logdate.TestDatabaseEnvironmentPlugin"
        }
        register("kmpBase") {
            id = "app.logdate.kmp-base"
            implementationClass = "app.logdate.KmpBasePlugin"
        }
        register("kmpLibrary") {
            id = "app.logdate.kmp-library"
            implementationClass = "app.logdate.KmpLibraryPlugin"
        }
        register("composeFeature") {
            id = "app.logdate.compose-feature"
            implementationClass = "app.logdate.ComposeFeaturePlugin"
        }
        register("androidRelease") {
            id = "app.logdate.android-release"
            implementationClass = "app.logdate.LogDateReleasePlugin"
        }
    }
}
