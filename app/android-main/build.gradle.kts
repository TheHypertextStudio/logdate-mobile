@file:Suppress("UnstableApiUsage")

import app.logdate.LogDateReleaseExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ResValue
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.gradlePlayPublisher)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.screenshot)
    id("app.logdate.android-release")
    id("app.logdate.managed-devices")
}

managedDeviceMatrix { phone() }

val baselineProfileRequested =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("BaselineProfile", ignoreCase = true)
    }
val androidTestClassOverride = providers.gradleProperty("logdate.androidTestClass").orNull
val androidTestPackageOverride = providers.gradleProperty("logdate.androidTestPackage").orNull
// Runtime-only credentials for the staging sync probe. Never put tokens in source or Gradle files.
val syncProbeArguments = providers.gradleProperty("logdate.syncProbeArguments").orNull
val recoveryAcceptanceMode = providers.gradleProperty("logdate.recoveryAcceptanceMode").orNull
val recoveryAcceptanceAssetsDir = providers.gradleProperty("logdate.recoveryAcceptanceAssetsDir").orNull
val androidTestCoverageEnabled =
    providers
        .gradleProperty("logdate.androidTestCoverage")
        .map(String::toBoolean)
        .getOrElse(true)
val androidTestOrchestratorEnabled =
    providers
        .gradleProperty("logdate.androidTestOrchestrator")
        .map(String::toBoolean)
        .getOrElse(true)
val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.isFile }
            ?.inputStream()
            ?.use(::load)
    }
val resolvedGoogleMapsApiKey =
    System.getenv("LOGDATE_GOOGLE_MAPS_API_KEY")
        ?: providers.gradleProperty("logdate.googleMapsApiKey").orNull
        ?: localProperties.getProperty("logdate.googleMapsApiKey")
        ?: ""

/**
 * LogDate ships as studio.hypertext.logdate. The retired co.reasonabletech.logdate is still
 * installed on developer devices holding real data, and an install only upgrades in place when
 * the package matches, so -Plogdate.applicationId rebuilds the current source under the old
 * identity until those installs are migrated.
 */
val baseApplicationId: String = the<LogDateReleaseExtension>().applicationId

/**
 * Debug installs under their own identity so they can sit beside a dogfood build rather than
 * colliding with it. The two are signed with different keys -- dogfood with the upload key, debug
 * with the per-machine debug keystore -- so sharing one package makes them mutually exclusive:
 * neither can update the other, and the only way to swap is an uninstall that takes the user's
 * journal with it.
 *
 * Only debug moves. Dogfood must keep the production package because its whole purpose is passing
 * Digital Asset Links for passkey sign-in, which binds package and certificate together. Debug
 * cannot do passkey sign-in under any identity, so it gives up nothing here.
 */
val debugApplicationIdSuffix: String = the<LogDateReleaseExtension>().debugApplicationIdSuffix

/**
 * Production release versionCode comes from CI. Priority order:
 *
 *  1. `LOGDATE_VERSION_CODE` env var (CI sets this from the commit graph plus a
 *     small commit-hash-derived provenance fragment).
 *  2. `logdate.versionCode` Gradle property (for ad-hoc local release builds).
 *  3. Fallback `1` — the historical hard-coded value, kept so `assembleDebug` still works
 *     without extra configuration.
 *
 * Play Store requires monotonically-increasing versionCodes per upload, so keeping this in CI
 * rather than hard-coding prevents collisions while keeping release provenance tied to a commit.
 */
val resolvedVersionCode: Int =
    (
        System.getenv("LOGDATE_VERSION_CODE")
            ?: providers.gradleProperty("logdate.versionCode").orNull
    )?.toIntOrNull() ?: 1

val resolvedVersionName: String =
    System.getenv("LOGDATE_VERSION_NAME")
        ?: providers.gradleProperty("logdate.versionName").orNull
        ?: "0.1.0"
val resolvedPlayTrack: String =
    System.getenv("LOGDATE_PLAY_TRACK")
        ?: providers.gradleProperty("logdate.play.track").orNull
        ?: "internal"

/**
 * Release signing material. Resolved in order:
 *
 *  1. Env vars (CI/secrets): `LOGDATE_RELEASE_STORE_FILE`, `LOGDATE_RELEASE_STORE_PASSWORD`,
 *     `LOGDATE_RELEASE_KEY_ALIAS`, `LOGDATE_RELEASE_KEY_PASSWORD`
 *  2. `~/.gradle/gradle.properties` (`logdate.release.*`)
 *  3. The env file `scripts/create-signing-keystore.sh` already writes next to the keystore it
 *     creates -- `$LOGDATE_SIGNING_DIR/production-upload.env` (default
 *     `~/.logdate-signing/production-upload.env`) -- read automatically so a machine that has
 *     already run that script needs no further setup at all.
 *
 * A normal release build fails closed when none of these resolve. Local benchmark and
 * baseline-profile work may explicitly opt into debug signing with
 * `-Plogdate.allowDebugReleaseSigning=true`; that override must never be used for publishing.
 */
val releaseEnvironmentVariables =
    mapOf(
        "storeFile" to "LOGDATE_RELEASE_STORE_FILE",
        "storePassword" to "LOGDATE_RELEASE_STORE_PASSWORD",
        "keyAlias" to "LOGDATE_RELEASE_KEY_ALIAS",
        "keyPassword" to "LOGDATE_RELEASE_KEY_PASSWORD",
    )

val signingEnvFile: Map<String, String> by lazy {
    val signingDir = System.getenv("LOGDATE_SIGNING_DIR") ?: "${System.getProperty("user.home")}/.logdate-signing"
    val envFile = file("$signingDir/production-upload.env")
    if (!envFile.isFile) {
        emptyMap()
    } else {
        envFile
            .readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    null
                } else {
                    val (key, value) = trimmed.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    key.trim() to value.trim().trim('"', '\'')
                }
            }.toMap()
    }
}

fun resolveRelease(prop: String): String? {
    val envVarName = requireNotNull(releaseEnvironmentVariables[prop])
    return System.getenv(envVarName)
        ?: providers.gradleProperty("logdate.release.$prop").orNull
        ?: signingEnvFile[envVarName]
}

val releaseStoreFile: String? = resolveRelease("storeFile")
val releaseStorePassword: String? = resolveRelease("storePassword")
val releaseKeyAlias: String? = resolveRelease("keyAlias")
val releaseKeyPassword: String? = resolveRelease("keyPassword")
val hasReleaseSigningConfig: Boolean =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
val allowDebugReleaseSigning: Boolean =
    providers.gradleProperty("logdate.allowDebugReleaseSigning").orNull?.toBoolean() == true
val playPublishRequested: Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("publish", ignoreCase = true) || taskName.contains("promote", ignoreCase = true)
    }
val releaseTaskRequested: Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        !taskName.contains("promote", ignoreCase = true) &&
            (
                taskName.contains("Release", ignoreCase = true) ||
                    taskName.contains("Play", ignoreCase = true)
            )
    }

if (releaseTaskRequested && !hasReleaseSigningConfig && !baselineProfileRequested && !allowDebugReleaseSigning) {
    error(
        "Release signing is not configured. Set LOGDATE_RELEASE_STORE_FILE, " +
            "LOGDATE_RELEASE_STORE_PASSWORD, LOGDATE_RELEASE_KEY_ALIAS, and " +
            "LOGDATE_RELEASE_KEY_PASSWORD, or explicitly pass " +
            "-Plogdate.allowDebugReleaseSigning=true for a local non-publishing build.",
    )
}

if (baselineProfileRequested) {
    apply(
        plugin =
            libs.plugins.androidx.baselineprofile
                .get()
                .pluginId,
    )
}

extensions.configure<ApplicationExtension> {
    namespace = "app.logdate.client"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    dynamicFeatures +=
        setOf(
            ":client:feature:remotedisplay",
            ":client:feature:speechrecognition",
        )
    if (recoveryAcceptanceAssetsDir != null) {
        sourceSets.getByName("androidTest").assets.directories += recoveryAcceptanceAssetsDir
    }
    defaultConfig {
        applicationId = baseApplicationId
        manifestPlaceholders["watchExperienceDefault"] =
            providers
                .gradleProperty("logdate.watchExperienceDefault")
                .map(String::toBooleanStrict)
                .getOrElse(false)
                .toString()
        manifestPlaceholders["quietSaveDefault"] =
            providers
                .gradleProperty("logdate.quietSaveDefault")
                .map(String::toBooleanStrict)
                .getOrElse(false)
                .toString()
        // Maps and Places use a dedicated Android-restricted credential. Never fall back to the
        // general Firebase API key generated from google-services.json.
        resValue("string", "google_maps_api_key", resolvedGoogleMapsApiKey)
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (androidTestClassOverride != null) {
            testInstrumentationRunnerArguments["class"] = androidTestClassOverride
        }
        if (androidTestPackageOverride != null) {
            testInstrumentationRunnerArguments["package"] = androidTestPackageOverride
        }
        if (syncProbeArguments != null) {
            testInstrumentationRunnerArguments["logdate.syncProbeArguments"] = syncProbeArguments
        }
        if (recoveryAcceptanceMode != null) {
            testInstrumentationRunnerArguments["logdate.recoveryAcceptanceMode"] = recoveryAcceptanceMode
        }
    }

    if (hasReleaseSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // enable both V1 and V2 signatures so Play accepts the upload
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = debugApplicationIdSuffix
            versionNameSuffix = "-debug"
            enableUnitTestCoverage = true
            // Local managed-device runs can disable instrumentation coverage to
            // reduce emulator memory pressure while keeping CI coverage enabled.
            enableAndroidTestCoverage = androidTestCoverageEnabled
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without these, R8 shrinks the shipped app using only dependency consumer rules -
            // none of AGP's baseline Android keep rules and nothing this project declares.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            // Debug-signed release builds (CI's R8 check) never ship, so their
            // mapping files must not reach Crashlytics.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = !allowDebugReleaseSigning
            }
            signingConfig =
                if (!releaseTaskRequested ||
                    baselineProfileRequested ||
                    (allowDebugReleaseSigning && !hasReleaseSigningConfig)
                ) {
                    signingConfigs.getByName("debug")
                } else {
                    signingConfigs.getByName("release")
                }
            if (baselineProfileRequested) {
                isProfileable = true
            }
        }
        // This benchmark intentionally disables code shrinking, which precludes resource shrinking.
        @Suppress("NotShrinkingResources")
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            // Inherited from release; resource shrinking requires code shrinking to be on.
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
        }
        create("dogfood") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            // initWith copies debug's applicationIdSuffix, which would rename dogfood and break the
            // Digital Asset Links match this build type exists for -- silently, with no build
            // error. Reset both to preserve the production identity.
            applicationIdSuffix = null
            versionNameSuffix = null
            // Signed with the upload key (already trusted for logdate.app's Digital Asset
            // Links) instead of the per-machine debug keystore, so passkey sign-in against
            // production works on a locally-built, fully debuggable install. Falls back to
            // debug signing only when the upload keystore isn't configured on this machine --
            // everything else about the build is unaffected, but passkey sign-in won't work
            // until it is.
            signingConfig =
                if (hasReleaseSigningConfig) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    androidResources {
        noCompress += listOf("cvr")
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
            keepDebugSymbols += "**/libimage_processing_util_jni.so"
            keepDebugSymbols += "**/libmockkjvmtiagent.so"
            keepDebugSymbols += "**/libsqlcipher.so"
            keepDebugSymbols += "**/libsqliteJni.so"
            keepDebugSymbols += "**/libsurface_util_jni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "**/*.properties"
            pickFirsts += "META-INF/DEPENDENCIES"
            pickFirsts += "META-INF/LICENSE"
            pickFirsts += "META-INF/LICENSE.txt"
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/NOTICE"
            pickFirsts += "META-INF/NOTICE.txt"
            pickFirsts += "META-INF/INDEX.LIST"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        // For the generated logdate_account_type string; res values are off by default repo-wide.
        resValues = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        animationsDisabled = true
        // Local managed-device runs can disable orchestration when UTP fails during
        // per-test process result collection. CI keeps orchestration on by default.
        if (androidTestOrchestratorEnabled) {
            execution = "ANDROID_TEST_ORCHESTRATOR"
        }
        unitTests.all {
            it.maxHeapSize = "4g"
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Account authentication must use the final package, including variant suffixes and overrides.
        variant.resValues.put(
            variant.makeResValueKey("string", "logdate_account_type"),
            variant.applicationId.map { ResValue("$it.account") },
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

// The preview renderer keeps native memory for every preview it renders until its process exits.
// CI runners have far less memory than a development machine, so CI restarts the renderer for
// each preview class, uses a smaller heap, and splits the previews across runners with
// comma-separated test patterns.
val screenshotTestMaxHeap = providers.gradleProperty("logdate.screenshotTest.maxHeap").orElse("12g")
val screenshotTestForkEvery = providers.gradleProperty("logdate.screenshotTest.forkEvery").map(String::toLong)
val screenshotTestInclude = providers.gradleProperty("logdate.screenshotTest.include")
val screenshotTestExclude = providers.gradleProperty("logdate.screenshotTest.exclude")

fun patterns(property: Provider<String>): List<String> =
    property.orNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

tasks.withType<Test>().configureEach {
    if (name.contains("Screenshot", ignoreCase = true)) {
        maxHeapSize = screenshotTestMaxHeap.get()
        screenshotTestForkEvery.orNull?.let { forkEvery = it }
        filter {
            patterns(screenshotTestInclude).forEach { includeTestsMatching(it) }
            patterns(screenshotTestExclude).forEach { excludeTestsMatching(it) }
        }
    } else {
        maxHeapSize = "4g"
    }
}

tasks.configureEach {
    if (name.startsWith("mergeExtDex") && name.endsWith("AndroidTest")) {
        val variantName =
            name
                .removePrefix("mergeExtDex")
                .removeSuffix("AndroidTest")
        dependsOn("desugar${variantName}AndroidTestFileDependencies")
    }
}

play {
    track.set(resolvedPlayTrack)
    defaultToAppBundles.set(true)
    // Keyless: CI signs in with Workload Identity Federation as the Play service
    // account. A service-account JSON in ANDROID_PUBLISHER_CREDENTIALS still wins.
    useApplicationDefaultCredentials.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS").isNullOrBlank())
    // Uploads get Play's highest versionCode + 1, so codes never collide or go
    // backwards whatever happens to git history. AUTO queries Play during every
    // release build, so it is limited to publishing; plain builds keep their
    // local versionCode and need no Play credentials.
    resolutionStrategy.set(
        if (playPublishRequested) {
            com.github.triplet.gradle.androidpublisher.ResolutionStrategy.AUTO
        } else {
            com.github.triplet.gradle.androidpublisher.ResolutionStrategy.FAIL
        },
    )
}

fun registerSpeechBundleVerification(variantName: String) =
    tasks.register("verifySpeechRecognition${variantName.replaceFirstChar(Char::uppercase)}Bundle") {
        val bundleTaskName = "bundle${variantName.replaceFirstChar(Char::uppercase)}"
        val bundleFile = layout.buildDirectory.file("outputs/bundle/$variantName/android-main-$variantName.aab")
        group = "verification"
        description = "Verifies that the $variantName app bundle contains the install-time speech engine."
        dependsOn(bundleTaskName)
        inputs.file(bundleFile)

        doLast {
            val artifact = bundleFile.get().asFile
            check(artifact.isFile) { "Android App Bundle not found: ${artifact.absolutePath}" }

            ZipFile(artifact).use { bundle ->
                val entries = bundle.entries().asSequence().associateBy { it.name }
                val requiredEntries =
                    listOf(
                        "speechrecognition/manifest/AndroidManifest.xml",
                        "speechrecognition/assets/sherpa-onnx-stt-en.zip",
                        "speechrecognition/assets/sherpa-onnx-punct-en.zip",
                        "speechrecognition/assets/silero-vad.zip",
                    )
                val missingEntries = requiredEntries.filterNot(entries::containsKey)
                check(missingEntries.isEmpty()) {
                    "Speech recognition bundle entries are missing: ${missingEntries.joinToString()}"
                }

                val manifestText =
                    bundle
                        .getInputStream(entries.getValue("speechrecognition/manifest/AndroidManifest.xml"))
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                check("install-time" in manifestText) {
                    "The speechrecognition feature must use install-time delivery"
                }

                val featureDexEntries = entries.values.filter { it.name.matches(Regex("speechrecognition/dex/classes\\d*\\.dex")) }
                check(
                    featureDexEntries.any { dexEntry ->
                        bundle
                            .getInputStream(dexEntry)
                            .readBytes()
                            .toString(Charsets.ISO_8859_1)
                            .contains("SpeechRecognitionProvider")
                    },
                ) {
                    "SpeechRecognitionProvider is missing from the speechrecognition feature dex"
                }

                check(entries.keys.any { it.matches(Regex("speechrecognition/lib/.+/libsherpa-onnx-jni\\.so")) }) {
                    "Sherpa-ONNX native libraries are missing from the speechrecognition feature"
                }
            }
        }
    }

val verifySpeechRecognitionDebugBundle = registerSpeechBundleVerification("debug")
val verifySpeechRecognitionReleaseBundle = registerSpeechBundleVerification("release")

tasks.matching { it.name == "publishReleaseBundle" }.configureEach {
    dependsOn(verifySpeechRecognitionReleaseBundle)
}

dependencies {
    implementation(projects.app.composeMain)
    implementation(projects.client.location)
    implementation(libs.androidx.profileinstaller)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)
    if (baselineProfileRequested) {
        add("baselineProfile", project(":benchmark:phone-baselineprofile"))
    }
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(projects.client.media)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(projects.client.ui)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.datetime)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockk)
    testImplementation(libs.compose.runtime)
    testImplementation(libs.nav3.runtime)
    testImplementation(libs.androidx.health.connect)
    testImplementation(libs.play.services.wearable)
    testImplementation(libs.wear.remote.interactions)
    testImplementation(projects.client.feature.core)
    testImplementation(projects.client.data)
    testImplementation(projects.client.database)
    testImplementation(projects.client.domain)
    testImplementation(projects.client.healthConnect)
    testImplementation(projects.client.media)
    testImplementation(projects.client.notifications)
    testImplementation(projects.client.feature.editor)
    testImplementation(projects.client.repository)
    testImplementation(projects.client.sync)
    testImplementation(projects.client.device)
    testImplementation(projects.client.datastore)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(projects.client.networking)
    testImplementation(projects.shared.config)
    testImplementation(projects.shared.model)

    androidTestImplementation(projects.app.composeMain)
    androidTestImplementation(projects.client.feature.core)
    androidTestImplementation(projects.client.feature.editor)
    androidTestImplementation(projects.client.feature.events)
    androidTestImplementation(projects.client.feature.journal)
    androidTestImplementation(projects.client.feature.postcards)
    androidTestImplementation(projects.client.feature.timeline)
    androidTestImplementation(projects.client.feature.locationTimeline)
    androidTestImplementation(projects.client.feature.onboarding)
    androidTestImplementation(projects.client.awareness)
    androidTestImplementation(projects.client.domain)
    androidTestImplementation(projects.client.healthConnect)
    androidTestImplementation(projects.client.intelligence)
    androidTestImplementation(projects.client.location)
    androidTestImplementation(projects.client.media)
    androidTestImplementation(projects.client.networking)
    androidTestImplementation(projects.client.notifications)
    androidTestImplementation(projects.client.permissions)
    androidTestImplementation(projects.client.repository)
    androidTestImplementation(projects.client.database)
    androidTestImplementation(projects.client.data)
    androidTestImplementation(projects.client.datastore)
    androidTestImplementation(projects.client.sharing)
    androidTestImplementation(projects.client.sync)
    androidTestImplementation(projects.client.device)
    androidTestImplementation(projects.client.feature.rewind)
    androidTestImplementation(projects.shared.config)
    androidTestImplementation(projects.shared.model)
    androidTestImplementation(libs.nav3.runtime)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.play.services.wearable)
    androidTestImplementation(libs.play.services.location)
    androidTestImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.ktor.client.core)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.sqlcipher.android)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coil.compose)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestUtil(libs.androidx.test.orchestrator)
    // Emulator-only WebAuthn provider, installed alongside the app so instrumented tests can
    // drive a real Credential Manager ceremony. A project() dependency cannot be used here:
    // androidTestUtil requires every resolved file to be an APK, and a project dependency
    // brings the module's runtime classpath with it.
    if (!providers.gradleProperty("logdate.skipPasskeyTestProvider").map(String::toBoolean).getOrElse(false)) {
        androidTestUtil(
            files(
                rootProject.layout.projectDirectory
                    .file("tools/passkey-test-provider/build/outputs/apk/debug/passkey-test-provider-debug.apk"),
            ),
        )
    }
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.compose.material3)
    androidTestImplementation(libs.napier)
    androidTestImplementation(libs.okio)
    androidTestImplementation(libs.media3.common)
    androidTestImplementation(libs.media3.session)
    androidTestImplementation(project.dependencies.platform(libs.koin.bom))
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.koin.android)
    androidTestImplementation(libs.koin.androidx.workmanager)
    androidTestImplementation(libs.androidx.work.runtime)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.glance.appwidget)
    androidTestImplementation(projects.client.feature.androidWidgets)
    androidTestImplementation(libs.koin.test)
    androidTestImplementation(projects.client.feature.library)
    androidTestImplementation(projects.client.feature.search)
    androidTestImplementation(projects.client.theme)
    androidTestImplementation(projects.client.ui)
    androidTestImplementation(libs.androidx.camera.view)
    androidTestImplementation(libs.compose.material.icons.extended)

    screenshotTestImplementation(libs.androidx.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.material3)
    screenshotTestImplementation(libs.compose.material.icons.extended)
    screenshotTestImplementation(libs.compose.components.resources)
    screenshotTestImplementation(libs.compose.runtime)
    screenshotTestImplementation(libs.compose.foundation)
    screenshotTestImplementation(projects.client.awareness)
    screenshotTestImplementation(projects.client.feature.editor)
    screenshotTestImplementation(projects.client.screenshotScenes)
    screenshotTestImplementation(projects.client.feature.core)
    screenshotTestImplementation(projects.client.feature.events)
    screenshotTestImplementation(projects.client.feature.postcards)
    screenshotTestImplementation(projects.client.calendarSync)
    screenshotTestImplementation(projects.client.feature.onboarding)
    screenshotTestImplementation(projects.client.feature.journal)
    screenshotTestImplementation(projects.client.feature.rewind)
    screenshotTestImplementation(projects.client.feature.timeline)
    screenshotTestImplementation(projects.client.feature.locationTimeline)
    screenshotTestImplementation(projects.client.feature.library)
    screenshotTestImplementation(projects.client.feature.search)
    screenshotTestImplementation(projects.client.permissions)
    screenshotTestImplementation(projects.client.domain)
    screenshotTestImplementation(projects.client.data)
    screenshotTestImplementation(projects.client.database)
    screenshotTestImplementation(projects.client.media)
    screenshotTestImplementation(projects.client.sync)
    screenshotTestImplementation(projects.client.location)
    screenshotTestImplementation(projects.client.repository)
    screenshotTestImplementation(projects.client.ui)
    screenshotTestImplementation(projects.client.billing)
    screenshotTestImplementation(projects.client.theme)
    screenshotTestImplementation(projects.shared.model)
    screenshotTestImplementation(projects.app.composeMain)
    screenshotTestImplementation(libs.coil.compose)
    screenshotTestImplementation(libs.kotlinx.datetime)
    screenshotTestImplementation(libs.nav3.ui)
    androidTestImplementation(libs.nav3.ui)
    screenshotTestImplementation(libs.nav3.runtime)
}

afterEvaluate {
    tasks.matching { it.name.startsWith("uninstall") }.configureEach {
        enabled = false
        doFirst {
            throw GradleException(
                "Uninstall tasks are disabled to protect app data on connected devices. " +
                    "Install the variant already on the device to upgrade in place: debug and " +
                    "dogfood have different applicationIds and signing keys, so neither can " +
                    "replace the other.",
            )
        }
    }
}

/**
 * The Google Services plugin refuses to build unless google-services.json declares a client for the
 * applicationId being built. The real file comes from repository secrets; locally there is a
 * placeholder, and it has to be rewritten whenever -Plogdate.applicationId changes. Doing that by
 * hand means a confusing "No matching client found" failure every time it is forgotten.
 */
val writeLocalGoogleServices by tasks.registering(Exec::class) {
    description = "Regenerates the placeholder google-services.json for the applicationIds being built."
    val config = layout.projectDirectory.file("google-services.json").asFile
    // A real Firebase configuration is left alone; the script refuses to overwrite one.
    onlyIf { !config.exists() || config.readText().contains("logdate-local-stub") }
    // Every id that any variant builds under. The plugin matches the variant's full applicationId,
    // so a stub naming only the base id fails the debug build with "No matching client found".
    environment("LOGDATE_APPLICATION_ID", baseApplicationId)
    environment("LOGDATE_APPLICATION_ID_EXTRA", "$baseApplicationId$debugApplicationIdSuffix")
    commandLine("bash", rootProject.file("scripts/write-local-google-services.sh").absolutePath)
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("GoogleServices") }.configureEach {
    dependsOn(writeLocalGoogleServices)
}

// The passkey provider APK is consumed by androidTestUtil as a plain file, so nothing in the
// dependency graph builds it. Every instrumented-test task needs it on disk first.
tasks.matching { it.name.endsWith("AndroidTest") }.configureEach {
    if (!providers.gradleProperty("logdate.skipPasskeyTestProvider").map(String::toBoolean).getOrElse(false)) {
        dependsOn(":tools:passkey-test-provider:assembleDebug")
    }
}
