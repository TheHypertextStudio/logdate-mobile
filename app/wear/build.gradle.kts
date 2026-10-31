import app.logdate.LogDateReleaseExtension
import com.android.build.api.dsl.ApplicationExtension
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.gradlePlayPublisher)
    id("app.logdate.android-release")
}

/**
 * The watch ships on the phone's Play listing, as a Wear OS form factor, under the phone's package
 * name and signing key. The Wear Data Layer only carries data between apps that match on both, so
 * identity, versioning and signing come from the same plugin the phone's values are defined by.
 */
val release = the<LogDateReleaseExtension>()
release.requireSigningForReleaseTasks()

val baselineProfileRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("BaselineProfile", ignoreCase = true)
    }

extensions.configure<ApplicationExtension> {
    namespace = "app.logdate.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = release.applicationId
        manifestPlaceholders["watchExperienceDefault"] =
            providers
                .gradleProperty("logdate.watchExperienceDefault")
                .map(String::toBooleanStrict)
                .getOrElse(false)
                .toString()
        minSdk = 34
        targetSdk = 37
        versionCode = release.versionCode
        versionName = release.versionName
        testInstrumentationRunner = "app.logdate.wear.e2e.WearEmulatorTestRunner"
    }

    release.signing?.let { key ->
        signingConfigs {
            create("release") {
                storeFile = file(key.storeFile)
                storePassword = key.storePassword
                keyAlias = key.keyAlias
                keyPassword = key.keyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Installs beside a release build, and matches the phone's debug package so a debug phone
            // and a debug watch built on one machine still share Data Layer identity and signing key.
            applicationIdSuffix = release.debugApplicationIdSuffix
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig =
                if (release.signsWithDebugKey) {
                    signingConfigs.getByName("debug")
                } else {
                    signingConfigs.getByName("release")
                }
            if (baselineProfileRequested) {
                isProfileable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Enable core library desugaring for health-connect
        isCoreLibraryDesugaringEnabled = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

play {
    // Wear OS releases go to the form factor's own track. LogDate internal testing is wear:internal.
    track.set(release.wearPlayTrack)
    defaultToAppBundles.set(true)
    // Keyless: CI signs in with Workload Identity Federation as the Play service account.
    useApplicationDefaultCredentials.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS").isNullOrBlank())
    // Play assigns the phone and the watch codes from one sequence for the listing. AUTO asks Play during
    // a publish; plain builds keep their local versionCode and need no Play credentials.
    resolutionStrategy.set(if (release.playPublishRequested) ResolutionStrategy.AUTO else ResolutionStrategy.FAIL)
}

dependencies {
    // Core library desugaring for health-connect
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark:wear-baselineprofile"))

    // Koin dependency injection
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    // Napier logging
    implementation(libs.napier)

    // Kotlinx coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Client dependencies for Wear - shared data infrastructure
    implementation(projects.client.repository)
    implementation(projects.client.domain)
    implementation(projects.client.healthConnect)
    implementation(projects.client.data)
    implementation(projects.client.database)
    implementation(projects.client.device)
    implementation(projects.client.location)
    implementation(projects.client.logdateDatastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(projects.client.networking)
    implementation(projects.client.sensor)
    implementation(projects.client.notifications)
    implementation(projects.client.permissions)
    implementation(projects.client.sync)
    implementation(projects.client.ui)
    implementation(projects.shared.config)
    implementation(projects.shared.model)
    implementation(projects.client.media)

    // Serialization (required by LocalFirstDraftRepository)
    implementation(libs.kotlinx.serialization.json)

    // Navigation 3
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.material.iconsExtended)

    // Additional Compose support
    implementation(libs.kotlinx.datetime)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.play.services.wearable)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.graphics.shapes)

    // Wear Compose
    implementation(libs.androidx.wear.material3)
    implementation(libs.androidx.wear.foundation)
    implementation(libs.androidx.wear.compose.navigation3)
    implementation(libs.androidx.wear.compose.ui.tooling)

    // Wear OS platform libraries
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.phone.interactions)
    implementation(libs.wear.remote.interactions)

    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // Material 3 tiles follow the watch theme.
    implementation(libs.androidx.tiles)
    implementation(libs.androidx.tiles.tooling.preview)
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.expression)
    implementation(libs.androidx.protolayout.material3)
    implementation(libs.androidx.watchface.complications.data.source.ktx)
    implementation(libs.androidx.health.services.client)

    // Media3 ExoPlayer for voice note playback (reuses AudioPlaybackService from client:media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.session)

    // WorkManager wakes the watch when a sync retry is due
    implementation(libs.androidx.work.runtime)

    // Unit test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)

    // Screenshot test dependencies
    screenshotTestImplementation(libs.screenshot.validation.api)

    // Instrumented test dependencies
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.protolayout.testing)
    androidTestImplementation(libs.androidx.tiles.renderer)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.tiles.tooling)
}

afterEvaluate {
    tasks.matching { it.name.startsWith("uninstall") }.configureEach {
        enabled = false
        doFirst {
            throw GradleException(
                "Uninstall tasks are disabled to protect app data on connected devices. " +
                    "Use 'installDebug' to upgrade in place.",
            )
        }
    }
}
