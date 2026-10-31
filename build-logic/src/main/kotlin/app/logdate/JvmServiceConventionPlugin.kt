package app.logdate

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Shared setup for the JVM-only server and its end-to-end tests, applied as
 * `id("app.logdate.jvm-service")`.
 *
 * Applies Kotlin JVM and compiles Kotlin and Java for JVM 17, the runtime of the server's container
 * image.
 */
class JvmServiceConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure(KotlinJvmProjectExtension::class.java) {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            extensions.configure(JavaPluginExtension::class.java) {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
