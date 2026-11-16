package app.logdate.feature.core.account

/**
 * What someone typed to sign in: who they are, and where their account lives if they said.
 *
 * [server] is `null` when only a username was typed, which means the server the app already
 * uses, LogDate Cloud unless another was chosen earlier.
 */
internal data class SignInName(
    val username: String,
    val server: String?,
) {
    /** Whether the name points at LogDate Cloud by its own domain. */
    val isOnLogDateCloud: Boolean
        get() = server in LOGDATE_CLOUD_DOMAINS
}

/**
 * Reads the sign-in field.
 *
 * A person signs in with the name they know, and the name says where the account lives, so
 * nobody has to pick a server before signing in:
 * - `alex` is an account on the server the app already uses;
 * - `alex@journal.example.com` is `alex` on the server at `journal.example.com`;
 * - `alex.example.com`, a full handle, is `alex` on the server at `example.com`. Usernames never
 *   contain a dot, so a dot always starts the server.
 *
 * A leading `@` and surrounding spaces are ignored, as is the case of the server.
 */
internal fun parseSignInName(input: String): SignInName {
    val typed = input.trim().removePrefix("@")
    val separator = typed.indexOfFirst { it == '@' || it == '.' }
    if (separator < 0) return SignInName(username = typed, server = null)
    val server =
        typed
            .substring(separator + 1)
            .trim()
            .trimEnd('/')
            .lowercase()
    return SignInName(username = typed.substring(0, separator).trim(), server = server.ifEmpty { null })
}

private val LOGDATE_CLOUD_DOMAINS = setOf("logdate.app", "cloud.logdate.app")
