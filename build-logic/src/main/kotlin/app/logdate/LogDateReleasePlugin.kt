package app.logdate

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Release identity, versioning and signing shared by LogDate's Android applications.
 *
 * The phone and the watch ship on one Play listing, and the Wear OS Data Layer only carries data
 * between apps with the same package name and signing key, so the values that decide those live
 * here rather than being written out in each app's build script.
 *
 * Applies to an Android application project as `id("app.logdate.android-release")`; read the
 * resolved values through the `logdateRelease` extension. The plugin also chooses every variant's
 * signing key, following [ReleaseSigningPolicy], so an app's build script sets none.
 */
class LogDateReleasePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val release = target.extensions.create("logdateRelease", LogDateReleaseExtension::class.java, target)
        configureAndroidShortcuts(target)
        configureAdvertisingIdVerification(target)
        configureSigning(target, release)
    }
}

private fun configureSigning(
    project: Project,
    release: LogDateReleaseExtension,
) {
    project.pluginManager.withPlugin("com.android.application") {
        val android = project.extensions.getByType(ApplicationExtension::class.java)
        val upload =
            release.signing?.let { key ->
                android.signingConfigs.create("release") {
                    storeFile = project.file(key.storeFile)
                    storePassword = key.storePassword
                    keyAlias = key.keyAlias
                    keyPassword = key.keyPassword
                    // Both signature schemes, so Play accepts the upload.
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        val debug = android.signingConfigs.getByName("debug")
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
            val key = ReleaseSigningPolicy.keyFor(variant.buildType.orEmpty(), hasUploadKey = upload != null)
            variant.signingConfig.setConfig(if (key == SigningKey.UPLOAD && upload != null) upload else debug)
        }

        val verification =
            project.tasks.register("verifyReleaseSigning", VerifyReleaseSigning::class.java) {
                group = "verification"
                description = "Refuses a release package that would be signed with the debug key."
                hasUploadKey.set(upload != null)
                allowDebugReleaseSigning.set(release.allowDebugReleaseSigning)
            }
        project.tasks.named { it == "packageRelease" || it == "signReleaseBundle" }.configureEach {
            dependsOn(verification)
        }
    }
}

/** Fails when a release package would be signed with the debug key without the explicit override. */
abstract class VerifyReleaseSigning : DefaultTask() {
    @get:Input
    abstract val hasUploadKey: Property<Boolean>

    @get:Input
    abstract val allowDebugReleaseSigning: Property<Boolean>

    @TaskAction
    fun verify() {
        ReleaseSigningPolicy.releaseBlocker(hasUploadKey.get(), allowDebugReleaseSigning.get())?.let { throw GradleException(it) }
    }
}

private fun configureAdvertisingIdVerification(project: Project) {
    val bundle = project.layout.buildDirectory.file("outputs/bundle/release/${project.name}-release.aab")
    val manifests = project.layout.buildDirectory.dir("intermediates/merged_manifest/release")
    val script =
        project.rootProject.layout.projectDirectory
            .file("scripts/verify-android-advertising-id.py")
    val verification =
        project.tasks.register("verifyAdvertisingIdReleaseBundle", Exec::class.java) {
            group = "verification"
            description = "Verifies that advertising ID is disabled in the final release bundle."
            dependsOn("bundleRelease")
            inputs.file(bundle)
            inputs.dir(manifests)
            inputs.file(script)
            commandLine(
                "python3",
                script.asFile.absolutePath,
                "--bundle",
                bundle.get().asFile.absolutePath,
                "--manifest-dir",
                manifests.get().asFile.absolutePath,
            )
            if (project.name == "android-main") args("--require-firebase")
        }
    project.tasks.matching { it.name == "publishReleaseBundle" }.configureEach {
        dependsOn(verification)
    }
}

open class LogDateReleaseExtension(
    private val project: Project,
) {
    private val providers = project.providers

    /**
     * `studio.hypertext.logdate`, or the `logdate.applicationId` property when a rebuild has to run
     * under the retired `co.reasonabletech.logdate` identity that developer devices may still hold.
     */
    val applicationId: String =
        providers
            .gradleProperty("logdate.applicationId")
            .orElse("studio.hypertext.logdate")
            .get()

    /** Debug builds install beside a release build instead of replacing it. */
    val debugApplicationIdSuffix: String = ".debug"

    /**
     * From `LOGDATE_VERSION_CODE`, then the `logdate.versionCode` property, then 1 so a plain debug
     * build needs no setup. Publishing lets Play assign the real code.
     */
    val versionCode: Int = envOrProperty("LOGDATE_VERSION_CODE", "logdate.versionCode")?.toIntOrNull() ?: 1

    val versionName: String = envOrProperty("LOGDATE_VERSION_NAME", "logdate.versionName") ?: "0.1.0"

    /** The Play track to publish to: `internal` unless `LOGDATE_PLAY_TRACK` or `logdate.play.track` says otherwise. */
    val playTrack: String = envOrProperty("LOGDATE_PLAY_TRACK", "logdate.play.track") ?: "internal"

    /** The same track on the Wear OS form factor, with its registered form-factor prefix. */
    val wearPlayTrack: String get() = WearPlayTrack.forTrack(playTrack)

    /** The upload key, when this machine has all of it; see [ReleaseSigningResolver] for the sources. */
    val signing: ReleaseSigning? by lazy {
        ReleaseSigningResolver.resolve(
            environment = { name -> providers.environmentVariable(name).orNull },
            gradleProperty = { name -> providers.gradleProperty(name).orNull },
            envFile = ReleaseSigningResolver.parseEnvFile(signingEnvFileLines()),
        )
    }

    /** A build that never publishes, such as CI's shrinking check, may package a release signed with the debug key. */
    val allowDebugReleaseSigning: Boolean =
        providers
            .gradleProperty("logdate.allowDebugReleaseSigning")
            .orNull
            ?.toBoolean() == true

    /**
     * Whether this build publishes to Play. Play then assigns the version code, which means asking Play
     * during the build, so plain builds skip that and need no Play credentials.
     */
    val playPublishRequested: Boolean =
        project.gradle.startParameter.taskNames
            .any { it.contains("publish", ignoreCase = true) || it.contains("promote", ignoreCase = true) }

    private fun envOrProperty(
        variable: String,
        property: String,
    ): String? = providers.environmentVariable(variable).orElse(providers.gradleProperty(property)).orNull

    private fun signingEnvFileLines(): List<String> {
        val path =
            ReleaseSigningPolicy.envFilePath(
                signingDirectory = providers.environmentVariable("LOGDATE_SIGNING_DIR").orNull,
                userHome = providers.systemProperty("user.home").get(),
            )
        val file = project.objects.fileProperty().fileValue(File(path))
        return providers
            .fileContents(file)
            .asText.orNull
            ?.lines()
            .orEmpty()
    }
}
