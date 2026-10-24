plugins {
    `java-platform`
    `maven-publish`
    signing
}

description = "Bill-of-materials (BOM) for the studio.hypertext.atproto Kotlin Multiplatform library."

// Mirror the convention plugin's group/version sourcing so the BOM stays
// aligned with the modules it pins, even when overridden by Gradle property
// or env var (release pipeline use case).
group =
    providers.gradleProperty("atproto.group").orNull
        ?: providers.environmentVariable("ATPROTO_GROUP").orNull
        ?: "studio.hypertext.atproto"
// ATPROTO_VERSION comes first so a release tag's version wins over the default in gradle.properties,
// matching AtprotoPublishedModulePlugin.
version =
    providers.environmentVariable("ATPROTO_VERSION").orNull
        ?: providers.gradleProperty("atproto.version").orNull
        ?: "0.1.0"

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("studio.hypertext.atproto:atproto-syntax:${project.version}")
        api("studio.hypertext.atproto:atproto-crypto:${project.version}")
        api("studio.hypertext.atproto:atproto-lexicon:${project.version}")
        api("studio.hypertext.atproto:atproto-identity:${project.version}")
        api("studio.hypertext.atproto:atproto-xrpc:${project.version}")
        api("studio.hypertext.atproto:atproto-plc:${project.version}")
        api("studio.hypertext.atproto:atproto-repo:${project.version}")
        api("studio.hypertext.atproto:atproto-pds:${project.version}")
        api("studio.hypertext.atproto:atproto-pds-runtime:${project.version}")
        api("studio.hypertext.atproto:atproto-oauth-client:${project.version}")
    }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["javaPlatform"])
            pom {
                name.set("atproto-bom")
                description.set(
                    "Bill-of-materials for studio.hypertext.atproto. Add this with " +
                        "`platform(\"studio.hypertext.atproto:atproto-bom\")` and reference " +
                        "any atproto-* module without an explicit version.",
                )
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
    repositories {
        // Mirror AtprotoPublishedModulePlugin's two-repo setup (legacy / GH Packages and
        // atprotoMavenCentral / Sonatype) so the BOM publishes to the same targets as the
        // KMP modules. The convention plugin can't be applied here directly because this is
        // a `java-platform` project and the plugin's KMP-publication assumptions wouldn't fit.
        val legacyUrl =
            providers.gradleProperty("atproto.publish.url").orNull
                ?: providers.environmentVariable("ATPROTO_PUBLISH_URL").orNull
        if (legacyUrl != null) {
            maven {
                name = "atproto"
                url = uri(legacyUrl)
                val user =
                    providers.gradleProperty("atproto.publish.username").orNull
                        ?: providers.environmentVariable("ATPROTO_PUBLISH_USERNAME").orNull
                val pass =
                    providers.gradleProperty("atproto.publish.password").orNull
                        ?: providers.environmentVariable("ATPROTO_PUBLISH_PASSWORD").orNull
                if (user != null && pass != null) {
                    credentials {
                        username = user
                        password = pass
                    }
                }
            }
        }
        val ossrhUser =
            providers.gradleProperty("ossrh.username").orNull
                ?: providers.environmentVariable("OSSRH_USERNAME").orNull
        val ossrhPass =
            providers.gradleProperty("ossrh.password").orNull
                ?: providers.environmentVariable("OSSRH_PASSWORD").orNull
        if (ossrhUser != null && ossrhPass != null) {
            maven {
                name = "atprotoMavenCentral"
                url =
                    uri(
                        providers.gradleProperty("ossrh.publish.url").orNull
                            ?: providers.environmentVariable("OSSRH_PUBLISH_URL").orNull
                            ?: "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/",
                    )
                credentials {
                    username = ossrhUser
                    password = ossrhPass
                }
            }
        }
    }
}

// Optional in-memory PGP signing — gated on env so local builds without GPG
// don't fail. Mirrors AtprotoPublishedModulePlugin's behaviour.
val signingKey =
    providers.gradleProperty("signingKey").orNull
        ?: providers.environmentVariable("SIGNING_KEY").orNull
val signingPassword =
    providers.gradleProperty("signingPassword").orNull
        ?: providers.environmentVariable("SIGNING_PASSWORD").orNull
if (signingKey != null && signingPassword != null) {
    signing {
        useInMemoryPgpKeys(
            providers.gradleProperty("signingKeyId").orNull
                ?: providers.environmentVariable("SIGNING_KEY_ID").orNull,
            signingKey,
            signingPassword,
        )
        sign(publishing.publications)
    }
}
