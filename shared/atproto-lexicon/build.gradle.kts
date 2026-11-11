@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("app.logdate.atproto-published-module")
    alias(libs.plugins.app.logdate.lexicon.models)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

val logDateLexiconInputDir = layout.projectDirectory.dir("src/commonMain/resources/studio/hypertext/logdate")
val logDateLexiconOutputDir = layout.projectDirectory.dir("src/commonMain/kotlin/studio/hypertext/atproto/lexicon/generated/logdate")
val atprotoIdentityLexiconInputDir = layout.projectDirectory.dir("src/commonMain/resources/com/atproto/identity")
val atprotoIdentityLexiconOutputDir =
    layout.projectDirectory.dir("src/commonMain/kotlin/studio/hypertext/atproto/lexicon/generated/com/atproto/identity")
val atprotoServerLexiconInputDir = layout.projectDirectory.dir("src/commonMain/resources/com/atproto/server")
val atprotoServerLexiconOutputDir =
    layout.projectDirectory.dir("src/commonMain/kotlin/studio/hypertext/atproto/lexicon/generated/com/atproto/server")
val atprotoRepoLexiconInputDir = layout.projectDirectory.dir("src/commonMain/resources/com/atproto/repo")
val atprotoRepoLexiconOutputDir =
    layout.projectDirectory.dir("src/commonMain/kotlin/studio/hypertext/atproto/lexicon/generated/com/atproto/repo")
val atprotoSyncLexiconInputDir = layout.projectDirectory.dir("src/commonMain/resources/com/atproto/sync")
val atprotoSyncLexiconOutputDir =
    layout.projectDirectory.dir("src/commonMain/kotlin/studio/hypertext/atproto/lexicon/generated/com/atproto/sync")

// The official AT Protocol models are checked in, because this module generates them itself and
// can't build its own main sources first. Regenerate them with generateOfficialAtprotoLexicons; a
// JVM test fails when they no longer match the generator.
fun registerLexiconCodegenTask(
    name: String,
    inputDir: org.gradle.api.file.Directory,
    outputDir: org.gradle.api.file.Directory,
    packageName: String,
) = tasks.register<JavaExec>(name) {
    group = "codegen"
    description = "Generate checked-in Kotlin models for ${inputDir.asFile.relativeTo(projectDir).path}."
    inputs.dir(inputDir)
    outputs.dir(outputDir)

    dependsOn(tasks.named("jvmJar"))

    val jvmJar = tasks.named<Jar>("jvmJar")
    classpath(jvmJar.map { it.archiveFile.get().asFile }, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("studio.hypertext.atproto.lexicon.GenerateLexiconsKt")
    args(inputDir.asFile.absolutePath, outputDir.asFile.absolutePath, packageName)
}

registerLexiconCodegenTask(
    name = "generateLogDateLexicons",
    inputDir = logDateLexiconInputDir,
    outputDir = logDateLexiconOutputDir,
    packageName = "studio.hypertext.atproto.lexicon.generated.logdate",
)

registerLexiconCodegenTask(
    name = "generateAtprotoIdentityLexicons",
    inputDir = atprotoIdentityLexiconInputDir,
    outputDir = atprotoIdentityLexiconOutputDir,
    packageName = "studio.hypertext.atproto.lexicon.generated.com.atproto.identity",
)

registerLexiconCodegenTask(
    name = "generateAtprotoServerLexicons",
    inputDir = atprotoServerLexiconInputDir,
    outputDir = atprotoServerLexiconOutputDir,
    packageName = "studio.hypertext.atproto.lexicon.generated.com.atproto.server",
)

registerLexiconCodegenTask(
    name = "generateAtprotoRepoLexicons",
    inputDir = atprotoRepoLexiconInputDir,
    outputDir = atprotoRepoLexiconOutputDir,
    packageName = "studio.hypertext.atproto.lexicon.generated.com.atproto.repo",
)

registerLexiconCodegenTask(
    name = "generateAtprotoSyncLexicons",
    inputDir = atprotoSyncLexiconInputDir,
    outputDir = atprotoSyncLexiconOutputDir,
    packageName = "studio.hypertext.atproto.lexicon.generated.com.atproto.sync",
)

tasks.register("generateOfficialAtprotoLexicons") {
    group = "codegen"
    description = "Generate checked-in Kotlin models for the official AT Protocol lexicon documents used by this repo."
    dependsOn(
        "generateAtprotoIdentityLexicons",
        "generateAtprotoServerLexicons",
        "generateAtprotoRepoLexicons",
        "generateAtprotoSyncLexicons",
    )
}

description = "Kotlin Multiplatform AT Protocol lexicon parsing and code generation."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.lexicon"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTestBuilder {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoRepo)
            api(projects.shared.atprotoSyntax)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Test fixtures: real LogDate and community schemas plus one synthetic schema for the kinds LogDate
// doesn't use yet. The build generates them before compiling the tests, which round-trip them on
// every platform, so a schema or generator change reaches the tests with nothing to run by hand.
// This module is the generator, so it runs its own JVM build.
lexiconModels {
    val repositoryLexicons = rootProject.layout.projectDirectory.dir("lexicons")
    schemas.from(
        repositoryLexicons.file("app/logdate/defs.json"),
        repositoryLexicons.file("app/logdate/journal/entry.json"),
        repositoryLexicons.file("app/logdate/crypto/sealed.json"),
        repositoryLexicons.file("community/lexicon/location/geo.json"),
        layout.projectDirectory.file("src/commonTest/lexicons/kitchenSink.json"),
    )
    basePackage = "studio.hypertext.atproto.lexicon.fixtures"
    namespaceLayout = false
    sourceSet = "commonTest"
    generator.from(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}
