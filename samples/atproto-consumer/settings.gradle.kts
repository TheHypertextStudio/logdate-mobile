pluginManagement {
    // Inside this repository the lexicon plugin is built from its source, so the sample configures
    // before anything has been published. A project outside the repository resolves the same
    // plugin id from a Maven repository instead; see the README.
    includeBuild("../../shared/atproto-lexicon-gradle-plugin")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "atproto-consumer"
