import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.app.logdate.atproto.published.module)
    alias(libs.plugins.app.logdate.lexicon.models)
}

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
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoRepo)
            api(projects.shared.atprotoSyntax)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
        }
    }
}

// Test fixtures: this module's own schemas under src/commonTest/lexicons. The build generates their
// models before compiling the tests, which round-trip them on every platform, so a schema or
// generator change reaches the tests with nothing to run by hand. This module is the generator, so
// it runs its own JVM build.
lexiconModels {
    schemas.from(layout.projectDirectory.dir("src/commonTest/lexicons"))
    basePackage = "studio.hypertext.atproto.lexicon.fixtures"
    namespaceLayout = false
    sourceSet = "commonTest"
    generator.from(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}
