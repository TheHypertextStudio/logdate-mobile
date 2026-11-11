import java.util.Properties

plugins {
    alias(libs.plugins.app.logdate.kmp.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.logdate.client.intelligence"
        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            // Project dependencies
            implementation(projects.shared.model)
            implementation(projects.client.networking)
            implementation(projects.client.repository)
            implementation(projects.client.util)
            implementation(projects.client.text)
            // External dependencies
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.components.resources)
            // Koin
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)

            implementation(libs.napier)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.koin.test)
        }
    }
}

val configProperties = PropertiesLoader.loadProperties(project)

// TODO(build): Extract this to a shared build module
object PropertiesLoader {
    fun loadProperties(project: Project): Properties {
        val properties = Properties()
        val propertiesFile = project.rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            properties.load(propertiesFile.inputStream())
        }
        return properties
    }
}

fun getConfigValue(key: String): String = "\"${configProperties.getProperty(key)}\""
