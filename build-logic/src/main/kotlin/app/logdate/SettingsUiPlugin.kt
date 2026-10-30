package app.logdate

import org.gradle.api.Plugin
import org.gradle.api.Project

class SettingsUiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val parser = project.configurations.create("settingsUiPsiCompiler") { isCanBeConsumed = false }
        project.dependencies.add(parser.name, kotlinCompilerEmbeddable(project))
        val check =
            project.tasks.register("checkSettingsUiContract", CheckSettingsUiContract::class.java) {
                group = "verification"
                description = "Checks shared settings rows and archive option sheets."
                parserClasspath.from(parser)
                sources.from(
                    project.rootProject.fileTree("client") {
                        include("**/src/commonMain/**/*.kt")
                        exclude("**/build/**")
                    },
                )
            }
        project.tasks.matching { it.name == "check" || it.name == "ktlintCheck" }.configureEach { dependsOn(check) }
    }
}
