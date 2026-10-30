package app.logdate

import org.gradle.api.Plugin
import org.gradle.api.Project

class HomeWorkspacePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val parser =
            project.configurations.create("homeWorkspacePsiCompiler") {
                isCanBeConsumed = false
            }
        project.dependencies.add(parser.name, kotlinCompilerEmbeddable(project))
        project.tasks.register("checkHomeWorkspaceContract", CheckHomeWorkspaceContract::class.java) {
            parserClasspath.from(parser)
            group = "verification"
            description = "Checks migrated Home roots against shared framing ownership."
            scopeManifest.set(project.layout.projectDirectory.file("config/workspace/home-roots.txt"))
            allowlist.set(project.layout.projectDirectory.file("config/workspace/home-allowlist.txt"))
            sources.from(
                project
                    .file("config/workspace/home-roots.txt")
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith('#') }
                    .map { project.file(it.substringBefore('|')) },
            )
        }
    }
}
