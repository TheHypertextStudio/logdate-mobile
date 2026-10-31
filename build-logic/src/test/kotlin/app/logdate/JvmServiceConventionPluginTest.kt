package app.logdate

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

/** Applies `app.logdate.jvm-service` in a real build and reads back its Kotlin and Java targets. */
class JvmServiceConventionPluginTest {
    private val project: File = createTempDirectory("jvm-service").toFile()

    init {
        project.resolve("settings.gradle.kts").writeText("""rootProject.name = "jvm-service-test"""")
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("app.logdate.jvm-service") }

            tasks.register("describe") {
                val lines =
                    listOf(
                        "kotlinJvmTarget=" + kotlin.compilerOptions.jvmTarget.get(),
                        "javaSource=" + java.sourceCompatibility,
                        "javaTarget=" + java.targetCompatibility,
                    )
                doLast { lines.forEach(::println) }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a service compiles Kotlin and Java for JVM 17`() {
        val output =
            GradleRunner
                .create()
                .withProjectDir(project)
                .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
                .withPluginClasspath()
                .withArguments("describe", "--stacktrace")
                .build()
                .output

        assertContains(output, "kotlinJvmTarget=JVM_17")
        assertContains(output, "javaSource=17")
        assertContains(output, "javaTarget=17")
    }
}
