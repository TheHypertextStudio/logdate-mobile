@file:Suppress("UnstableApiUsage")

import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    id("app.logdate.home-workspace")
    id("app.logdate.screenshot-coverage")
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.androidx.benchmark) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.gradlePlayPublisher) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.benManesVersions)
    jacoco
}

// Pins the npm `ws` package pulled in transitively by the wasmJs() browser() targets
// (shared/model, shared/config, client/util, client/networking) to a version without the
// known memory-exhaustion/uninitialized-memory-disclosure advisories. Kotlin/Wasm's default
// resolution otherwise settles on whatever `karma`/`webpack-dev-server` last pinned.
plugins.withType<WasmYarnPlugin> {
    the<WasmYarnRootExtension>().resolution("ws", "8.21.0")
}

// build-logic is an included build, so a root `./gradlew test` never reaches its tests. They cover
// release signing resolution, which decides what the Play publish uploads with, so the root `test`
// task runs them too.
tasks.register("verifyKtlintCoversKotlinSources") {
    group = "verification"
    description = "Fails if a module has Kotlin sources that none of its ktlint tasks reads."
    notCompatibleWithConfigurationCache("Inspects the ktlint tasks of every project.")
    doLast {
        val uncovered =
            subprojects.filter { module ->
                val hasKotlinSources = module.fileTree("src") { include("**/*.kt") }.files.isNotEmpty()
                if (!hasKotlinSources) return@filter false
                val ktlintTasks = module.tasks.withType<BaseKtLintCheckTask>()
                val sourceSetTasks =
                    ktlintTasks.any {
                        it.name.startsWith("runKtlintCheckOver") && !it.name.endsWith("OverKotlinScripts")
                    }
                val scriptTaskReadsKotlin =
                    ktlintTasks.any { task ->
                        task.name.endsWith("OverKotlinScripts") && task.source.files.any { it.extension == "kt" }
                    }
                !sourceSetTasks && !scriptTaskReadsKotlin
            }
        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "ktlintCheck does not lint the Kotlin sources of: " + uncovered.joinToString { it.path },
            )
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the build-logic tests, which the subprojects' test tasks do not reach."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

val ktlintEngineVersion = libs.versions.ktlintEngine

subprojects {
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    configure<KtlintExtension> {
        version.set(ktlintEngineVersion)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    // Modules on AGP's built-in Kotlin never apply a Kotlin plugin, so the ktlint plugin finds no Kotlin
    // source sets and registers only the Kotlin-script tasks. Point those tasks at the module's
    // Kotlin sources too, so `ktlintCheck` and `ktlintFormat` cover them.
    afterEvaluate {
        val coversKotlinSources =
            tasks.names.any {
                it.startsWith("runKtlintCheckOver") && it != "runKtlintCheckOverKotlinScripts"
            }
        if (coversKotlinSources) return@afterEvaluate
        tasks.withType<BaseKtLintCheckTask>().configureEach {
            if (name.endsWith("OverKotlinScripts")) {
                source(fileTree("src") { include("**/*.kt") })
            }
        }
    }

    // Size limits only (see config/detekt/detekt.yml and
    // docs/reference/standards/entrypoint-structure.md). Only the plain `detekt` task runs;
    // type-resolution tasks are disabled in gradle.properties.
    apply { plugin("dev.detekt") }
    configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Main source sets only: every `src/<name>Test*/` and `src/test/` tree is skipped.
        source.setFrom(
            fileTree("src") {
                include("**/*.kt")
                exclude("test/**", "*Test*/**")
            },
        )
        // Only modules with pre-existing findings carry a baseline; a missing file is not an error.
        baseline.set(file("detekt-baseline.xml").takeIf { it.exists() })
        parallel.set(false)
    }
    tasks.withType<DetektCreateBaselineTask>().configureEach {
        baseline.set(file("detekt-baseline.xml"))
    }

    dependencies {
        components {
            listOf(
                "io.coil-kt.coil3:coil-core-jvm",
                "io.coil-kt.coil3:coil-core-iossimulatorarm64",
            ).forEach { moduleId ->
                withModule(moduleId) {
                    allVariants {
                        withDependencies {
                            removeAll { it.group == "org.jetbrains.skiko" }
                        }
                    }
                }
            }
        }
    }
}

private val featureCoverageModules =
    setOf(
        ":app:android-main",
        ":app:wear",
    )

subprojects {
    if (path in featureCoverageModules) {
        apply(plugin = "jacoco")

        tasks.withType<Test>().configureEach {
            extensions.configure(JacocoTaskExtension::class) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

val atprotoModulePaths =
    listOf(
        ":shared:atproto-crypto",
        ":shared:atproto-syntax",
        ":shared:atproto-identity",
        ":shared:atproto-xrpc",
        ":shared:atproto-repo",
        ":shared:atproto-plc",
        ":shared:atproto-lexicon",
        ":shared:atproto-pds",
        ":shared:atproto-pds-runtime",
        ":shared:atproto-oauth-client",
    )

val androidScreenshotValidationTasks =
    listOf(
        ":app:android-main:validateDebugScreenshotTest",
        ":app:wear:validateDebugScreenshotTest",
    )

val androidScreenshotUpdateTasks =
    listOf(
        ":app:android-main:updateDebugScreenshotTest",
        ":app:wear:updateDebugScreenshotTest",
    )

val universalScreenshotHostModules =
    listOf(
        ":app:compose-main",
        ":client:awareness",
        ":client:feature:core",
        ":client:feature:editor",
        ":client:feature:events",
        ":client:feature:journal",
        ":client:feature:library",
        ":client:feature:location-timeline",
        ":client:feature:onboarding",
        ":client:feature:postcards",
        ":client:feature:rewind",
        ":client:feature:search",
        ":client:feature:stickers",
        ":client:feature:timeline",
        ":client:media",
        ":client:permissions",
        ":client:screenshot-scenes",
        ":client:sharing",
        ":client:theme",
        ":client:ui",
    )

fun existingTaskPaths(
    projectPaths: List<String>,
    taskName: String,
): List<String> =
    projectPaths.mapNotNull { path ->
        findProject(path)?.tasks?.findByName(taskName)?.path
    }

// The API reference merges every published AT Protocol module into one site at build/dokka/html.
dependencies {
    atprotoModulePaths.forEach { dokka(project(it)) }
}

tasks.register("generateAtprotoDokka") {
    group = "documentation"
    description = "Generate Dokka HTML publications for every ATProto module, and the merged site."
    dependsOn(atprotoModulePaths.map { "$it:dokkaGeneratePublicationHtml" })
    dependsOn(":dokkaGeneratePublicationHtml")
}

tasks.register("publishAtprotoToMavenLocal") {
    group = "publishing"
    description = "Publish every ATProto module to the local Maven repository."
    dependsOn(atprotoModulePaths.map { "$it:publishToMavenLocal" })
}

tasks.register("managedPhoneDebugAndroidTest") {
    group = "verification"
    description = "Run app/android-main instrumented tests on the oldest-supported and flagship managed phones."
    dependsOn(":app:android-main:phoneDevicesGroupDebugAndroidTest")
}

tasks.register("managedAndroidSmoke") {
    group = "verification"
    description = "Run the fast managed Android smoke lane for app/android-main on the flagship phone and tablet."
    dependsOn(":app:android-main:smokeDevicesGroupDebugAndroidTest")
}

tasks.register<Exec>("managedAndroidE2EDebugAndroidTest") {
    group = "verification"
    description = "Run app/android-main end-to-end instrumentation tests on the managed Android smoke lane."
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        ":app:android-main:smokeDevicesGroupDebugAndroidTest",
        "-Plogdate.androidTestPackage=app.logdate.client.e2e",
    )
}

tasks.register<Exec>("managedAndroidMultiWindowDebugAndroidTest") {
    group = "verification"
    description = "Run the multi-window Android e2e suite on the managed Android matrix."
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        ":app:android-main:deviceMatrixGroupDebugAndroidTest",
        "-Plogdate.androidTestClass=app.logdate.client.e2e.MultiWindowEditorE2ETest",
    )
}

tasks.register<Exec>("managedAndroidShareDebugAndroidTest") {
    group = "verification"
    description = "Run the Android share e2e suite on the managed Android smoke lane."
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        ":app:android-main:smokeDevicesGroupDebugAndroidTest",
        "-Plogdate.androidTestClass=app.logdate.client.e2e.IncomingShareE2ETest,app.logdate.client.e2e.ShareReceiverE2ETest,app.logdate.client.e2e.SharingEntryPointsE2ETest",
    )
}

tasks.register("managedAndroidMatrix") {
    group = "verification"
    description = "Run the managed Android phone and tablet matrix for app/android-main."
    dependsOn(":app:android-main:deviceMatrixGroupDebugAndroidTest")
}

tasks.register("managedTabletDebugAndroidTest") {
    group = "verification"
    description = "Run app/android-main instrumented tests on the managed tablet device."
    dependsOn(":app:android-main:tabletDevicesGroupDebugAndroidTest")
}

tasks.register<Exec>("managedTabletMediaCoverageAndroidTest") {
    group = "verification"
    description = "Run AndroidMediaManager instrumentation coverage on the managed tablet device."
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        ":app:android-main:createManagedDeviceDebugAndroidTestCoverageReport",
        "-Plogdate.managedDeviceProfile=tabletOnly",
        "-Plogdate.androidTestClass=app.logdate.client.media.AndroidMediaManagerTest",
    )
}

tasks.register("managedWearDebugAndroidTest") {
    group = "verification"
    description = "Run app/wear instrumented tests on the shared managed Wear OS device."
    dependsOn(":app:wear:wearDevicesGroupDebugAndroidTest")
}

tasks.register("managedPhoneBenchmark") {
    group = "verification"
    description = "Run phone macrobenchmarks on the shared managed benchmark device."
    dependsOn(":benchmark:phone-macro:phoneBenchmarkDevicesGroupBenchmarkAndroidTest")
}

tasks.register("managedWearBenchmark") {
    group = "verification"
    description = "Run Wear OS macrobenchmarks on the shared managed benchmark device."
    dependsOn(":benchmark:wear-macro:wearBenchmarkDevicesGroupBenchmarkAndroidTest")
}

tasks.register("managedMicroBenchmark") {
    group = "verification"
    description = "Run Android microbenchmarks on the managed phone device."
    dependsOn(":benchmark:micro:microBenchmarkDevicesGroupReleaseAndroidTest")
}

tasks.register("managedBenchmark") {
    group = "verification"
    description = "Run all managed phone and Wear benchmark suites."
    dependsOn(
        "managedPhoneBenchmark",
        "managedWearBenchmark",
    )
}

tasks.register("validateAndroidScreenshots") {
    group = "verification"
    description = "Validate Android and Wear screenshot baselines using the Android screenshot plugin."
    dependsOn(androidScreenshotValidationTasks)
}

tasks.register("updateAndroidScreenshots") {
    group = "verification"
    description = "Update Android and Wear screenshot baselines using the Android screenshot plugin."
    dependsOn(androidScreenshotUpdateTasks)
}

val validateDesktopScreenshots =
    tasks.register("validateDesktopScreenshots") {
        group = "verification"
        description = "Validate committed Desktop screenshot baselines."
        dependsOn(":app:compose-main:validateDesktopScreenshotTest")
    }

val validateIosScreenshots =
    tasks.register("validateIosScreenshots") {
        group = "verification"
        description =
            "Run the iOS simulator host-test lane for screenshot-covered UI modules. " +
            "This is the iOS half of the universal screenshot contract until an iOS baseline renderer lands."
    }

tasks.register("updateDesktopScreenshots") {
    group = "verification"
    description = "Update committed Desktop screenshot baselines."
    dependsOn(":app:compose-main:updateDesktopScreenshotTest")
}

tasks.register("updateIosScreenshots") {
    group = "verification"
    description =
        "Placeholder for future iOS screenshot baseline updates. " +
        "iOS currently validates through simulator host tests only."
    doLast {
        logger.lifecycle(
            "iOS baseline updates are not implemented yet. " +
                "Use validateIosScreenshots to run the iOS visual lane.",
        )
    }
}

tasks.register("validateAllScreenshots") {
    group = "verification"
    description =
        "Run the universal screenshot lane across Android, Wear, Desktop, and iOS. " +
        "Android/Wear/Desktop use baseline screenshots; iOS uses a simulator validation lane."
    dependsOn(
        "validateAndroidScreenshots",
        "validateDesktopScreenshots",
        "validateIosScreenshots",
    )
}

tasks.register("updateAllScreenshots") {
    group = "verification"
    description =
        "Update every supported screenshot baseline lane. " +
        "Today this updates Android/Wear/Desktop baselines and leaves iOS as validate-only."
    dependsOn(
        "updateAndroidScreenshots",
        "updateDesktopScreenshots",
    )
}

gradle.projectsEvaluated {
    validateIosScreenshots.configure {
        dependsOn(existingTaskPaths(universalScreenshotHostModules, "iosSimulatorArm64Test"))
    }
}

tasks.register("managedAndroidPerformance") {
    group = "verification"
    description = "Run the managed Android phone performance lane: phone macrobenchmarks, microbenchmarks, and baseline profile generation."
    dependsOn(
        "managedPhoneBenchmark",
        "managedMicroBenchmark",
        "generatePhoneBaselineProfile",
    )
}

tasks.register("generatePhoneBaselineProfile") {
    group = "verification"
    description = "Generate and copy the phone app baseline profile."
    dependsOn(":benchmark:phone-baselineprofile:collectNonMinifiedReleaseBaselineProfile")
}

tasks.register("generateWearBaselineProfile") {
    group = "verification"
    description = "Generate and copy the Wear app baseline profile."
    dependsOn(":benchmark:wear-baselineprofile:collectNonMinifiedReleaseBaselineProfile")
}

tasks.register("managedDeviceDebugAndroidTest") {
    group = "verification"
    description = "Run all instrumented tests on the shared managed phone, tablet, and Wear OS device matrix."
    dependsOn(
        ":app:android-main:deviceMatrixGroupDebugAndroidTest",
        "managedWearDebugAndroidTest",
    )
}

tasks.register<JacocoReport>("featureCoverageReport") {
    group = "verification"
    description = "Generate coverage for the Wear synced-audio playback feature."

    val wearProject = project(":app:wear")
    val phoneProject = project(":app:android-main")
    val composeProject = project(":app:compose-main")
    val mediaProject = project(":client:media")

    dependsOn(
        ":app:wear:testDebugUnitTest",
        ":app:android-main:testDebugUnitTest",
    )

    executionData.setFrom(
        files(
            wearProject.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
            phoneProject.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
        ),
    )

    classDirectories.setFrom(
        files(
            wearProject.fileTree(
                wearProject.layout.buildDirectory
                    .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
                    .get()
                    .asFile,
            ) {
                include(
                    "app/logdate/wear/playback/PhoneSyncedAudioResolver*.class",
                    "app/logdate/wear/presentation/timeline/WearTimelineViewModel*.class",
                    "app/logdate/wear/sync/GoogleWearDataLayerClient*.class",
                )
            },
            composeProject.fileTree(
                composeProject.layout.buildDirectory
                    .dir("classes/kotlin/android/main")
                    .get()
                    .asFile,
            ) {
                include(
                    "app/logdate/client/sync/PhoneDataLayerListenerService*.class",
                    "app/logdate/client/sync/DefaultPhoneWearSyncBridge*.class",
                    "app/logdate/client/sync/GooglePhoneWearTransport*.class",
                    "app/logdate/client/sync/AndroidPhoneAudioStreamOpener*.class",
                )
            },
            mediaProject.fileTree(
                mediaProject.layout.buildDirectory
                    .dir("classes/kotlin/android/main")
                    .get()
                    .asFile,
            ) {
                include("app/logdate/client/media/audio/AndroidAudioPlaybackManager*.class")
            },
        ),
    )

    sourceDirectories.setFrom(
        files(
            wearProject.layout.projectDirectory.dir("src/main/kotlin"),
            composeProject.layout.projectDirectory.dir("src/androidMain/kotlin"),
            mediaProject.layout.projectDirectory.dir("src/androidMain/kotlin"),
        ),
    )
    additionalSourceDirs.setFrom(sourceDirectories)

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/featureCoverageReport/featureCoverageReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/featureCoverageReport/html"))
        csv.required.set(false)
    }

    doFirst {
        val missingExecFiles =
            executionData.files.filterNot { it.exists() }
        if (missingExecFiles.isNotEmpty()) {
            throw GradleException(
                "Missing JaCoCo execution data for feature coverage: " +
                    missingExecFiles.joinToString { it.absolutePath },
            )
        }
    }
}

tasks.register<JacocoReport>("mediaAndroidTestCoverageReport") {
    group = "verification"
    description = "Generate Android instrumentation coverage for AndroidMediaManager."

    val appProject = project(":app:android-main")
    val mediaProject = project(":client:media")
    val managedDeviceCoverageFiles =
        files(
            appProject.layout.buildDirectory.file(
                "outputs/managed_device_code_coverage/debug/largeScreenTabletApi35/coverage.ec",
            ),
            appProject.layout.buildDirectory.file(
                "intermediates/managed_device_code_coverage/debugAndroidTest/largeScreenTabletApi35DebugAndroidTest/coverage.ec",
            ),
        )

    dependsOn("managedTabletMediaCoverageAndroidTest")

    executionData.setFrom(
        managedDeviceCoverageFiles,
    )

    classDirectories.setFrom(
        files(
            mediaProject.fileTree(
                mediaProject.layout.buildDirectory
                    .dir("classes/kotlin/android/main")
                    .get()
                    .asFile,
            ) {
                include("app/logdate/client/media/AndroidMediaManager*.class")
            },
        ),
    )

    sourceDirectories.setFrom(
        files(
            mediaProject.layout.projectDirectory.dir("src/androidMain/kotlin"),
        ),
    )
    additionalSourceDirs.setFrom(sourceDirectories)

    reports {
        xml.required.set(true)
        xml.outputLocation.set(
            layout.buildDirectory.file("reports/jacoco/mediaAndroidTestCoverageReport/mediaAndroidTestCoverageReport.xml"),
        )
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/mediaAndroidTestCoverageReport/html"))
        csv.required.set(false)
    }

    doFirst {
        val coverageFiles =
            executionData.files.filter { it.exists() }
        if (coverageFiles.isEmpty()) {
            throw GradleException(
                "Missing managed-device Android instrumentation coverage data for media coverage under " +
                    managedDeviceCoverageFiles.files.joinToString { it.absolutePath },
            )
        }
    }
}

// Kover configuration for test coverage
kover {
    reports {
        total {
            html {
                onCheck = true
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }
            xml {
                onCheck = true
                xmlFile = layout.buildDirectory.file("reports/kover/coverage.xml")
            }
            verify {
                rule {
                    minBound(70) // 70% minimum coverage threshold
                }
            }
        }
        filters {
            excludes {
                // Exclude generated code and build configuration
                classes(
                    "*.BuildConfig*",
                    "*.*Test*",
                    "*.test.*",
                    "*.*_Impl*", // Room generated DAOs
                    "*.*_Factory*", // Koin generated factories
                    "*.di.*Module*", // DI modules
                    "*ComposableSingletons*", // Compose generated code
                    "*.*\$WhenMappings*", // Kotlin when expression mappings
                )
                packages(
                    "*.di", // All DI packages
                    "*.test", // Test packages
                    "*.generated", // Generated code packages
                )
                annotatedBy(
                    "androidx.compose.runtime.Composable", // Exclude Composable functions
                    "org.koin.core.annotation.Module", // Exclude Koin modules
                )
            }
        }
    }
}
