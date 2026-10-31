package app.logdate

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared setup for LogDate's Kotlin Multiplatform libraries, applied as `id("app.logdate.kmp-library")`.
 *
 * Applies Kotlin Multiplatform and declares the iOS device and simulator targets every library
 * ships. Each source set opts in to `kotlin.uuid` and `kotlin.time`, and compiles expect/actual
 * classes without the Beta warning. When the module also applies the Android KMP library plugin,
 * its Android target takes `compileSdk` and `minSdk` from the version catalog and compiles for
 * JVM 17, the bytecode level of the Android apps. Desktop tests render Compose offscreen, so they
 * run headless rather than registering a foreground Java app on macOS.
 *
 * A module still declares what is particular to it: its JVM target (`jvm()` or `jvm("desktop")`),
 * any Wasm target, its Android namespace and test builders, and its dependencies.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.iosArm64()
            kotlin.iosSimulatorArm64()
            kotlin.compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            kotlin.sourceSets.configureEach {
                languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
                languageSettings.optIn("kotlin.time.ExperimentalTime")
            }
            tasks.withType(Test::class.java).named { it == "desktopTest" }.configureEach {
                systemProperty("java.awt.headless", "true")
            }
            pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
                kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
                    compileSdk = libs.intVersion("android-compileSdk")
                    minSdk = libs.intVersion("android-minSdk")
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }
}
