package app.logdate

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * Repository-wide coverage, applied to the root project as `id("app.logdate.coverage-reports")`.
 *
 * Configures Kover's total report, which `koverVerify` holds to [MINIMUM_LINE_COVERAGE]. Two JaCoCo
 * reports cover code that Kover cannot measure: the Wear synced-audio feature, exercised by the
 * phone and watch unit tests, and the Android media manager, exercised by instrumentation on the
 * managed tablet.
 */
class CoverageReportsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlinx.kover")
        project.pluginManager.apply("jacoco")
        configureKover(project)
        configureFeatureCoverageModules(project)
        registerFeatureCoverageReport(project)
        registerMediaAndroidTestCoverageReport(project)
    }

    companion object {
        /** The merged line coverage `koverVerify` requires, in percent. */
        const val MINIMUM_LINE_COVERAGE = 70
    }
}

private fun configureKover(project: Project) {
    project.extensions.configure(KoverProjectExtension::class.java) {
        reports {
            total {
                html {
                    onCheck.set(true)
                    htmlDir.set(project.layout.buildDirectory.dir("reports/kover/html"))
                }
                xml {
                    onCheck.set(true)
                    xmlFile.set(project.layout.buildDirectory.file("reports/kover/coverage.xml"))
                }
                verify {
                    rule {
                        minBound(CoverageReportsPlugin.MINIMUM_LINE_COVERAGE)
                    }
                }
            }
            filters {
                excludes {
                    // Generated code and build configuration
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
                        "*.di",
                        "*.test",
                        "*.generated",
                    )
                    annotatedBy(
                        "androidx.compose.runtime.Composable",
                        "org.koin.core.annotation.Module",
                    )
                }
            }
        }
    }
}

/** The apps whose unit tests exercise the Wear synced-audio feature. */
private val featureCoverageModules = listOf(":app:android-main", ":app:wear")

private fun configureFeatureCoverageModules(project: Project) {
    featureCoverageModules.forEach { path ->
        project.project(path) {
            pluginManager.apply("jacoco")
            tasks.withType(Test::class.java).configureEach {
                extensions.configure(JacocoTaskExtension::class.java) {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }
}

private fun registerFeatureCoverageReport(project: Project) {
    project.tasks.register("featureCoverageReport", JacocoReport::class.java) {
        group = "verification"
        description = "Generate coverage for the Wear synced-audio playback feature."

        val wear = project.project(":app:wear")
        val phone = project.project(":app:android-main")
        val compose = project.project(":app:compose-main")
        val media = project.project(":client:media")

        dependsOn(":app:wear:testDebugUnitTest", ":app:android-main:testDebugUnitTest")

        executionData.setFrom(
            wear.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
            phone.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
        )
        classDirectories.setFrom(
            wear.layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").map { classes ->
                project.fileTree(classes) {
                    include(
                        "app/logdate/wear/playback/PhoneSyncedAudioResolver*.class",
                        "app/logdate/wear/presentation/timeline/WearTimelineViewModel*.class",
                        "app/logdate/wear/sync/GoogleWearDataLayerClient*.class",
                    )
                }
            },
            compose.layout.buildDirectory.dir("classes/kotlin/android/main").map { classes ->
                project.fileTree(classes) {
                    include(
                        "app/logdate/client/sync/PhoneDataLayerListenerService*.class",
                        "app/logdate/client/sync/DefaultPhoneWearSyncBridge*.class",
                        "app/logdate/client/sync/GooglePhoneWearTransport*.class",
                        "app/logdate/client/sync/AndroidPhoneAudioStreamOpener*.class",
                    )
                }
            },
            media.layout.buildDirectory.dir("classes/kotlin/android/main").map { classes ->
                project.fileTree(classes) { include("app/logdate/client/media/audio/AndroidAudioPlaybackManager*.class") }
            },
        )
        sourceDirectories.setFrom(
            wear.layout.projectDirectory.dir("src/main/kotlin"),
            compose.layout.projectDirectory.dir("src/androidMain/kotlin"),
            media.layout.projectDirectory.dir("src/androidMain/kotlin"),
        )
        additionalSourceDirs.setFrom(sourceDirectories)

        reports {
            xml.required.set(true)
            xml.outputLocation.set(project.layout.buildDirectory.file("reports/jacoco/featureCoverageReport/featureCoverageReport.xml"))
            html.required.set(true)
            html.outputLocation.set(project.layout.buildDirectory.dir("reports/jacoco/featureCoverageReport/html"))
            csv.required.set(false)
        }

        val executionFiles = executionData
        doFirst {
            val missing = executionFiles.files.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                throw GradleException("Missing JaCoCo execution data for feature coverage: " + missing.joinToString { it.absolutePath })
            }
        }
    }
}

private fun registerMediaAndroidTestCoverageReport(project: Project) {
    project.tasks.register("mediaAndroidTestCoverageReport", JacocoReport::class.java) {
        group = "verification"
        description =
            "Generate Android instrumentation coverage for AndroidMediaManager. Run it as " +
            "./run test:coverage:media, which selects the managed tablet and the test class."

        val app = project.project(":app:android-main")
        val media = project.project(":client:media")
        val coverageFiles =
            project.files(
                app.layout.buildDirectory.file("outputs/managed_device_code_coverage/debug/largeScreenTabletApi35/coverage.ec"),
                app.layout.buildDirectory.file(
                    "intermediates/managed_device_code_coverage/debugAndroidTest/largeScreenTabletApi35DebugAndroidTest/coverage.ec",
                ),
            )

        dependsOn(":app:android-main:createManagedDeviceDebugAndroidTestCoverageReport")

        executionData.setFrom(coverageFiles)
        classDirectories.setFrom(
            media.layout.buildDirectory.dir("classes/kotlin/android/main").map { classes ->
                project.fileTree(classes) { include("app/logdate/client/media/AndroidMediaManager*.class") }
            },
        )
        sourceDirectories.setFrom(media.layout.projectDirectory.dir("src/androidMain/kotlin"))
        additionalSourceDirs.setFrom(sourceDirectories)

        reports {
            xml.required.set(true)
            xml.outputLocation.set(
                project.layout.buildDirectory.file("reports/jacoco/mediaAndroidTestCoverageReport/mediaAndroidTestCoverageReport.xml"),
            )
            html.required.set(true)
            html.outputLocation.set(project.layout.buildDirectory.dir("reports/jacoco/mediaAndroidTestCoverageReport/html"))
            csv.required.set(false)
        }

        doFirst {
            if (coverageFiles.files.none { it.exists() }) {
                throw GradleException(
                    "Missing managed-device Android instrumentation coverage data for media coverage under " +
                        coverageFiles.files.joinToString { it.absolutePath },
                )
            }
        }
    }
}
