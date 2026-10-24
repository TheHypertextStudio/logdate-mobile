package app.logdate.server.config

import io.github.aakira.napier.Napier
import java.net.URI
import java.util.Base64

/**
 * Refuses to start the server in [RuntimeProfile.PRODUCTION] if required secrets are missing or
 * obviously unsafe (e.g. the historic `"your-secret-key-change-in-production"` placeholder, or the
 * out-of-the-box `"logdate"` database password).
 *
 * Run this before any Ktor modules are installed — a loud crash at boot is the whole point.
 */
object ProductionConfigValidator {
    private const val MIN_JWT_SECRET_LENGTH = 32
    private const val MIN_DEDICATED_SECRET_LENGTH = 32

    private const val ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:"

    private val RELEASE_VERSION_PATTERN = Regex("^logdate-server@[0-9a-fA-F]{40}$")
    private val ANDROID_ORIGIN_PATTERN = Regex("^android:apk-key-hash:[A-Za-z0-9_-]{43}$")
    private val ANDROID_CERT_FINGERPRINT_PATTERN = Regex("^(?:[A-Fa-f0-9]{2}:){31}[A-Fa-f0-9]{2}$")

    private val INSECURE_JWT_SECRETS =
        setOf(
            "your-secret-key-change-in-production",
            "change-me",
            "changeme",
            "secret",
            "dev-secret",
        )

    private val INSECURE_DB_PASSWORDS =
        setOf(
            "logdate",
            "password",
            "postgres",
            "changeme",
        )

    class InsecureProductionConfigException(
        message: String,
    ) : IllegalStateException(message)

    fun validate(
        profile: RuntimeProfile = RuntimeProfile.fromEnvironment(),
        readEnv: (String) -> String? = System::getenv,
    ) {
        if (!profile.isProduction) return

        val failures = mutableListOf<String>()

        validateJwtSecret(readEnv, failures)
        validateDedicatedSecret(
            AtprotoSigningKeyKek,
            "It encrypts hosted AT Protocol signing keys; a deployment that already stores keys must keep the exact " +
                "value that encrypted them (see server/docs/environment-variables.md).",
            readEnv,
            failures,
            mustDifferFrom = listOf("JWT_SECRET"),
        )
        validateDecryptOnlySigningKeyKek(readEnv, failures)
        validateDedicatedSecret(
            AtprotoSessionSecret,
            "It signs hosted AT Protocol session tokens (see server/docs/environment-variables.md).",
            readEnv,
            failures,
            mustDifferFrom = listOf("JWT_SECRET", AtprotoSigningKeyKek.envVar),
        )
        validateDatabase(readEnv, failures)
        validateBlobStorage(readEnv, failures)
        val publicOrigin = validatePublicOrigin(readEnv, failures)
        validateAtprotoServiceUrl(readEnv, publicOrigin, failures)
        validateHealthToken(readEnv, failures)
        validateReleaseVersion(readEnv, failures)
        validateServerEncryption(readEnv, failures)
        validateSignedMediaUrls(readEnv, failures)
        validateWebAuthn(readEnv, failures)

        if (readEnv("LOGDATE_EXPECT_FIRST_PARTY").isEnabled()) {
            validateFirstPartyConfig(readEnv, failures)
        }

        if (failures.isNotEmpty()) {
            val message =
                buildString {
                    appendLine("Refusing to start LogDate server: insecure production configuration.")
                    failures.forEach { appendLine("  - $it") }
                    append("Set LOGDATE_ENV to 'development' for local runs, or fix the values above.")
                }
            throw InsecureProductionConfigException(message)
        }
    }

    private fun validateJwtSecret(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val jwtSecret = readEnv("JWT_SECRET")?.trim().orEmpty()
        when {
            jwtSecret.isEmpty() -> {
                failures += "JWT_SECRET is required in production (generate with: openssl rand -base64 32)."
            }

            jwtSecret.length < MIN_JWT_SECRET_LENGTH -> {
                failures += "JWT_SECRET must be at least $MIN_JWT_SECRET_LENGTH characters (got ${jwtSecret.length})."
            }

            jwtSecret.lowercase() in INSECURE_JWT_SECRETS -> {
                failures += "JWT_SECRET is set to a known placeholder value — rotate it before deploying."
            }
        }
    }

    /**
     * A dedicated secret must also differ from every secret in [mustDifferFrom]: a shared value means
     * rotating one silently changes the other, which is what the dedicated secret exists to prevent.
     */
    private fun validateDedicatedSecret(
        secret: DedicatedSecret,
        purpose: String,
        readEnv: (String) -> String?,
        failures: MutableList<String>,
        mustDifferFrom: List<String>,
    ) {
        val name = secret.envVar
        val value = readEnv(name)?.trim().orEmpty()
        val sharedWith = mustDifferFrom.firstOrNull { other -> value.isNotEmpty() && value == readEnv(other)?.trim() }
        when {
            value.isEmpty() -> {
                failures += "$name is required in production. $purpose"
            }

            value.length < MIN_DEDICATED_SECRET_LENGTH -> {
                failures += "$name must be at least $MIN_DEDICATED_SECRET_LENGTH characters (got ${value.length})."
            }

            value.lowercase() in INSECURE_JWT_SECRETS -> {
                failures += "$name is set to a known placeholder value."
            }

            sharedWith != null -> {
                failures += "$name must differ from $sharedWith; give it its own value."
            }
        }
    }

    private fun validateDecryptOnlySigningKeyKek(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val name = AtprotoSigningKeyKekDecryptOnly.ENV_VAR
        val value = readEnv(name)?.trim().orEmpty()
        if (value.isEmpty()) return
        when {
            value.length < MIN_DEDICATED_SECRET_LENGTH -> {
                failures += "$name must be at least $MIN_DEDICATED_SECRET_LENGTH characters (got ${value.length})."
            }

            value == readEnv(AtprotoSigningKeyKek.envVar)?.trim() -> {
                failures += "$name must differ from ${AtprotoSigningKeyKek.envVar}; it only exists while moving to a new one."
            }
        }
    }

    private fun validateDatabase(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val dbPassword = readEnv("DATABASE_PASSWORD")?.trim().orEmpty()
        val databaseUrl = readEnv("DATABASE_URL")?.trim().orEmpty()
        val dbUrlHasCredentials = databaseUrl.contains("@")
        when {
            dbPassword.isEmpty() && !dbUrlHasCredentials -> {
                failures += "DATABASE_PASSWORD is required in production (or embed credentials in DATABASE_URL)."
            }

            dbPassword.lowercase() in INSECURE_DB_PASSWORDS -> {
                failures += "DATABASE_PASSWORD is set to a known default — use a real secret."
            }
        }

        val hasCloudSqlContract =
            readEnv("INSTANCE_CONNECTION_NAME")?.trim().isNullOrEmpty().not() &&
                readEnv("DB_NAME")?.trim().isNullOrEmpty().not()
        if (databaseUrl.isEmpty() && !hasCloudSqlContract) {
            failures += "DATABASE_URL or INSTANCE_CONNECTION_NAME and DB_NAME are required in production."
        }
    }

    private fun validateBlobStorage(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val hasGcsBucket = readEnv("GCS_BUCKET_NAME")?.trim().isNullOrEmpty().not()
        val hasFilesystemStorage = readEnv("LOGDATE_BLOB_STORAGE_DIR")?.trim().isNullOrEmpty().not()
        if (!hasGcsBucket && !hasFilesystemStorage) {
            failures += "GCS_BUCKET_NAME or LOGDATE_BLOB_STORAGE_DIR must be configured for durable media storage."
        }
    }

    private fun validatePublicOrigin(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ): String? {
        val origin = readEnv("LOGDATE_PUBLIC_ORIGIN")?.trim().orEmpty()
        val normalized = origin.toNormalizedHttpsUrl()
        if (normalized == null) {
            failures += "LOGDATE_PUBLIC_ORIGIN must be an https:// URL in production."
        }
        return normalized
    }

    private fun validateAtprotoServiceUrl(
        readEnv: (String) -> String?,
        publicOrigin: String?,
        failures: MutableList<String>,
    ) {
        val serviceUrl = readEnv("ATPROTO_PDS_SERVICE_URL")?.trim().orEmpty().toNormalizedHttpsUrl()
        if (publicOrigin != null && serviceUrl != publicOrigin) {
            failures += "ATPROTO_PDS_SERVICE_URL must equal LOGDATE_PUBLIC_ORIGIN in production."
        }
    }

    private fun validateHealthToken(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        if (readEnv("HEALTH_INTERNAL_TOKEN")?.trim().isNullOrEmpty()) {
            failures += "HEALTH_INTERNAL_TOKEN is required in production."
        }
    }

    private fun validateReleaseVersion(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val releaseVersion = readEnv("RELEASE_VERSION")?.trim().orEmpty()
        if (!RELEASE_VERSION_PATTERN.matches(releaseVersion)) {
            failures += "RELEASE_VERSION must match logdate-server@<40-hex-sha> in production."
        }
    }

    private fun validateServerEncryption(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        if (!readEnv("SERVER_ENCRYPTION_ENABLED").isEnabled()) {
            failures += "SERVER_ENCRYPTION_ENABLED must be true in production."
        }

        val encodedKey = readEnv("SERVER_ENCRYPTION_KEY")?.trim().orEmpty()
        if (encodedKey.isEmpty()) {
            failures += "SERVER_ENCRYPTION_KEY is required in production."
        } else {
            val decoded = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()
            when {
                decoded == null -> {
                    failures += "SERVER_ENCRYPTION_KEY must be valid base64."
                }

                decoded.size !in setOf(16, 24, 32) -> {
                    failures += "SERVER_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes (AES-128/192/256)."
                }
            }
        }

        if (readEnv("SERVER_ENCRYPTION_KEY_ID")?.trim().isNullOrEmpty()) {
            failures += "SERVER_ENCRYPTION_KEY_ID is required in production."
        }
    }

    private fun validateSignedMediaUrls(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        if (!readEnv("SYNC_MEDIA_SIGNED_URLS").isEnabled()) {
            failures += "SYNC_MEDIA_SIGNED_URLS must be true in production."
        }
    }

    private fun validateFirstPartyConfig(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        val deploymentKind = readEnv("LOGDATE_DEPLOYMENT_KIND")?.trim()?.lowercase()
        if (deploymentKind != "first_party") {
            failures += "LOGDATE_DEPLOYMENT_KIND must be first_party when LOGDATE_EXPECT_FIRST_PARTY=true."
        }
        if (readEnv("GCS_BUCKET_NAME")?.trim().isNullOrEmpty()) {
            failures += "GCS_BUCKET_NAME is required when LOGDATE_EXPECT_FIRST_PARTY=true."
        }
        if (readEnv("BILLING_PROVIDER")?.trim()?.lowercase().let { it.isNullOrEmpty() || it == "disabled" }) {
            failures += "BILLING_PROVIDER must not be disabled when LOGDATE_EXPECT_FIRST_PARTY=true."
        }

        val allowedOrigins = readEnv("WEBAUTHN_ALLOWED_ORIGINS").splitEnvironmentValues()
        if (allowedOrigins.none { ANDROID_ORIGIN_PATTERN.matches(it) }) {
            failures +=
                "WEBAUTHN_ALLOWED_ORIGINS must include a valid android:apk-key-hash origin when LOGDATE_EXPECT_FIRST_PARTY=true."
        }

        val fingerprints = readEnv("ANDROID_CERT_FINGERPRINTS").splitEnvironmentValues()
        if (fingerprints.isEmpty() || fingerprints.any { !ANDROID_CERT_FINGERPRINT_PATTERN.matches(it) }) {
            failures +=
                "ANDROID_CERT_FINGERPRINTS must include colon-hex SHA-256 fingerprints when LOGDATE_EXPECT_FIRST_PARTY=true."
        }
    }

    private fun String?.isEnabled(): Boolean = this?.trim()?.equals("true", ignoreCase = true) == true

    private fun String?.splitEnvironmentValues(): List<String> =
        this
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun String.toNormalizedHttpsUrl(): String? {
        val uri = runCatching { URI(this) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        return uri.toString().removeSuffix("/")
    }

    private fun validateWebAuthn(
        readEnv: (String) -> String?,
        failures: MutableList<String>,
    ) {
        validateAllowedOrigins(readEnv("WEBAUTHN_ALLOWED_ORIGINS"), failures)

        // Passkey credentials are pinned to the relying-party ID at registration time, so a
        // production rollout that silently derives the RP ID from the request origin (the default
        // fallback in `WebAuthnConfig.fromEnvironment`) will quietly bind every new passkey to
        // whatever host first received traffic — typically a Cloud Run revision URL or a
        // preview subdomain. Those passkeys will not work against the canonical apex once DNS is
        // pointed at production. The fix is to require the operator to set both env vars
        // explicitly and to verify their relationship before we boot.
        val rpId = readEnv("WEBAUTHN_RP_ID")?.trim().orEmpty()
        val webauthnOrigin = readEnv("WEBAUTHN_ORIGIN")?.trim().orEmpty()

        if (rpId.isEmpty()) {
            failures +=
                "WEBAUTHN_RP_ID is required in production (set to the registrable apex you serve passkeys for, e.g. 'logdate.app'). " +
                "Without it the server falls back to deriving from request origin, which pins passkeys to whichever host happened to serve the registration."
        }
        if (webauthnOrigin.isEmpty()) {
            failures += "WEBAUTHN_ORIGIN is required in production (e.g. 'https://cloud.logdate.app')."
            return
        }
        if (!webauthnOrigin.startsWith("https://")) {
            failures += "WEBAUTHN_ORIGIN must use https:// scheme in production (got '$webauthnOrigin')."
            return
        }
        if (rpId.isEmpty()) return

        val originHost = runCatching { URI(webauthnOrigin).host?.lowercase()?.trim() }.getOrNull()
        if (originHost.isNullOrBlank()) {
            failures += "WEBAUTHN_ORIGIN '$webauthnOrigin' does not contain a parseable host."
            return
        }
        val rpIdLower = rpId.lowercase()
        val matchesExactly = originHost == rpIdLower
        val isSubdomain = originHost.endsWith(".$rpIdLower")
        if (!matchesExactly && !isSubdomain) {
            failures +=
                "WEBAUTHN_RP_ID '$rpId' must equal or be the registrable apex of WEBAUTHN_ORIGIN host '$originHost'. " +
                "Otherwise passkeys created against the origin will not be recognized when the user returns."
        }
    }

    /**
     * `WEBAUTHN_ALLOWED_ORIGINS` carries the extra origins passkey ceremonies may present — most
     * importantly the Android `android:apk-key-hash:<hash>` origins, without which on-device signup
     * and sign-in fail. Each entry must be an `https://` URL or an apk-key-hash origin; malformed
     * entries are a hard failure. A missing android origin is only a warning: a web-only deployment
     * is legitimate, but real Android clients will not be able to authenticate.
     */
    private fun validateAllowedOrigins(
        raw: String?,
        failures: MutableList<String>,
    ) {
        val entries =
            raw
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()

        entries.forEach { entry ->
            val isHttps = entry.startsWith("https://")
            val isAndroid = entry.startsWith(ANDROID_ORIGIN_PREFIX) && entry.length > ANDROID_ORIGIN_PREFIX.length
            if (!isHttps && !isAndroid) {
                failures +=
                    "WEBAUTHN_ALLOWED_ORIGINS entry '$entry' must be an https:// URL or an " +
                    "'${ANDROID_ORIGIN_PREFIX}<hash>' origin."
            }
        }

        if (entries.none { it.startsWith(ANDROID_ORIGIN_PREFIX) }) {
            Napier.w(
                "WEBAUTHN_ALLOWED_ORIGINS has no android:apk-key-hash: origin; real Android passkey " +
                    "signup and sign-in will fail until one is added.",
            )
        }
    }
}
