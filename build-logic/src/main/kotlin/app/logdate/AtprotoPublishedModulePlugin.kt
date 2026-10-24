package app.logdate

import groovy.util.Node
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension

private const val DEFAULT_ATPROTO_GROUP = "studio.hypertext.atproto"
private const val DEFAULT_ATPROTO_VERSION = "0.1.0"
private const val REPO_URL = "https://github.com/TheHypertextStudio/logdate-mobile"
private const val SCM_CONNECTION = "scm:git:$REPO_URL.git"
private const val SCM_DEVELOPER_CONNECTION = "scm:git:ssh://git@github.com/TheHypertextStudio/logdate-mobile.git"
private const val ORG_URL = "https://thehypertext.studio"

/**
 * Convention plugin for publishable `shared/atproto-*` modules.
 *
 * This plugin exists to keep the standalone ATProto library artifacts consistent across modules.
 * It centralizes the publication policy for the `studio.hypertext.atproto` artifact family instead
 * of repeating the same `maven-publish`, Dokka, and signing configuration in every module.
 *
 * Applied behavior:
 * - applies `org.jetbrains.dokka`, `maven-publish`, and `signing`
 * - sets the default artifact group to `studio.hypertext.atproto`
 * - sets the default artifact version to `0.1.0`
 * - registers a `dokkaHtmlJar` task and attaches it to every Maven publication as the `javadoc`
 *   artifact
 * - adds shared POM metadata to every publication
 * - optionally wires a remote `atproto` Maven repository
 * - optionally enables in-memory PGP signing
 *
 * Property precedence is:
 * 1. Gradle property
 * 2. environment variable
 * 3. hard-coded default, when one exists
 *
 * The version is the exception: `ATPROTO_VERSION` wins over `atproto.version`. The release
 * workflow sets `ATPROTO_VERSION` from the release tag, and `atproto.version` is always present
 * because the root `gradle.properties` defines it, so the tag could otherwise never take effect.
 *
 * Recognized overrides:
 * - `atproto.group` or `ATPROTO_GROUP`
 * - `atproto.version` or `ATPROTO_VERSION`
 * - `atproto.publish.url` or `ATPROTO_PUBLISH_URL`
 * - `atproto.publish.username` or `ATPROTO_PUBLISH_USERNAME`
 * - `atproto.publish.password` or `ATPROTO_PUBLISH_PASSWORD`
 * - `signingKeyId` or `SIGNING_KEY_ID`
 * - `signingKey` or `SIGNING_KEY`
 * - `signingPassword` or `SIGNING_PASSWORD`
 *
 * Signing is intentionally opt-in. If the key and password are not provided, the plugin still
 * supports local and unsigned remote publication.
 */
class AtprotoPublishedModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.dokka")
            pluginManager.apply("maven-publish")
            pluginManager.apply("signing")

            group = propertyOrEnv("atproto.group", "ATPROTO_GROUP") ?: DEFAULT_ATPROTO_GROUP
            version = envOrProperty("ATPROTO_VERSION", "atproto.version") ?: DEFAULT_ATPROTO_VERSION

            val dokkaHtmlJar =
                tasks.register("dokkaHtmlJar", Jar::class.java) {
                    group = "documentation"
                    description = "Assembles a jar archive containing the Dokka HTML publication."
                    archiveClassifier.set("javadoc")
                    dependsOn(tasks.named("dokkaGeneratePublicationHtml"))
                    from(layout.buildDirectory.dir("dokka/html"))
                }

            val publishing = extensions.getByType(PublishingExtension::class.java)
            configureRepository(publishing)
            configurePublications(publishing, dokkaHtmlJar)
            embedLicensingInJars()

            configureSigning()
        }
    }

    /**
     * Copies the Apache 2.0 LICENSE and NOTICE into the binary jar's META-INF.
     *
     * Scoped to publishable binary jars only — `archiveClassifier == ""` skips sources, javadoc,
     * dokka html, and per-target klib bundles. Apache 2.0 only mandates the notice in the
     * binary distribution; embedding it in sources/docs jars is noise and breaks build-cache
     * hits on otherwise-deterministic auxiliary artifacts.
     */
    private fun Project.embedLicensingInJars() {
        val licensingDir = rootProject.layout.projectDirectory.dir("shared/atproto-licensing")
        tasks
            .withType(Jar::class.java)
            .matching { it.archiveClassifier.getOrElse("").isEmpty() }
            .configureEach {
                from(licensingDir.file("LICENSE")) { into("META-INF") }
                from(licensingDir.file("NOTICE")) { into("META-INF") }
            }
    }

    /**
     * Wires the optional `atproto` (legacy / GH Packages) and `atprotoMavenCentral` (Sonatype)
     * repositories. Each is gated on its own credential set; both are no-ops without one.
     */
    private fun Project.configureRepository(publishing: PublishingExtension) {
        registerNamedRepository(
            publishing = publishing,
            name = "atproto",
            url = propertyOrEnv("atproto.publish.url", "ATPROTO_PUBLISH_URL"),
            username = propertyOrEnv("atproto.publish.username", "ATPROTO_PUBLISH_USERNAME"),
            password = propertyOrEnv("atproto.publish.password", "ATPROTO_PUBLISH_PASSWORD"),
            requireCredentials = false,
        )
        registerNamedRepository(
            publishing = publishing,
            name = "atprotoMavenCentral",
            url =
                propertyOrEnv("ossrh.publish.url", "OSSRH_PUBLISH_URL")
                    ?: "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/",
            username = propertyOrEnv("ossrh.username", "OSSRH_USERNAME"),
            password = propertyOrEnv("ossrh.password", "OSSRH_PASSWORD"),
            requireCredentials = true,
        )
    }

    private fun Project.registerNamedRepository(
        publishing: PublishingExtension,
        name: String,
        url: String?,
        username: String?,
        password: String?,
        requireCredentials: Boolean,
    ) {
        if (url == null) return
        if (requireCredentials && (username == null || password == null)) return
        publishing.repositories.maven(
            object : Action<MavenArtifactRepository> {
                override fun execute(repository: MavenArtifactRepository) {
                    repository.name = name
                    repository.url = uri(url)
                    if (username != null && password != null) {
                        repository.credentials(
                            PasswordCredentials::class.java,
                            object : Action<PasswordCredentials> {
                                override fun execute(credentials: PasswordCredentials) {
                                    credentials.username = username
                                    credentials.password = password
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    /**
     * Applies shared publication metadata and documentation artifacts to every Maven publication.
     *
     * Kotlin Multiplatform creates multiple publications per module. This method keeps the root
     * multiplatform, JVM, Android, and native publications aligned on the same metadata and
     * attached Dokka HTML jar.
     */
    private fun Project.configurePublications(
        publishing: PublishingExtension,
        dokkaHtmlJar: org.gradle.api.tasks.TaskProvider<Jar>,
    ) {
        publishing.publications.withType(MavenPublication::class.java).configureEach(
            object : Action<MavenPublication> {
                override fun execute(publication: MavenPublication) {
                    publication.artifact(dokkaHtmlJar)
                    publication.pom.name.set(project.name)
                    publication.pom.description.set(project.description ?: "Kotlin Multiplatform AT Protocol module.")
                    publication.pom.url.set(REPO_URL)
                    publication.pom.inceptionYear.set("2026")
                    publication.pom.withXml(
                        object : Action<XmlProvider> {
                            override fun execute(xml: XmlProvider) {
                                val root = xml.asNode()
                                root.appendNodeIfMissing("organization").apply {
                                    appendNodeIfMissing("name").setValue("The Hypertext Studio")
                                    appendNodeIfMissing("url").setValue(ORG_URL)
                                }
                                root.appendNodeIfMissing("licenses").appendNodeIfMissing("license").apply {
                                    appendNodeIfMissing("name").setValue("Apache-2.0")
                                    appendNodeIfMissing("url").setValue("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                }
                                root.appendNodeIfMissing("developers").appendNodeIfMissing("developer").apply {
                                    appendNodeIfMissing("id").setValue("thehypertextstudio")
                                    appendNodeIfMissing("name").setValue("The Hypertext Studio")
                                    appendNodeIfMissing("url").setValue(ORG_URL)
                                }
                                root.appendNodeIfMissing("scm").apply {
                                    appendNodeIfMissing("url").setValue(REPO_URL)
                                    appendNodeIfMissing("connection").setValue(SCM_CONNECTION)
                                    appendNodeIfMissing("developerConnection").setValue(SCM_DEVELOPER_CONNECTION)
                                }
                                root.appendNodeIfMissing("issueManagement").apply {
                                    appendNodeIfMissing("system").setValue("GitHub")
                                    appendNodeIfMissing("url").setValue("$REPO_URL/issues")
                                }
                            }
                        },
                    )
                }
            },
        )
    }

    /**
     * Enables in-memory PGP signing only when a usable signing key and password are configured.
     *
     * This keeps local developer flows frictionless while still supporting signed remote releases.
     * The key ID is optional because Gradle can sign successfully with just the armored key and
     * password.
     */
    private fun Project.configureSigning() {
        val signingKeyId = propertyOrEnv("signingKeyId", "SIGNING_KEY_ID")
        val signingKey = propertyOrEnv("signingKey", "SIGNING_KEY")
        val signingPassword = propertyOrEnv("signingPassword", "SIGNING_PASSWORD")

        if (signingKey != null && signingPassword != null) {
            val signing = extensions.getByType(SigningExtension::class.java)
            signing.useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
            val publishing = extensions.getByType(PublishingExtension::class.java)
            signing.sign(publishing.publications)
        }
    }

    /**
     * Returns the configured Gradle property when present, otherwise the paired environment
     * variable.
     */
    private fun Project.propertyOrEnv(
        propertyName: String,
        envName: String,
    ): String? = providers.gradleProperty(propertyName).orNull ?: providers.environmentVariable(envName).orNull

    private fun Project.envOrProperty(
        envName: String,
        propertyName: String,
    ): String? = providers.environmentVariable(envName).orNull ?: providers.gradleProperty(propertyName).orNull

    /**
     * Appends a child XML node only when it does not already exist.
     *
     * The POM customization hooks may be invoked repeatedly by Gradle during publication
     * configuration. This helper keeps the generated XML stable and avoids duplicate nodes.
     */
    private fun Node.appendNodeIfMissing(name: String): Node = (get(name) as? List<*>)?.firstOrNull() as? Node ?: appendNode(name)
}
