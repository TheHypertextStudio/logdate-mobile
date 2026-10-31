package app.logdate

/** Keystore material for signing a release build. */
data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/** The keystore a build variant is signed with. */
enum class SigningKey {
    /** The Play upload key, which passkey sign-in's Digital Asset Links trust. */
    UPLOAD,

    /** The per-machine debug key. */
    DEBUG,
}

/**
 * Which key each build type signs with, and when a release package must not be built.
 *
 * Release and dogfood builds sign with the upload key when the machine has it. Dogfood falls back
 * to the debug key without complaint, because only passkey sign-in depends on its key. A release
 * package without the upload key is refused unless the build explicitly allows debug signing, so a
 * release is never quietly signed with the debug key. Every other build type, including the
 * benchmark and baseline-profile builds, signs with the debug key.
 */
object ReleaseSigningPolicy {
    private val uploadKeyBuildTypes = setOf("release", "dogfood")

    fun keyFor(
        buildType: String,
        hasUploadKey: Boolean,
    ): SigningKey = if (hasUploadKey && buildType in uploadKeyBuildTypes) SigningKey.UPLOAD else SigningKey.DEBUG

    /** Why a release package must not be built, or null when it may be. */
    fun releaseBlocker(
        hasUploadKey: Boolean,
        allowDebugReleaseSigning: Boolean,
    ): String? {
        if (hasUploadKey || allowDebugReleaseSigning) return null
        return "Release signing is not configured. Set LOGDATE_RELEASE_STORE_FILE, " +
            "LOGDATE_RELEASE_STORE_PASSWORD, LOGDATE_RELEASE_KEY_ALIAS, and " +
            "LOGDATE_RELEASE_KEY_PASSWORD, or explicitly pass " +
            "-Plogdate.allowDebugReleaseSigning=true for a local non-publishing build."
    }

    /**
     * The env file `scripts/create-signing-keystore.sh` writes next to the keystore it creates:
     * `$LOGDATE_SIGNING_DIR/production-upload.env`, by default under `~/.logdate-signing`.
     */
    fun envFilePath(
        signingDirectory: String?,
        userHome: String,
    ): String = "${signingDirectory ?: "$userHome/.logdate-signing"}/production-upload.env"
}

/**
 * Resolves release signing material, checking each source in turn:
 *
 *  1. Environment variables, which CI sets: `LOGDATE_RELEASE_STORE_FILE`, `LOGDATE_RELEASE_STORE_PASSWORD`,
 *     `LOGDATE_RELEASE_KEY_ALIAS`, `LOGDATE_RELEASE_KEY_PASSWORD`
 *  2. Gradle properties named `logdate.release.storeFile`, `logdate.release.storePassword` and so on
 *  3. The env file `scripts/create-signing-keystore.sh` writes next to the keystore it creates, which
 *     a machine that has already run that script needs nothing more for
 *
 * A value found in an earlier source wins for that value. The result is null unless all four resolve
 * to something non-blank, so a half-configured machine never signs with a partial key.
 */
object ReleaseSigningResolver {
    private val environmentVariables =
        mapOf(
            "storeFile" to "LOGDATE_RELEASE_STORE_FILE",
            "storePassword" to "LOGDATE_RELEASE_STORE_PASSWORD",
            "keyAlias" to "LOGDATE_RELEASE_KEY_ALIAS",
            "keyPassword" to "LOGDATE_RELEASE_KEY_PASSWORD",
        )

    fun resolve(
        environment: Map<String, String>,
        gradleProperty: (String) -> String?,
        envFile: Map<String, String>,
    ): ReleaseSigning? = resolve(environment::get, gradleProperty, envFile)

    /** Resolves through lookups, so a build reads only the variables and properties signing uses. */
    fun resolve(
        environment: (String) -> String?,
        gradleProperty: (String) -> String?,
        envFile: Map<String, String>,
    ): ReleaseSigning? {
        fun value(name: String): String? {
            val variable = environmentVariables.getValue(name)
            return environment(variable) ?: gradleProperty("logdate.release.$name") ?: envFile[variable]
        }

        val storeFile = value("storeFile")
        val storePassword = value("storePassword")
        val keyAlias = value("keyAlias")
        val keyPassword = value("keyPassword")
        if (storeFile.isNullOrBlank() || storePassword.isNullOrBlank() || keyAlias.isNullOrBlank() || keyPassword.isNullOrBlank()) {
            return null
        }
        return ReleaseSigning(storeFile, storePassword, keyAlias, keyPassword)
    }

    /**
     * Reads `KEY=value` lines, ignoring blanks and `#` comments and stripping matching quotes, the
     * format `scripts/create-signing-keystore.sh` writes.
     */
    fun parseEnvFile(lines: List<String>): Map<String, String> =
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                key.trim() to value.trim().trim('"', '\'')
            }
}
