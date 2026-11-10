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
    implementation("de.undercouch:gradle-download-task:5.7.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    compileOnly("com.android.tools.build:gradle:9.2.1")
    // The Kotlin Gradle plugin is on the classpath of every module that applies lexicon-models.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
        register("lexiconModels") {
            id = "app.logdate.lexicon-models"
            implementationClass = "app.logdate.LexiconModelsPlugin"
        }
        register("testDatabaseEnvironment") {
            id = "app.logdate.test-database-environment"
            implementationClass = "app.logdate.TestDatabaseEnvironmentPlugin"
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
