package app.logdate

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Applies `app.logdate.dead-code-gate` to the root of a real build: a Kotlin module with an unused
 * local must fail to compile, and an Android application must lint its libraries for unused
 * resources.
 */
class DeadCodeGatePluginTest {
    private val project: File = createTempDirectory("dead-code-gate").toFile()

    init {
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "dead-code-gate-test"
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            include(":service", ":app")
            """.trimIndent(),
        )
        project.resolve("gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\n")
        project.resolve("build.gradle.kts").writeText("""plugins { id("app.logdate.dead-code-gate") }""")
        listOf("service", "app").forEach { project.resolve(it).mkdirs() }
        project.resolve("service/build.gradle.kts").writeText("""plugins { id("org.jetbrains.kotlin.jvm") }""")
        project.resolve("service/src/main/kotlin/Greeting.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                fun greeting(name: String): String {
                    val forgotten = name.length
                    return "Hello, ${'$'}name"
                }
                """.trimIndent(),
            )
        }
        project.resolve("app/build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }

            android {
                namespace = "app.logdate.test"
                compileSdk = 36
            }

            tasks.register("describeLint") {
                val lines =
                    listOf(
                        "checkDependencies=" + android.lint.checkDependencies,
                        "errors=" + android.lint.error.sorted(),
                    )
                doLast { lines.forEach(::println) }
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(project)
            .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    @Test
    fun `an unused local fails the compilation that declares it when compiler errors are on`() {
        val output = runner(":service:compileKotlin", "-Plogdate.deadCode.compilerErrors=true").buildAndFail().output

        assertContains(output, Regex("""e: \S+/Greeting\.kt:2:9 Variable is unused\."""))
    }

    @Test
    fun `an unused local is reported as a warning by default`() {
        val output = runner(":service:compileKotlin").build().output

        assertContains(output, Regex("""w: \S+/Greeting\.kt:2:9 Variable is unused\."""))
    }

    @Test
    fun `an application lints its libraries and fails on unused resources and ids`() {
        val output = runner(":app:describeLint").build().output

        assertContains(output, "checkDependencies=true")
        assertContains(output, "errors=[UnusedIds, UnusedResources]")
    }
}
