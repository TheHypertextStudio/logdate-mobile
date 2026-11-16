package app.logdate.server.billing

/**
 * What the server needs to talk to Stripe: the secret key for API calls and the signing secret
 * that proves a webhook came from Stripe.
 *
 * [apiBaseUrl] is Stripe's API except in tests, which point it at a local stand-in.
 */
data class StripeConfig(
    val secretKey: String,
    val webhookSecret: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    // The keys must never reach a log line through a data class's generated toString.
    override fun toString(): String = "StripeConfig(apiBaseUrl=$apiBaseUrl)"

    companion object {
        const val SECRET_KEY_ENV: String = "STRIPE_SECRET_KEY"
        const val WEBHOOK_SECRET_ENV: String = "STRIPE_WEBHOOK_SECRET"
        const val API_BASE_URL_ENV: String = "STRIPE_API_BASE_URL"
        const val DEFAULT_API_BASE_URL: String = "https://api.stripe.com"

        /** The configuration from the environment, or null when either secret is missing. */
        fun fromEnvironment(readEnv: (String) -> String? = System::getenv): StripeConfig? {
            val secretKey = readEnv(SECRET_KEY_ENV)?.trim().orEmpty()
            val webhookSecret = readEnv(WEBHOOK_SECRET_ENV)?.trim().orEmpty()
            if (secretKey.isEmpty() || webhookSecret.isEmpty()) return null
            return StripeConfig(
                secretKey = secretKey,
                webhookSecret = webhookSecret,
                apiBaseUrl = readEnv(API_BASE_URL_ENV)?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: DEFAULT_API_BASE_URL,
            )
        }
    }
}
