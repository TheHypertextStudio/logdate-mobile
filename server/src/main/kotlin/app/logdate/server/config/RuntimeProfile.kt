package app.logdate.server.config

/**
 * Deployment profile for the LogDate server, selected via the `LOGDATE_ENV` environment variable.
 *
 * Production mode enables strict startup validation — see [ProductionConfigValidator] — which refuses
 * to boot if required secrets are missing or obviously insecure. Development and test profiles keep
 * the permissive defaults that local iteration and the test suite rely on.
 *
 * An unset `LOGDATE_ENV` is a local run and selects [DEVELOPMENT]. Any other value that isn't one of
 * the names below stops the server, so a mistyped production setting can't fall back to development.
 */
enum class RuntimeProfile {
    PRODUCTION,
    DEVELOPMENT,
    TEST,
    ;

    val isProduction: Boolean get() = this == PRODUCTION

    companion object {
        const val ENV_VAR: String = "LOGDATE_ENV"

        fun fromEnvironment(readEnv: (String) -> String? = System::getenv): RuntimeProfile {
            val raw = readEnv(ENV_VAR)?.trim()?.lowercase().orEmpty()
            return when (raw) {
                "production", "prod" -> PRODUCTION
                "test" -> TEST
                "", "development", "dev", "local" -> DEVELOPMENT
                else ->
                    error(
                        "$ENV_VAR is '$raw', which is not a known profile. Set it to 'production' for a deployment, " +
                            "or 'development' or 'test' for a local run.",
                    )
            }
        }
    }
}

/**
 * Reads a boolean environment variable with a production-aware default: when the variable is unset,
 * return [productionDefault] in production and [devDefault] otherwise. Used by security toggles
 * that must be strict in prod but permissive in dev/test (e.g. WebAuthn strict verification).
 */
internal fun profileAwareBoolEnv(
    name: String,
    productionDefault: Boolean,
    devDefault: Boolean,
    readEnv: (String) -> String? = System::getenv,
    profile: RuntimeProfile = RuntimeProfile.fromEnvironment(readEnv),
): Boolean {
    val raw = readEnv(name)?.trim()?.lowercase().orEmpty()
    return when (raw) {
        "" -> if (profile.isProduction) productionDefault else devDefault
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> if (profile.isProduction) productionDefault else devDefault
    }
}
