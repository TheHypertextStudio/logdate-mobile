plugins {
    id("app.logdate.atproto-published-module")
    id("app.logdate.kmp-library")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
}

description = "Kotlin Multiplatform AT Protocol repository record primitives."

kotlin {
    explicitApi()

    android {
        namespace = "studio.hypertext.atproto.repo"
        withHostTestBuilder {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.atprotoIdentity)
            api(projects.shared.atprotoSyntax)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.serialization.json)
            api(libs.okio)
            implementation(libs.napier)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmTest.dependencies {
            implementation(projects.shared.atprotoCrypto)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The JVM tests hand exported repositories to the AT Protocol reference implementation
// (@atproto/repo, pinned in tools/atproto-repo-verify) so they are checked by the same code
// relays run. Node.js 22 or newer must be on the PATH; pnpm is optional (`./run setup` checks both).
val referenceVerifierDir: Directory = rootProject.layout.projectDirectory.dir("tools/atproto-repo-verify")

val installReferenceRepoVerifier by tasks.registering(Exec::class) {
    description = "Installs the AT Protocol reference implementation that verifies exported repositories."
    group = "verification"
    commandLine(referenceVerifierDir.file("install.sh").asFile.path)
    inputs.file(referenceVerifierDir.file("install.sh")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(referenceVerifierDir.file("package.json")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(referenceVerifierDir.file("pnpm-lock.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(referenceVerifierDir.dir("node_modules"))
}

/**
 * Passes the verifier script's location to the tests. The script and the lockfile are inputs by
 * their path relative to the project, so the test task's cache key is the same in every checkout.
 */
class ReferenceVerifierArguments(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val script: File,
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val lockfile: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Datproto.referenceVerifier=${script.absolutePath}")
}

tasks.named<Test>("jvmTest") {
    dependsOn(installReferenceRepoVerifier)
    jvmArgumentProviders +=
        ReferenceVerifierArguments(
            script = referenceVerifierDir.file("verify.mjs").asFile,
            lockfile = referenceVerifierDir.file("pnpm-lock.yaml").asFile,
        )
}
