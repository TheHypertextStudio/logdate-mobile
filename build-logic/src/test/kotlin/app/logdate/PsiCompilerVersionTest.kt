package app.logdate

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * The Home workspace and settings UI contract checks parse Kotlin with the compiler's PSI. They must
 * load the compiler version the catalog names, so the checks read sources with the same Kotlin the
 * build compiles them with.
 */
class PsiCompilerVersionTest {
    private val project: File = createTempDirectory("psi-compiler").toFile()

    init {
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "psi-compiler-test"
            dependencyResolutionManagement {
                versionCatalogs { create("libs") { version("kotlin", "9.8.7") } }
            }
            """.trimIndent(),
        )
        project.resolve("config/workspace").mkdirs()
        project.resolve("config/workspace/home-roots.txt").writeText("")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("app.logdate.home-workspace")
                id("app.logdate.settings-ui")
            }

            tasks.register("printParsers") {
                val parsers =
                    listOf("homeWorkspacePsiCompiler", "settingsUiPsiCompiler").map { name ->
                        name + "=" + configurations.getByName(name).dependencies.joinToString { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                    }
                doLast { parsers.forEach(::println) }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `both contract checks parse with the catalog's Kotlin compiler`() {
        val output =
            GradleRunner
                .create()
                .withProjectDir(project)
                .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
                .withPluginClasspath()
                .withArguments("printParsers", "--stacktrace")
                .build()
                .output

        assertContains(output, "homeWorkspacePsiCompiler=org.jetbrains.kotlin:kotlin-compiler-embeddable:9.8.7")
        assertContains(output, "settingsUiPsiCompiler=org.jetbrains.kotlin:kotlin-compiler-embeddable:9.8.7")
    }
}
