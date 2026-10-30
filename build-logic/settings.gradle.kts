pluginManagement {
    repositories {
        // Included builds do not inherit the root's plugin repositories.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        // The build's own catalog, so build-logic compiles against the plugin versions it runs with.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
