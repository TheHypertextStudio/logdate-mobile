package app.logdate

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `downloadSpeechModels` in a real build: a build with unchanged models skips the download, and
 * a clean build restores the assets from the build cache instead of downloading them again.
 */
class DownloadSpeechModelTaskTest {
    private val project: File = createTempDirectory("speech-model-task").toFile()
    private val asset: File = project.resolve("build/generated/speech-assets/vad.zip")

    init {
        val model = project.resolve("models/vad.onnx").apply { parentFile.mkdirs() }
        model.writeText("weights")
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "speech-model-test"
            buildCache { local { directory = file("build-cache") } }
            """.trimIndent(),
        )
        project.resolve("build.gradle.kts").writeText(
            """
            plugins { id("app.logdate.speech-model") }

            speechModels {
                models.set(listOf(app.logdate.SpeechModel("${model.toURI()}", "vad.zip", "${sha256(model)}")))
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `unchanged models are not downloaded again and a clean build restores them from the cache`() {
        assertEquals(TaskOutcome.SUCCESS, run().outcome())
        assertTrue(asset.isFile)

        assertEquals(TaskOutcome.UP_TO_DATE, run().outcome())

        project.resolve("build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, run().outcome())
        assertTrue(asset.isFile)
    }

    private fun run(): BuildResult =
        GradleRunner
            .create()
            .withProjectDir(project)
            .withTestKitDir(File(System.getProperty("logdate.testKitDir")))
            .withPluginClasspath()
            .withArguments("downloadSpeechModels", "--build-cache", "--stacktrace")
            .build()

    private fun BuildResult.outcome(): TaskOutcome? = task(":downloadSpeechModels")?.outcome

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
