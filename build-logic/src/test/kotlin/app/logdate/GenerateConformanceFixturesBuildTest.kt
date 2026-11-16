package app.logdate

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Runs [GenerateConformanceFixtures] in a real Gradle build: it must skip work when nothing it
 * reads changed, rerun when a selected fixture changes, ignore files that aren't selected, and
 * work with the configuration cache.
 */
class GenerateConformanceFixturesBuildTest {
    private val project: File = createTempDirectory("conformance-fixtures").toFile()
    private val fixtures: File = project.resolve("fixtures")
    private val generated: File = project.resolve("build/generated/fixtures/Fixtures.kt")

    init {
        fixtures.mkdirs()
        fixtures.resolve("padding.json").writeText("""{"a": 1}""")
        project.resolve("settings.gradle.kts").writeText("""rootProject.name = "fixtures-test"""")
        project.resolve("build.gradle.kts").writeText(buildScript())
    }

    @Test
    fun `an unchanged build skips the task and a changed fixture reruns it`() {
        assertEquals(TaskOutcome.SUCCESS, run().outcome())
        assertContains(generated.readText(), "\"padding.json\" to \"{\\\"a\\\": 1}\"")

        assertEquals(TaskOutcome.UP_TO_DATE, run().outcome())

        fixtures.resolve("padding.json").writeText("""{"a": 2}""")
        assertEquals(TaskOutcome.SUCCESS, run().outcome())
        assertContains(generated.readText(), "{\\\"a\\\": 2}")
    }

    @Test
    fun `a file that is not a selected fixture changes nothing`() {
        run()
        fixtures.resolve("notes.md").writeText("Not a fixture")
        fixtures.resolve("reference").mkdirs()
        fixtures.resolve("reference/verify.py").writeText("print('not a fixture')")

        assertEquals(TaskOutcome.UP_TO_DATE, run().outcome())
        assertFalse(generated.readText().contains("notes.md"))
    }

    @Test
    fun `a new fixture is embedded and a removed one is dropped`() {
        run()
        fixtures.resolve("manifest.json").writeText("{}")
        assertEquals(TaskOutcome.SUCCESS, run().outcome())
        assertContains(generated.readText(), "\"manifest.json\" to \"{}\"")

        fixtures.resolve("manifest.json").delete()
        assertEquals(TaskOutcome.SUCCESS, run().outcome())
        assertFalse(generated.readText().contains("manifest.json"))
    }

    @Test
    fun `the task works with the configuration cache`() {
        val first = run("--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, first.outcome())
        assertContains(first.output, "Configuration cache entry stored")

        val second = run("--configuration-cache")
        assertEquals(TaskOutcome.UP_TO_DATE, second.outcome())
        assertContains(second.output, "Reusing configuration cache")
    }

    private fun run(vararg arguments: String): BuildResult =
        GradleRunner
            .create()
            .withProjectDir(project)
            .withArguments("embed", "--stacktrace", *arguments)
            .build()

    private fun BuildResult.outcome(): TaskOutcome? = task(":embed")?.outcome

    private fun buildScript(): String {
        val taskClasses =
            File(
                GenerateConformanceFixtures::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        return """
            buildscript {
                dependencies { classpath(files("${taskClasses.invariantSeparatorsPath}")) }
            }

            tasks.register<app.logdate.GenerateConformanceFixtures>("embed") {
                root.set(layout.projectDirectory.dir("fixtures"))
                fixtures.from(fileTree(layout.projectDirectory.dir("fixtures")) { include("*.json") })
                packageName.set("fixtures")
                objectName.set("Fixtures")
                outputDirectory.set(layout.buildDirectory.dir("generated"))
            }
            """.trimIndent()
    }
}
