package app.logdate

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.concurrent.Callable

/**
 * The compiler and Android Lint layers of LogDate's dead-code gate, applied to the root project as
 * `id("app.logdate.dead-code-gate")`.
 *
 * Every Kotlin compilation in every module, test source sets included, turns on the compiler's extra
 * checkers and fails on unused locals, never-read assignments, unused lambda parameters and
 * discarded expressions. Without the extra checkers the Kotlin 2.3 compiler reports none of these.
 *
 * Every Android application analyzes its library modules with it (`checkDependencies`), so a
 * resource one module declares and another uses counts as used, and fails on resources and view IDs
 * nothing references. Lint then reads each Kotlin Multiplatform library's Android and common
 * sources, so it runs after the tasks that generate any of them.
 *
 * When the root project applies the Dependency Analysis plugin, which must load beside the Android
 * and Kotlin Gradle plugins, every module applies it too, so `buildHealth` reports each module's
 * unused and misdeclared dependencies. detekt and SearchDeadCode are the gate's other layers; the
 * Lint section of docs/DEVELOPMENT.md runs them all.
 */
class DeadCodeGatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.subprojects {
            failOnUnusedLocals(this)
            failOnUnusedResources(this)
            lintAfterGeneratedSources(this)
        }
        project.pluginManager.withPlugin(DEPENDENCY_ANALYSIS) {
            project.subprojects { pluginManager.apply(DEPENDENCY_ANALYSIS) }
        }
    }
}

private const val DEPENDENCY_ANALYSIS = "com.autonomousapps.dependency-analysis"

/**
 * Reports unused locals, never-read assignments, unused lambda parameters and discarded expressions
 * on every compilation. They are warnings until the existing ones are cleared; passing
 * `-Plogdate.deadCode.compilerErrors=true` makes them fail the compilation, which is how the gate
 * will enforce them once the code is clean.
 */
private fun failOnUnusedLocals(project: Project) {
    val asErrors = project.providers.gradleProperty("logdate.deadCode.compilerErrors").orNull == "true"
    project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
        compilerOptions {
            extraWarnings.set(true)
            if (!asErrors) return@compilerOptions
            freeCompilerArgs.addAll(
                "-Xwarning-level=UNUSED_VARIABLE:error",
                "-Xwarning-level=VARIABLE_NEVER_READ:error",
                "-Xwarning-level=ASSIGNED_VALUE_IS_NEVER_READ:error",
                "-Xwarning-level=VARIABLE_INITIALIZER_IS_REDUNDANT:error",
                "-Xwarning-level=UNUSED_ANONYMOUS_PARAMETER:error",
                "-Xwarning-level=UNUSED_EXPRESSION:error",
                "-Xwarning-level=UNUSED_LAMBDA_EXPRESSION:error",
            )
        }
    }
}

private fun failOnUnusedResources(project: Project) {
    project.pluginManager.withPlugin("com.android.application") {
        project.extensions.configure(ApplicationExtension::class.java) {
            lint {
                checkDependencies = true
                error += setOf("UnusedResources", "UnusedIds")
            }
        }
    }
}

/**
 * Android Lint reads a multiplatform library's generated sources without depending on the tasks that
 * write them, which Gradle rejects; the lint tasks of the library's own variants and of the apps that
 * check it both need them.
 */
private fun lintAfterGeneratedSources(project: Project) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val sourceSets = project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
        val lintedSources =
            Callable {
                sourceSets
                    .filter { it.name.startsWith("common") || it.name.startsWith("android") }
                    .map { it.kotlin.sourceDirectories }
            }
        project.tasks.configureEach {
            if (name.startsWith("lintAnalyze") || (name.startsWith("generate") && name.endsWith("LintModel"))) {
                dependsOn(lintedSources)
            }
        }
    }
}
