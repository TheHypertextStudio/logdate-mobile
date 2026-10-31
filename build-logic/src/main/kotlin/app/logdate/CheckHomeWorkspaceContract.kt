package app.logdate

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class CheckHomeWorkspaceContract
    @Inject
    constructor(
        private val workers: WorkerExecutor,
    ) : DefaultTask() {
        @get:Classpath abstract val parserClasspath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val scopeManifest: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val allowlist: RegularFileProperty

        /** Written once every root passes, so a build whose inputs are unchanged skips the check. */
        @get:OutputFile abstract val result: RegularFileProperty

        @TaskAction fun check() {
            workers.classLoaderIsolation { classpath.from(parserClasspath) }.submit(HomeWorkspaceContractWork::class.java) {
                sources.from(this@CheckHomeWorkspaceContract.sources)
                scopeManifest.set(this@CheckHomeWorkspaceContract.scopeManifest)
                allowlist.set(this@CheckHomeWorkspaceContract.allowlist)
                result.set(this@CheckHomeWorkspaceContract.result)
            }
        }
    }

interface HomeWorkspaceContractParameters : WorkParameters {
    val sources: ConfigurableFileCollection
    val scopeManifest: RegularFileProperty
    val allowlist: RegularFileProperty
    val result: RegularFileProperty
}

abstract class HomeWorkspaceContractWork : WorkAction<HomeWorkspaceContractParameters> {
    override fun execute() {
        val sources = parameters.sources
        val scopeManifest = parameters.scopeManifest
        val allowlist = parameters.allowlist

        val scopes =
            scopeManifest
                .get()
                .asFile
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .associate { line ->
                    val (path, functions) = line.split('|')
                    path to functions.split(',').toSet()
                }
        val exceptions =
            allowlist
                .get()
                .asFile
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .map { entry ->
                    val fields = entry.split('|')
                    if (fields.size != 4 || fields.any { it.isBlank() || '*' in it }) {
                        throw GradleException("Workspace exceptions must name one path, function, rule, and reviewed reason")
                    }
                    if (fields[1] !in scopes[fields[0]].orEmpty()) {
                        throw GradleException("Workspace exception names an unscoped function: ${fields[1]}")
                    }
                    fields.take(3).joinToString("|")
                }.toSet()
        val failures =
            sources.files.flatMap { file ->
                val relative =
                    scopes.keys.singleOrNull { file.path.replace('\\', '/').endsWith(it) }
                        ?: throw GradleException("Unscoped workspace root: $file")
                HomeWorkspaceContract.inspect(file.readText(), scopes.getValue(relative)).mapNotNull { finding ->
                    val (function, rule, line) = finding.split('|')
                    if ("$relative|$function|$rule" in exceptions) null else "$relative:$line $function violates $rule"
                }
            }
        if (failures.isNotEmpty()) throw GradleException("Home workspace contract failed:\n${failures.joinToString("\n")}")
        parameters.result
            .get()
            .asFile
            .writeText("${sources.files.size} Home roots follow the workspace contract.\n")
    }
}
