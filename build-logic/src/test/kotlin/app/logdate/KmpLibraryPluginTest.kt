package app.logdate

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Applies `app.logdate.kmp-library` and `app.logdate.kmp-base` in real builds and reads back the
 * evaluated Kotlin and Android configuration, so a module that uses a convention gets the same setup
 * it used to write out.
 */
class KmpLibraryPluginTest {
    @Test
    fun `a library gets the shared targets, its Android target and the compiler settings`() {
        val output =
            describe(
                plugin = "app.logdate.kmp-library",
                kotlinBlock =
                    """
                    android { namespace = "app.logdate.test" }
                    jvm("desktop")
                    """.trimIndent(),
                extraLines =
                    """
                    "compileSdk=" + android.compileSdk,
                    "minSdk=" + android.minSdk,
                    "androidJvmTarget=" + android.compilerOptions.jvmTarget.get(),
                    """.trimIndent(),
                androidTarget = true,
            )

        assertContains(output, "targets=[android, desktop, iosArm64, iosSimulatorArm64, metadata]")
        assertContains(output, "compileSdk=36")
        assertContains(output, "minSdk=29")
        assertContains(output, "androidJvmTarget=JVM_17")
        assertContains(output, "freeCompilerArgs=[-Xexpect-actual-classes]")
        assertContains(output, "optIns=[kotlin.time.ExperimentalTime, kotlin.uuid.ExperimentalUuidApi]")
        assertContains(output, "serialization=true")
        assertContains(output, "desktopTestHeadless=true")
    }

    @Test
    fun `a library without Android gets the shared targets and compiler settings only`() {
        val output = describe(plugin = "app.logdate.kmp-base", kotlinBlock = "jvm()")

        assertContains(output, "targets=[iosArm64, iosSimulatorArm64, jvm, metadata]")
        assertContains(output, "android=false")
        assertContains(output, "freeCompilerArgs=[-Xexpect-actual-classes]")
        assertContains(output, "optIns=[kotlin.time.ExperimentalTime, kotlin.uuid.ExperimentalUuidApi]")
        assertContains(output, "serialization=true")
    }

    private fun describe(
        plugin: String,
        kotlinBlock: String,
        extraLines: String = "",
        androidTarget: Boolean = false,
    ): String {
        val project = createTempDirectory("kmp-library").toFile()
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "kmp-library-test"
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("android-compileSdk", "36")
                        version("android-minSdk", "29")
                        library("kotlin-test", "org.jetbrains.kotlin:kotlin-test:2.3.21")
                        library("kotlinx-coroutines-test", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                    }
                }
            }
            """.trimIndent(),
        )
        val androidLookup =
            if (androidTarget) {
                "val android = kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).single()"
            } else {
                ""
            }
        project.resolve("build.gradle.kts").writeText(
            """
            import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

            plugins {
                id("$plugin")
            }

            kotlin {
            ${kotlinBlock.prependIndent("    ")}
            }

            tasks.register("describe") {
                $androidLookup
                val lines =
                    listOf(
                        "targets=" + kotlin.targets.names.sorted(),
                        "android=" + pluginManager.hasPlugin("com.android.kotlin.multiplatform.library"),
                        "serialization=" + pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.serialization"),
                        "freeCompilerArgs=" + kotlin.compilerOptions.freeCompilerArgs.get(),
                        "optIns=" + kotlin.sourceSets.getByName("commonMain").languageSettings.optInAnnotationsInUse.sorted(),
                        "desktopTestHeadless=" + tasks.withType<Test>().findByName("desktopTest")?.systemProperties?.get("java.awt.headless"),
            ${extraLines.prependIndent("            ")}
                    )
                doLast { lines.forEach(::println) }
            }
            """.trimIndent(),
        )
        return GradleRunner
            .create()
            .withProjectDir(project)
            .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
            .withPluginClasspath()
            .withArguments("describe", "--stacktrace")
            .build()
            .output
    }
}
