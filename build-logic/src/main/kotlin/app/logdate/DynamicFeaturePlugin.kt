package app.logdate

import com.android.build.api.dsl.DynamicFeatureExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Convention plugin for dynamic feature modules delivered via Google Play Feature Delivery.
 *
 * Applies `com.android.dynamic-feature` and configures standard compile SDK, min SDK,
 * and Java 17 to match the rest of the project. AGP 9+ provides built-in Kotlin support
 * so no separate Kotlin plugin is needed.
 */
class DynamicFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.dynamic-feature")

            extensions.configure(DynamicFeatureExtension::class.java) {
                compileSdk = libs.intVersion("android-compileSdk")

                defaultConfig {
                    minSdk = libs.intVersion("android-minSdk")
                }

                buildTypes {
                    maybeCreate("benchmark").apply {
                        initWith(getByName("release"))
                        matchingFallbacks += listOf("release")
                    }
                    maybeCreate("dogfood").apply {
                        initWith(getByName("debug"))
                        matchingFallbacks += listOf("debug")
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }
}
