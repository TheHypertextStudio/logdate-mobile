package app.logdate.server.billing

/**
 * Whether LogDate Cloud has launched.
 *
 * Before launch the service is in beta. The base plan is free and is not sold, and every account
 * created is recorded as a beta member, which keeps the base plan at no charge for as long as the
 * account exists. LogDate Cloud Plus is a paid plan from the start: it can be bought during the
 * beta wherever the server is connected to Stripe.
 *
 * Launch ends the beta. From then on the base plan is sold too, and a new account has no plan
 * until it buys one.
 *
 * Set by `LOGDATE_CLOUD_LAUNCHED`, which is false unless it says `true`.
 */
data class CloudLaunch(
    val launched: Boolean,
) {
    companion object {
        const val ENV_VAR: String = "LOGDATE_CLOUD_LAUNCHED"

        fun fromEnvironment(readEnv: (String) -> String? = System::getenv): CloudLaunch = CloudLaunch(launched = readEnv(ENV_VAR) == "true")
    }
}
