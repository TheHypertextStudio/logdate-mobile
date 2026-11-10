package app.logdate

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for Compose Multiplatform feature modules.
 *
 * Builds on [KmpLibraryPlugin], applies the Compose plugins, and supplies the dependencies that
 * every feature module uses (Compose basics, coroutines, datetime, Koin, Napier).
 */
class ComposeFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(KmpLibraryPlugin::class.java)
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val catalog = libsCatalog()
            listOf(
                "compose-runtime",
                "compose-foundation",
                "compose-material3",
                "compose-components-resources",
                "kotlinx-coroutines-core",
                "kotlinx-datetime",
                "koin-core",
                "koin-compose",
                "koin-compose-viewmodel",
                "napier",
            ).forEach { dependencies.add("commonMainImplementation", catalog.library(it)) }
            dependencies.add(
                "commonMainImplementation",
                dependencies.platform(catalog.library("koin-bom")),
            )
        }
    }
}
