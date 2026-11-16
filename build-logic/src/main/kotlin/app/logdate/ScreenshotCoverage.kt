package app.logdate

import java.io.File

/**
 * Checks that every Android OS integration feature has the screenshots its registry entry promises.
 *
 * The registry (`config/screenshots/os-integration-coverage.txt`) has one requirement per line,
 * written `feature|lane|target`:
 *
 * - `android`: `path/to/File.kt#Function`, a Compose preview under the phone screenshot sources. It
 *   must use `@OsIntegrationPreviewMatrix` and have a reference for every configuration in that
 *   matrix (plus `@BubbleWindowPreview` when applied).
 * - `desktop`: a shared scene id. It must have references for the standard and accessibility
 *   viewports in the desktop lane.
 * - `system`: a repository path to a real system UI capture (PNG) or text evidence (TXT).
 * - `glance`: a repository path to a Glance widget reference (PNG).
 *
 * The checks are plain functions over files so they can be unit-tested without Gradle.
 */
object ScreenshotCoverage {
    /** Where the screenshot sources and references live. */
    data class Roots(
        val repoRoot: File,
        val androidSources: File,
        val androidReferences: File,
        val desktopReferences: File,
    )

    /** One registry line. */
    sealed interface Requirement {
        val feature: String

        data class AndroidPreview(
            override val feature: String,
            val sourcePath: String,
            val function: String,
        ) : Requirement

        data class DesktopScene(
            override val feature: String,
            val sceneId: String,
        ) : Requirement

        data class SystemCapture(
            override val feature: String,
            val path: String,
        ) : Requirement

        data class GlanceReference(
            override val feature: String,
            val path: String,
        ) : Requirement
    }

    /** Desktop viewports every OS integration scene must render in. */
    val requiredDesktopSuffixes = listOf("phone", "phone-dark", "compact-phone", "phone-rtl", "phone-large-text")

    private const val MATRIX = "OsIntegrationPreviewMatrix"
    private const val BUBBLE = "BubbleWindowPreview"
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val functionDeclaration = Regex("""\bfun\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*\(""")

    fun parseRegistry(lines: List<String>): List<Requirement> =
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { line ->
                val fields = line.split('|').map { it.trim() }
                require(fields.size == 3 && fields.none { it.isEmpty() }) {
                    "Screenshot registry lines must be feature|lane|target: $line"
                }
                val (feature, lane, target) = fields
                when (lane) {
                    "android" -> {
                        val sourcePath = target.substringBefore('#', missingDelimiterValue = "")
                        val function = target.substringAfter('#', missingDelimiterValue = "")
                        require(sourcePath.isNotEmpty() && function.isNotEmpty()) {
                            "Android screenshot requirements must name File.kt#Function: $line"
                        }
                        Requirement.AndroidPreview(feature, sourcePath, function)
                    }

                    "desktop" -> {
                        Requirement.DesktopScene(feature, target)
                    }

                    "system" -> {
                        Requirement.SystemCapture(feature, target)
                    }

                    "glance" -> {
                        Requirement.GlanceReference(feature, target)
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown screenshot lane '$lane': $line")
                    }
                }
            }

    /** The `@Preview(name = ...)` names declared on the multipreview [annotation] in [matrixSource]. */
    fun previewNames(
        matrixSource: String,
        annotation: String,
    ): List<String> {
        val declaration = Regex("""annotation\s+class\s+$annotation\b""").find(matrixSource) ?: return emptyList()
        val previous =
            Regex("""annotation\s+class\s+\w+""")
                .findAll(matrixSource)
                .lastOrNull { it.range.last < declaration.range.first }
        val start = previous?.range?.last?.plus(1) ?: 0
        return Regex("""name\s*=\s*"([^"]+)"""")
            .findAll(matrixSource.substring(start, declaration.range.first))
            .map { it.groupValues[1] }
            .toList()
    }

    /** Every unmet requirement, as a sentence naming the feature and what is missing. */
    fun verify(
        requirements: List<Requirement>,
        roots: Roots,
        matrixSource: String,
    ): List<String> =
        requirements.flatMap { requirement ->
            when (requirement) {
                is Requirement.AndroidPreview -> verifyAndroidPreview(requirement, roots, matrixSource)
                is Requirement.DesktopScene -> verifyDesktopScene(requirement, roots)
                is Requirement.SystemCapture -> verifySystemCapture(requirement, roots)
                is Requirement.GlanceReference -> verifyPng(requirement.feature, roots.repoRoot.resolve(requirement.path), requirement.path)
            }
        }

    /** Reference images whose test file or preview function no longer exists. */
    fun staleAndroidReferences(roots: Roots): List<String> =
        roots.androidReferences
            .walkTopDown()
            .filter { it.isDirectory }
            .flatMap { directory ->
                val references = directory.listFiles { file -> file.extension == "png" }.orEmpty()
                if (references.isEmpty()) return@flatMap emptySequence()
                val relativeDirectory = directory.relativeTo(roots.androidReferences).invariantSeparatorsPath
                val functions = sourceFor(relativeDirectory, roots)?.let(::declaredFunctions).orEmpty()
                references
                    .filter { reference -> functions.none { reference.name.startsWith("${it}_") } }
                    .map { "$relativeDirectory/${it.name}" }
                    .asSequence()
            }.sorted()
            .toList()

    /** Previews using the OS integration matrix under [packagePath] that the registry does not list. */
    fun unregisteredOsIntegrationPreviews(
        requirements: List<Requirement>,
        roots: Roots,
        packagePath: String,
    ): List<String> {
        val registered =
            requirements
                .filterIsInstance<Requirement.AndroidPreview>()
                .map { "${it.sourcePath}#${it.function}" }
                .toSet()
        return roots.androidSources
            .resolve(packagePath)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                val path = file.relativeTo(roots.androidSources).invariantSeparatorsPath
                functionDeclaration
                    .findAll(source)
                    .filter { "@$MATRIX" in annotationsBefore(source, it.range.first) }
                    .map { "$path#${it.groupValues[1]}" }
            }.filterNot { it in registered }
            .sorted()
            .toList()
    }

    private fun verifyAndroidPreview(
        requirement: Requirement.AndroidPreview,
        roots: Roots,
        matrixSource: String,
    ): List<String> {
        val label = "${requirement.feature}: ${requirement.sourcePath}#${requirement.function}"
        val sourceFile = roots.androidSources.resolve(requirement.sourcePath)
        if (!sourceFile.isFile) return listOf("$label is registered, but the source file does not exist")
        val source = sourceFile.readText()
        val declaration =
            functionDeclaration.findAll(source).firstOrNull { it.groupValues[1] == requirement.function }
                ?: return listOf("$label is registered, but the function does not exist")
        val annotations = annotationsBefore(source, declaration.range.first)
        if ("@$MATRIX" !in annotations) return listOf("$label must use @$MATRIX")

        val required =
            previewNames(matrixSource, MATRIX) + if ("@$BUBBLE" in annotations) previewNames(matrixSource, BUBBLE) else emptyList()
        val referenceDirectory =
            roots.androidReferences
                .resolve(requirement.sourcePath.substringBeforeLast('/', missingDelimiterValue = ""))
                .resolve("${sourceFile.nameWithoutExtension}Kt")
        val references = referenceDirectory.listFiles().orEmpty().map { it.name }
        return required
            .filter { preview -> references.none { it.startsWith("${requirement.function}_${preview}_") && it.endsWith(".png") } }
            .map { preview -> "$label has no '$preview' reference; record it with updateDebugScreenshotTest" }
    }

    private fun verifyDesktopScene(
        requirement: Requirement.DesktopScene,
        roots: Roots,
    ): List<String> =
        requiredDesktopSuffixes
            .filterNot { suffix -> roots.desktopReferences.resolve("${requirement.sceneId}--$suffix.png").isFile }
            .map { suffix -> "${requirement.feature}: desktop scene ${requirement.sceneId} has no '$suffix' reference" }

    private fun verifySystemCapture(
        requirement: Requirement.SystemCapture,
        roots: Roots,
    ): List<String> {
        val file = roots.repoRoot.resolve(requirement.path)
        return when (file.extension) {
            "png" -> {
                verifyPng(requirement.feature, file, requirement.path)
            }

            "txt" -> {
                if (file.isFile && file.readText().isNotBlank()) {
                    emptyList()
                } else {
                    listOf("${requirement.feature}: text evidence ${requirement.path} is missing or empty")
                }
            }

            else -> {
                listOf("${requirement.feature}: system capture ${requirement.path} must be a .png image or .txt evidence")
            }
        }
    }

    private fun verifyPng(
        feature: String,
        file: File,
        path: String,
    ): List<String> =
        when {
            !file.isFile -> listOf("$feature: $path does not exist")

            !file
                .readBytes()
                .take(
                    pngSignature.size,
                ).toByteArray()
                .contentEquals(pngSignature) -> listOf("$feature: $path is not a PNG image")

            else -> emptyList()
        }

    private fun sourceFor(
        referenceDirectory: String,
        roots: Roots,
    ): File? {
        val fileClass = referenceDirectory.substringAfterLast('/')
        if (!fileClass.endsWith("Kt")) return null
        val packagePath = referenceDirectory.substringBeforeLast('/', missingDelimiterValue = "")
        return roots.androidSources
            .resolve(packagePath)
            .resolve("${fileClass.removeSuffix("Kt")}.kt")
            .takeIf { it.isFile }
    }

    private fun declaredFunctions(file: File): Set<String> =
        functionDeclaration
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** The annotations and KDoc between the previous declaration and the function at [index]. */
    private fun annotationsBefore(
        source: String,
        index: Int,
    ): String {
        val previousEnd = maxOf(source.lastIndexOf("\n}", index), source.lastIndexOf("*/", index), 0)
        return source.substring(previousEnd, index)
    }
}
