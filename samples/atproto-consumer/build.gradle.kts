plugins {
    kotlin("jvm") version "2.3.21"
    application
}

// Pulls atproto.version from the root gradle.properties via the Gradle property
// provider. The earlier eager `Properties().apply { file(...).inputStream() }`
// block crashed the configuration cache because it was filesystem I/O at config
// time. The provider chain is configuration-cache safe.
val atprotoVersion = providers.gradleProperty("atproto.version").orElse("0.1.0")

repositories {
    // mavenLocal first so `./gradlew :shared:atproto-bom:publishToMavenLocal …`
    // output is consumed without round-tripping Sonatype.
    mavenLocal()
    mavenCentral()
    // GitHub Packages release target. See docs/reference/atproto-library.md.
    // Only registered when credentials are present so an unauth'd dev doesn't
    // get a 401 on every resolve.
    val gprUser =
        providers
            .gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
            .orNull
    val gprToken =
        providers
            .gradleProperty("gpr.token")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
            .orNull
    if (gprUser != null && gprToken != null) {
        maven {
            name = "atproto-github-packages"
            url = uri("https://maven.pkg.github.com/TheHypertextStudio/logdate-mobile")
            credentials {
                username = gprUser
                password = gprToken
            }
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    implementation("io.ktor:ktor-client-mock:3.4.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    // BOM pins the atproto-* versions, so the implementation lines below
    // stay version-less. Bumping atproto.version updates them all together.
    implementation(platform("studio.hypertext.atproto:atproto-bom:${atprotoVersion.get()}"))
    implementation("studio.hypertext.atproto:atproto-syntax")
    implementation("studio.hypertext.atproto:atproto-identity")
    implementation("studio.hypertext.atproto:atproto-repo")
    implementation("studio.hypertext.atproto:atproto-xrpc")
    implementation("studio.hypertext.atproto:atproto-pds")
}

application {
    mainClass.set("studio.hypertext.atproto.sample.MainKt")
}
