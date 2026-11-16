pluginManagement {
    repositories {
        // Included builds do not inherit the root's plugin repositories.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "atproto-lexicon-gradle-plugin"
