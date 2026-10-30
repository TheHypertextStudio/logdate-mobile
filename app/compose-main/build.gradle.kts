import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        // TODO: Migrate to app.logdate.mobile once we have ability to migrate
        namespace = "studio.hypertext.logdate"
        withHostTestBuilder {}
        androidResources {
            enable = true
            noCompress += listOf("cvr")
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            binaryOption("bundleId", "studio.hypertext.apps.logdate.compose")
            isStatic = true
        }
    }

    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            // Project dependencies
            implementation(projects.client.feature.core)
            implementation(projects.client.feature.onboarding)
            implementation(projects.client.feature.editor)
            implementation(projects.client.feature.timeline)
            implementation(projects.client.feature.journal)
            implementation(projects.client.feature.library)
            implementation(projects.client.feature.rewind)
            implementation(projects.client.feature.locationTimeline)
            implementation(projects.client.feature.search)
            implementation(projects.client.feature.postcards)
            implementation(projects.client.feature.stickers)
            implementation(projects.client.feature.events)
            implementation(projects.client.data)
            implementation(projects.client.database)
            implementation(projects.client.ui)
            implementation(projects.client.theme)
            implementation(projects.client.networking) // TODO: See if this can be hoisted down
            implementation(projects.client.device)
            implementation(projects.client.intelligence)
            implementation(projects.client.domain)
            implementation(projects.client.repository)
            implementation(projects.client.logdateDatastore)
            implementation(projects.client.location)
            implementation(projects.client.sensor)
            implementation(projects.client.media)
            implementation(projects.client.permissions)
            implementation(projects.client.sharing)
            implementation(projects.client.sync)
            implementation(projects.client.healthConnect)
            implementation(projects.client.calendarSync)
            implementation(projects.client.notifications)
            implementation(projects.client.util)
            implementation(projects.shared.model)
            implementation(projects.shared.config)
            // External dependencies
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive.navigation.suite)
            implementation(libs.compose.ui)
            implementation(libs.compose.animation)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
            implementation(libs.jetbrains.material3.adaptive.navigation3.mp)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.datetime)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.napier)
            implementation(libs.filekit.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.client.screenshotScenes)
        }
        androidMain.dependencies {
            // Assistant actions (Android 16+). The compiler generates the service and its schema.
            implementation(libs.androidx.appfunctions)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.health.connect)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.androidx.material3.adaptive.navigation3)
            implementation(libs.nav3.ui)
            implementation(libs.nav3.runtime)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.play.services.location)
            implementation(libs.koin.android)
            implementation(libs.koin.androidx.workmanager)
            implementation(projects.client.feature.androidWidgets)
            implementation(libs.material)
            implementation(libs.play.app.update.ktx)
            implementation(libs.play.feature.delivery.ktx)
            implementation(libs.coil.compose)
            implementation(libs.coil.video)
            implementation(libs.play.services.wearable)
            implementation(libs.wear.remote.interactions)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.crashlytics)
        }
        val androidHostTest by getting
        androidHostTest.dependencies {
            implementation(libs.robolectric)
            implementation(libs.androidx.work.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockk)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.coil.compose)
        }
        desktopTest.dependencies {
            implementation(projects.client.awareness)
            implementation(libs.compose.ui.test)
            implementation(kotlin("test"))
            implementation(libs.kotlin.test.junit)
            implementation(libs.junit)
            implementation(compose.desktop.currentOs)
            implementation(projects.client.screenshotScenes)
        }
    }
}

fun addToFirstExistingConfiguration(
    dependency: Any,
    vararg configurationNames: String,
) {
    val targetConfiguration =
        configurationNames.firstOrNull { name ->
            configurations.findByName(name) != null
        } ?: return
    dependencies.add(targetConfiguration, dependency)
}

addToFirstExistingConfiguration(
    libs.compose.ui.tooling,
    "debugImplementation",
    "androidDebugImplementation",
)

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "logdate.app.composemain.generated.resources"
}

compose.desktop {
    application {
        mainClass = "app.logdate.MainKt"

        buildTypes {
            release {
                proguard {
                    // Preserve bundled JNA and optional codec integrations during packaging.
                    isEnabled.set(false)
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LogDate"
            description = "The LogDate desktop application"
            copyright = "© 2024 Willie Chalmers III"
            packageVersion = "1.0.0"

            windows {
                console = true
                perUserInstall = true
                iconFile.set(project.file("src/commonMain/composeResources/drawable/ic_launcher_google_play.png"))
            }

            macOS {
                bundleID = "studio.hypertext.apps.logdate"
                dockName = "LogDate"
                appCategory = "public.app-category.lifestyle"
                iconFile.set(project.file("packaging/LogDate.icns"))
            }

            linux {
                debMaintainer = "contact@logdate.app"
                iconFile.set(project.file("src/commonMain/composeResources/drawable/ic_launcher_google_play.png"))

                modules("jdk.security.auth") // Needed for FileKit
            }
        }
    }
}

buildConfig {
    packageName("app.logdate.client")

    // Values sourced from gradle.properties; override in local.properties or CI for non-production builds.
    // TODO: add per-variant overrides once a staging/debug web environment exists.
    buildConfigField("LOGDATE_ORIGIN", providers.gradleProperty("logdate.origin").get())
    buildConfigField("LOGDATE_API_BASE_URL", providers.gradleProperty("logdate.apiBaseUrl").get())
}

val desktopScreenshotReferenceDir = layout.projectDirectory.dir("src/desktopTest/reference")
dependencies {
    add("kspAndroid", libs.androidx.appfunctions.compiler)
}

// The AppFunctions compiler writes the assistant actions' schema (logdate_app_functions.xml) as an
// asset. The multiplatform Android library does not package KSP assets by itself, so add them.
val appFunctionAssets = layout.buildDirectory.dir("generated/ksp/android/androidMain/resources/assets")
extensions.getByType(com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension::class.java).let { components ->
    components.onVariants(components.selector().all()) { variant ->
        variant.sources.assets?.addStaticSourceDirectory(appFunctionAssets.get().asFile.absolutePath)
    }
}
tasks
    .matching { it.name == "generateAndroidMainAssets" || it.name == "mergeAndroidMainAssets" }
    .configureEach { dependsOn("kspAndroidMain") }

val desktopScreenshotActualDir = layout.buildDirectory.dir("reports/desktopScreenshotTest/actual")
val desktopScreenshotDiffDir = layout.buildDirectory.dir("reports/desktopScreenshotTest/diff")
val desktopScreenshotSceneFilter = providers.systemProperty("logdate.desktopScreenshots.sceneFilter").orNull

tasks.named<Test>("desktopTest") {
    systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")
    systemProperty("apple.awt.UIElement", "true")
    systemProperty("logdate.desktopScreenshots.referenceDir", desktopScreenshotReferenceDir.asFile.absolutePath)
    systemProperty("logdate.desktopScreenshots.actualDir", desktopScreenshotActualDir.get().asFile.absolutePath)
    systemProperty("logdate.desktopScreenshots.diffDir", desktopScreenshotDiffDir.get().asFile.absolutePath)
    desktopScreenshotSceneFilter?.let {
        systemProperty("logdate.desktopScreenshots.sceneFilter", it)
    }
}

tasks.register("validateDesktopScreenshotTest") {
    group = "verification"
    description = "Validate committed Desktop screenshot baselines for app/compose-main."
    dependsOn("desktopTest")
}

tasks.register<Test>("updateDesktopScreenshotTest") {
    group = "verification"
    description = "Update Desktop screenshot baselines for app/compose-main. Without a scene filter, baselines that still pass are kept."

    val desktopTest = tasks.named<Test>("desktopTest").get()
    testClassesDirs = desktopTest.testClassesDirs
    classpath = desktopTest.classpath
    workingDir = desktopTest.workingDir
    systemProperty("skiko.renderApi", "SOFTWARE_COMPAT")
    systemProperty("apple.awt.UIElement", "true")
    systemProperty("logdate.desktopScreenshots.referenceDir", desktopScreenshotReferenceDir.asFile.absolutePath)
    systemProperty("logdate.desktopScreenshots.actualDir", desktopScreenshotActualDir.get().asFile.absolutePath)
    systemProperty("logdate.desktopScreenshots.diffDir", desktopScreenshotDiffDir.get().asFile.absolutePath)
    systemProperty("logdate.desktopScreenshots.update", "true")
    desktopScreenshotSceneFilter?.let {
        systemProperty("logdate.desktopScreenshots.sceneFilter", it)
    }
    outputs.upToDateWhen { false }
}
