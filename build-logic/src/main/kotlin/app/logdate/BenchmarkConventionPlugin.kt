package app.logdate

import com.android.build.api.dsl.TestExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Shared setup for the macrobenchmark and baseline-profile modules, applied as
 * `id("app.logdate.benchmark")`.
 *
 * Applies `com.android.test` with the catalog's `compileSdk` and `minSdk`, JVM 17, the AndroidX test
 * runner, and a self-instrumenting, non-debuggable `benchmark` build type signed with the debug key.
 * Emulator errors are suppressed because benchmarks run on Gradle Managed Devices.
 *
 * A module still states its namespace, the app it measures (`targetProjectPath`), a different
 * `minSdk` when its app needs one, its managed devices and its dependencies.
 */
class BenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.android.test")
            extensions.configure(TestExtension::class.java) {
                compileSdk = libs.intVersion("android-compileSdk")
                defaultConfig {
                    minSdk = libs.intVersion("android-minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
                }
                experimentalProperties["android.experimental.self-instrumenting"] = true
                buildTypes.create("benchmark") {
                    isDebuggable = false
                    signingConfig = buildTypes.getByName("debug").signingConfig
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}
