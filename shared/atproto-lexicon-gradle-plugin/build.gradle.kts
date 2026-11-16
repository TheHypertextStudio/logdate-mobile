plugins {
    `kotlin-dsl`
    `maven-publish`
    signing
}

description = "Gradle plugin that generates Kotlin models from AT Protocol lexicon schemas."

// This build is included by the repository's root build, which does not pass its gradle.properties
// down, so the shared default version is read from that file. The precedence matches
// AtprotoPublishedModulePlugin: ATPROTO_VERSION, then atproto.version, then the default.
val repositoryProperties =
    providers
        .fileContents(layout.projectDirectory.file("../../gradle.properties"))
        .asText
        .map { text ->
            text
                .lineSequence()
                .filter { '=' in it && !it.trimStart().startsWith("#") }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        }.orElse(emptyMap())

fun setting(
    propertyName: String,
    envName: String,
): String? = providers.gradleProperty(propertyName).orNull ?: providers.environmentVariable(envName).orNull

group = setting("atproto.group", "ATPROTO_GROUP") ?: "studio.hypertext.atproto"
version =
    providers.environmentVariable("ATPROTO_VERSION").orNull
        ?: providers.gradleProperty("atproto.version").orNull
        ?: repositoryProperties.get()["atproto.version"]
        ?: "0.1.0"

dependencies {
    // Every project that applies this plugin also applies a Kotlin Gradle plugin, which is where
    // these classes come from at run time.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")

    testImplementation(kotlin("test"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
}

// The plugin resolves the generator at its own version, so the version is built into the jar.
val pluginCoordinates =
    tasks.register<WriteProperties>("pluginCoordinates") {
        destinationFile.set(
            layout.buildDirectory.file("generated/plugin-coordinates/studio/hypertext/atproto/lexicon/gradle/plugin.properties"),
        )
        property("group", project.group.toString())
        property("version", project.version.toString())
    }

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/plugin-coordinates")).builtBy(pluginCoordinates))
}

tasks.withType<Jar>().matching { it.archiveClassifier.getOrElse("").isEmpty() }.configureEach {
    from(layout.projectDirectory.dir("../atproto-licensing")) {
        include("LICENSE", "NOTICE")
        into("META-INF")
    }
}

gradlePlugin {
    website.set("https://github.com/TheHypertextStudio/logdate-mobile")
    vcsUrl.set("https://github.com/TheHypertextStudio/logdate-mobile.git")
    plugins {
        register("lexiconModels") {
            id = "studio.hypertext.atproto.lexicon"
            implementationClass = "studio.hypertext.atproto.lexicon.gradle.LexiconModelsPlugin"
            displayName = "AT Protocol lexicon models"
            description = project.description
        }
    }
}

publishing {
    repositories {
        // The same two targets the atproto-* modules publish to, under the same names, so one
        // release run can address every artifact alike.
        val publishUrl = setting("atproto.publish.url", "ATPROTO_PUBLISH_URL")
        if (publishUrl != null) {
            maven {
                name = "atproto"
                url = uri(publishUrl)
                val publishUsername = setting("atproto.publish.username", "ATPROTO_PUBLISH_USERNAME")
                val publishPassword = setting("atproto.publish.password", "ATPROTO_PUBLISH_PASSWORD")
                if (publishUsername != null && publishPassword != null) {
                    credentials {
                        username = publishUsername
                        password = publishPassword
                    }
                }
            }
        }
        val centralUsername = setting("ossrh.username", "OSSRH_USERNAME")
        val centralPassword = setting("ossrh.password", "OSSRH_PASSWORD")
        if (centralUsername != null && centralPassword != null) {
            maven {
                name = "atprotoMavenCentral"
                url =
                    uri(
                        setting("ossrh.publish.url", "OSSRH_PUBLISH_URL")
                            ?: "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/",
                    )
                credentials {
                    username = centralUsername
                    password = centralPassword
                }
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("atproto-lexicon-gradle-plugin")
            description.set(project.description)
            url.set("https://github.com/TheHypertextStudio/logdate-mobile")
            inceptionYear.set("2026")
            organization {
                name.set("The Hypertext Studio")
                url.set("https://thehypertext.studio")
            }
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thehypertextstudio")
                    name.set("The Hypertext Studio")
                    url.set("https://thehypertext.studio")
                }
            }
            scm {
                url.set("https://github.com/TheHypertextStudio/logdate-mobile")
                connection.set("scm:git:https://github.com/TheHypertextStudio/logdate-mobile.git")
                developerConnection.set("scm:git:ssh://git@github.com/TheHypertextStudio/logdate-mobile.git")
            }
            issueManagement {
                system.set("GitHub")
                url.set("https://github.com/TheHypertextStudio/logdate-mobile/issues")
            }
        }
    }
}

// Signing is opt-in, as it is for the atproto-* modules: local and unsigned publication work
// without a key.
val signingKey = setting("signingKey", "SIGNING_KEY")
val signingPassword = setting("signingPassword", "SIGNING_PASSWORD")
if (signingKey != null && signingPassword != null) {
    signing {
        useInMemoryPgpKeys(setting("signingKeyId", "SIGNING_KEY_ID"), signingKey, signingPassword)
        sign(publishing.publications)
    }
}
