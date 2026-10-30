package app.logdate

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider

/** The `libs` catalog from `gradle/libs.versions.toml`, which every LogDate build script reads. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("gradle/libs.versions.toml has no version named $alias") }.requiredVersion

/** Reads an integer version entry such as `android-compileSdk` from the catalog. */
internal fun VersionCatalog.intVersion(alias: String): Int = version(alias).toInt()

/** Resolves a library provider from the catalog by its alias. */
internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("gradle/libs.versions.toml has no library named $alias") }

/**
 * The Kotlin compiler at the catalog's Kotlin version, for checks that parse sources with its PSI, so
 * they read Kotlin the way the build compiles it.
 */
internal fun kotlinCompilerEmbeddable(project: Project): String =
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${project.libs.version("kotlin")}"
