package app.logdate

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Writes one comparison sheet per OS integration feature in the coverage registry, or only the
 * feature named by `-Pfeature=<name>`, to `build/reports/screenshot-sheets/<feature>.png`.
 */
abstract class BuildScreenshotComparisonSheet : DefaultTask() {
    @get:Internal abstract val registry: RegularFileProperty

    @get:Internal abstract val repoRoot: DirectoryProperty

    @get:Internal abstract val androidReferences: DirectoryProperty

    @get:Internal abstract val desktopReferences: DirectoryProperty

    @get:Internal abstract val outputDirectory: DirectoryProperty

    @get:Input @get:Optional
    abstract val feature: Property<String>

    init {
        doNotTrackState("Reads reference images named in the registry, which can change outside Gradle")
    }

    @TaskAction fun build() {
        val roots =
            ScreenshotCoverage.Roots(
                repoRoot = repoRoot.get().asFile,
                androidSources = repoRoot.get().asFile,
                androidReferences = androidReferences.get().asFile,
                desktopReferences = desktopReferences.get().asFile,
            )
        val byFeature = ScreenshotCoverage.parseRegistry(registry.get().asFile.readLines()).groupBy { it.feature }
        val selected =
            feature.orNull?.let { name ->
                mapOf(
                    name to (
                        byFeature[name] ?: throw GradleException(
                            "No feature '$name' in the registry. Known: ${byFeature.keys.sorted()}",
                        )
                    ),
                )
            }
                ?: byFeature

        selected.forEach { (name, requirements) ->
            val tiles = ScreenshotComparisonSheet.tilesFor(requirements, roots)
            if (tiles.isEmpty()) throw GradleException("Feature '$name' has no images to compare")
            val output = outputDirectory.get().file("$name.png").asFile
            ScreenshotComparisonSheet.render(title = name, tiles = tiles, output = output)
            logger.lifecycle("Comparison sheet for $name (${tiles.size} images): $output")
        }
    }
}
