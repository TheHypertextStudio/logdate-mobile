package app.logdate

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention plugin for Kotlin Multiplatform library modules with an Android target.
 *
 * Applies the Kotlin Multiplatform, Android KMP library, and serialization plugins and configures
 * compile/min SDK, JVM 17, host tests, iOS targets, shared opt-ins, and the common test
 * dependencies. Modules still declare their own `namespace`, JVM target, and dependencies.
 */
class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val catalog = libs
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            extensions.configure(KotlinMultiplatformExtension::class.java) {
                (this as ExtensionAware).extensions.configure(
                    "android",
                    Action<KotlinMultiplatformAndroidLibraryTarget> {
                        compileSdk = catalog.intVersion("android-compileSdk")
                        minSdk = catalog.intVersion("android-minSdk")
                        withHostTestBuilder {}
                        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
                    },
                )
                iosArm64()
                iosSimulatorArm64()
                sourceSets.all {
                    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
                    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }

            dependencies.add("commonTestImplementation", catalog.library("kotlin-test"))
            dependencies.add("commonTestImplementation", catalog.library("kotlinx-coroutines-test"))
        }
    }
}
