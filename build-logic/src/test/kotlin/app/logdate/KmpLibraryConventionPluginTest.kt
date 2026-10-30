package app.logdate

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Applies `app.logdate.kmp-library` in a real build and reads back the evaluated Kotlin and Android
 * configuration, so a module that uses the convention gets the same setup it used to write out.
 */
class KmpLibraryConventionPluginTest {
    private val project: File = createTempDirectory("kmp-library").toFile()

    init {
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "kmp-library-test"
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    create("libs") {
                        version("android-compileSdk", "36")
                        version("android-minSdk", "29")
                    }
                }
            }
            """.trimIndent(),
        )
        project.resolve("build.gradle.kts").writeText(
            """
            import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

            plugins {
                id("app.logdate.kmp-library")
                id("com.android.kotlin.multiplatform.library")
            }

            kotlin {
                android { namespace = "app.logdate.test" }
                jvm("desktop")
            }

            tasks.register("describe") {
                val android = kotlin.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).single()
                val lines =
                    listOf(
                        "targets=" + kotlin.targets.names.sorted(),
                        "compileSdk=" + android.compileSdk,
                        "minSdk=" + android.minSdk,
                        "androidJvmTarget=" + android.compilerOptions.jvmTarget.get(),
                        "freeCompilerArgs=" + kotlin.compilerOptions.freeCompilerArgs.get(),
                        "optIns=" + kotlin.sourceSets.getByName("commonMain").languageSettings.optInAnnotationsInUse.sorted(),
                        "desktopTestHeadless=" + tasks.withType<Test>().getByName("desktopTest").systemProperties["java.awt.headless"],
                    )
                doLast { lines.forEach(::println) }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a library gets the shared targets, SDK levels and compiler settings`() {
        val output =
            GradleRunner
                .create()
                .withProjectDir(project)
                .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
                .withPluginClasspath()
                .withArguments("describe", "--stacktrace")
                .build()
                .output

        assertContains(output, "targets=[android, desktop, iosArm64, iosSimulatorArm64, metadata]")
        assertContains(output, "compileSdk=36")
        assertContains(output, "minSdk=29")
        assertContains(output, "androidJvmTarget=JVM_17")
        assertContains(output, "freeCompilerArgs=[-Xexpect-actual-classes]")
        assertContains(output, "optIns=[kotlin.time.ExperimentalTime, kotlin.uuid.ExperimentalUuidApi]")
        assertContains(output, "desktopTestHeadless=true")
    }
}
