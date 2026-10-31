package app.logdate

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
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
abstract class CheckSettingsUiContract
    @Inject
    constructor(
        private val workers: WorkerExecutor,
    ) : DefaultTask() {
        @get:Classpath abstract val parserClasspath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        /** Written once every source passes, so a build whose sources are unchanged skips the check. */
        @get:OutputFile abstract val result: RegularFileProperty

        @TaskAction
        fun check() {
            workers.classLoaderIsolation { classpath.from(parserClasspath) }.submit(SettingsUiContractWork::class.java) {
                sources.from(this@CheckSettingsUiContract.sources)
                result.set(this@CheckSettingsUiContract.result)
            }
        }
    }

interface SettingsUiContractParameters : WorkParameters {
    val sources: ConfigurableFileCollection
    val result: RegularFileProperty
}

abstract class SettingsUiContractWork : WorkAction<SettingsUiContractParameters> {
    override fun execute() {
        val failures =
            parameters.sources.files
                .filter { file ->
                    val source = file.readText()
                    listOf("Settings", "ListItem", "Switch", "RadioButton").any { it in source }
                }.flatMap { file ->
                    SettingsUiContract.inspect(file.readText(), file.name).map { line ->
                        "${file.path}:$line: Use shared settings rows, supporting typography and direct section children; see docs/reference/standards/settings-ui.md"
                    }
                }
        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n"))
        parameters.result
            .get()
            .asFile
            .writeText("${parameters.sources.files.size} sources follow the settings UI contract.\n")
    }
}
