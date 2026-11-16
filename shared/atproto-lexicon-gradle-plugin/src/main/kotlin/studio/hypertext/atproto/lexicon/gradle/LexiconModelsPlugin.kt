package studio.hypertext.atproto.lexicon.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * Generates Kotlin models from lexicon schemas on every build, into a source set of a Kotlin JVM or
 * Kotlin Multiplatform project. Nothing generated is checked in: a schema change reaches the code
 * the next time anything compiles, and an invalid schema fails the build.
 *
 * ```kotlin
 * plugins {
 *     kotlin("jvm")
 *     kotlin("plugin.serialization")
 *     id("studio.hypertext.atproto.lexicon")
 * }
 *
 * lexiconModels {
 *     schemas.from("src/main/lexicons")
 *     basePackage = "com.example.models"
 * }
 *
 * dependencies {
 *     // The generated models use this library's data types.
 *     implementation("studio.hypertext.atproto:atproto-lexicon:<version>")
 * }
 * ```
 *
 * The generator is the JVM build of `studio.hypertext.atproto:atproto-lexicon`. The plugin resolves
 * the release that matches its own version from the project's repositories. To run a different
 * build of the generator, declare it on the [GENERATOR_CONFIGURATION] configuration:
 *
 * ```kotlin
 * dependencies {
 *     lexiconGenerator(project(":shared:atproto-lexicon"))
 * }
 * ```
 */
class LexiconModelsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("lexiconModels", LexiconModelsExtension::class.java)
        extension.namespaceLayout.convention(true)

        val generator =
            project.configurations.create(GENERATOR_CONFIGURATION) {
                description = "The lexicon generator and its runtime dependencies."
                isCanBeConsumed = false
                isCanBeResolved = false
                defaultDependencies { add(project.dependencies.create(LexiconPluginCoordinates.generator)) }
            }

        val generate =
            project.tasks.register("generateLexiconModels", GenerateLexiconModels::class.java) {
                group = "codegen"
                description = "Generate Kotlin models from this project's lexicon schemas."
                schemas.from(extension.schemas)
                basePackage.set(extension.basePackage)
                namespaceLayout.set(extension.namespaceLayout)
                recordIndex.set(extension.recordIndex)
                schemaIndex.set(extension.schemaIndex)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/lexicon/kotlin"))
            }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            wireKotlin(project, extension, generator, generate, defaultSourceSet = "commonMain")
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            wireKotlin(project, extension, generator, generate, defaultSourceSet = "main")
        }
    }

    private fun wireKotlin(
        project: Project,
        extension: LexiconModelsExtension,
        generator: Configuration,
        generate: TaskProvider<GenerateLexiconModels>,
        defaultSourceSet: String,
    ) {
        extension.sourceSet.convention(defaultSourceSet)
        val classpath = generatorClasspath(project, generator)
        generate.configure { this.generator.from(classpath) }
        // The source set is read once the build script has configured the extension.
        project.afterEvaluate {
            val kotlin = project.extensions.getByName("kotlin") as KotlinProjectExtension
            kotlin.sourceSets
                .getByName(extension.sourceSet.get())
                .kotlin
                .srcDir(generate.flatMap { it.outputDirectory })
        }
    }

    /** Resolves [generator] to the JVM runtime of whatever it declares. */
    private fun generatorClasspath(
        project: Project,
        generator: Configuration,
    ) = project.configurations.create("${GENERATOR_CONFIGURATION}Classpath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        extendsFrom(generator)
        attributes {
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
        }
    }

    companion object {
        /** The configuration that holds the generator. Empty, it resolves the published generator. */
        const val GENERATOR_CONFIGURATION = "lexiconGenerator"
    }
}

/** The published generator this plugin runs by default, at the plugin's own version. */
internal object LexiconPluginCoordinates {
    private val properties =
        Properties().apply {
            val resource =
                checkNotNull(LexiconPluginCoordinates::class.java.getResourceAsStream("plugin.properties")) {
                    "The lexicon plugin was built without its coordinates"
                }
            resource.use(::load)
        }

    val generator: String = "${properties.getProperty("group")}:atproto-lexicon:${properties.getProperty("version")}"
}

/** Configures [LexiconModelsPlugin]. */
abstract class LexiconModelsExtension {
    /** Lexicon JSON files, or directories read recursively. */
    abstract val schemas: ConfigurableFileCollection

    /**
     * The package for the generated code. With [namespaceLayout], each document goes in this
     * package followed by its NSID's namespace; without it, every document goes in this package.
     */
    abstract val basePackage: Property<String>

    /** Whether to give each NSID namespace its own package. Defaults to true. */
    abstract val namespaceLayout: Property<Boolean>

    /** When set, also writes an object of this name mapping each record type to its serializer. */
    abstract val recordIndex: Property<String>

    /** When set, also writes an object of this name holding every schema's JSON, for checking records at runtime. */
    abstract val schemaIndex: Property<String>

    /**
     * The Kotlin source set the generated code joins. Defaults to `commonMain` in a Kotlin
     * Multiplatform project and `main` in a Kotlin JVM project.
     */
    abstract val sourceSet: Property<String>
}

/** Runs the lexicon generator, replacing everything it wrote before. */
@CacheableTask
abstract class GenerateLexiconModels : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemas: ConfigurableFileCollection

    @get:Classpath
    abstract val generator: ConfigurableFileCollection

    @get:Input
    abstract val basePackage: Property<String>

    @get:Input
    abstract val namespaceLayout: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val recordIndex: Property<String>

    @get:Input
    @get:Optional
    abstract val schemaIndex: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun generate() {
        check(!generator.isEmpty) {
            "No lexicon generator to run. Apply a Kotlin JVM or Kotlin Multiplatform plugin alongside studio.hypertext.atproto.lexicon."
        }
        val output = outputDirectory.get().asFile
        // Start empty, so code for a removed schema doesn't linger.
        files.delete { delete(output) }
        output.mkdirs()
        exec.javaexec {
            classpath = generator
            mainClass.set(GENERATOR_MAIN_CLASS)
            args(
                lexiconGeneratorArguments(
                    schemas = schemas.files,
                    outputDirectory = output,
                    basePackage = basePackage.get(),
                    namespaceLayout = namespaceLayout.get(),
                    recordIndex = recordIndex.orNull,
                    schemaIndex = schemaIndex.orNull,
                ),
            )
        }
    }

    private companion object {
        const val GENERATOR_MAIN_CLASS = "studio.hypertext.atproto.lexicon.GenerateLexiconsKt"
    }
}

/** The command-line arguments the generator's `main` takes. */
fun lexiconGeneratorArguments(
    schemas: Collection<File>,
    outputDirectory: File,
    basePackage: String,
    namespaceLayout: Boolean,
    recordIndex: String?,
    schemaIndex: String? = null,
): List<String> =
    buildList {
        add(schemas.sortedBy { it.path }.joinToString(File.pathSeparator) { it.absolutePath })
        add(outputDirectory.absolutePath)
        add(basePackage)
        if (namespaceLayout) add("--namespace-layout")
        recordIndex?.let {
            add("--record-index")
            add(it)
        }
        schemaIndex?.let {
            add("--schema-index")
            add(it)
        }
    }
