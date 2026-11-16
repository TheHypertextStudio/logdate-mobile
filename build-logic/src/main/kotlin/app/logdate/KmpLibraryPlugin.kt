package app.logdate

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A LogDate Kotlin Multiplatform library with an Android target, applied as `app.logdate.kmp-library`.
 *
 * Builds on [KmpBasePlugin] and applies the Android KMP library plugin. The Android target takes
 * `compileSdk` and `minSdk` from the version catalog, compiles for JVM 17, the bytecode level of the
 * Android apps, and runs host tests.
 *
 * A module still declares what is particular to it: its Android namespace, its JVM target
 * (`jvm()` or `jvm("desktop")`), any Wasm target, any device tests, and its dependencies.
 */
class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply(KmpBasePlugin::class.java)
            pluginManager.apply("com.android.kotlin.multiplatform.library")

            val catalog = libs
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
            }
        }
    }
}
