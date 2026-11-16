package app.logdate

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * The Kotlin Multiplatform setup every LogDate library shares, applied as `app.logdate.kmp-base`.
 *
 * Applies Kotlin Multiplatform and the serialization plugin, and declares the iOS device and
 * simulator targets every library ships. Each source set opts in to `kotlin.uuid` and `kotlin.time`,
 * and expect/actual classes compile without the Beta warning. Common tests get `kotlin-test` and
 * `kotlinx-coroutines-test`. Desktop tests render Compose offscreen, so they run headless rather
 * than registering a foreground Java app on macOS.
 *
 * Libraries with an Android target apply `app.logdate.kmp-library` ([KmpLibraryPlugin]), which
 * builds on this plugin. Only libraries without an Android target, such as the models the server
 * shares, apply this one directly.
 *
 * A module still declares what is particular to it: its JVM target (`jvm()` or `jvm("desktop")`),
 * any Wasm target, and its dependencies.
 */
class KmpBasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.iosArm64()
            kotlin.iosSimulatorArm64()
            kotlin.compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            kotlin.sourceSets.configureEach {
                languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
                languageSettings.optIn("kotlin.time.ExperimentalTime")
            }

            dependencies.add("commonTestImplementation", libs.library("kotlin-test"))
            dependencies.add("commonTestImplementation", libs.library("kotlinx-coroutines-test"))

            tasks.withType(Test::class.java).named { it == "desktopTest" }.configureEach {
                systemProperty("java.awt.headless", "true")
            }
        }
    }
}
