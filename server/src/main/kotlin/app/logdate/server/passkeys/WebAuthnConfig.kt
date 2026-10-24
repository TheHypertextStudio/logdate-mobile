package app.logdate.server.passkeys

import io.github.aakira.napier.Napier
import java.net.URI

/**
 * Server-side configuration for WebAuthn relying-party metadata.
 *
 * [origins] is the full set of client origins the relying party accepts during passkey ceremonies.
 * It always contains the canonical web origin and the HTTPS relying-party origin used by native
 * Apple apps. It may additionally contain Android
 * `android:apk-key-hash:<base64url-SHA256(signing-cert)>` origins. A real Android Credential Manager
 * ceremony sends the apk-key-hash as `clientDataJSON.origin` rather than an `https://` URL, so without
 * these entries webauthn4j rejects every on-device passkey. webauthn4j matches the incoming origin
 * against this set.
 */
data class WebAuthnConfig(
    val relyingPartyId: String,
    val relyingPartyName: String,
    val origins: Set<String>,
) {
    init {
        require(relyingPartyId.isNotBlank()) { "relyingPartyId must not be blank" }
        require(relyingPartyName.isNotBlank()) { "relyingPartyName must not be blank" }
        require(origins.any { it.startsWith(HTTPS_PREFIX) }) {
            "WebAuthnConfig requires at least one https:// origin"
        }
        val invalid = origins.filterNot { it.startsWith(HTTPS_PREFIX) || it.startsWith(ANDROID_ORIGIN_PREFIX) }
        require(invalid.isEmpty()) {
            "WebAuthnConfig origins must be https:// URLs or $ANDROID_ORIGIN_PREFIX origins; got $invalid"
        }
    }

    /** The canonical https web origin; drives rpId derivation and the public server descriptor. */
    val origin: String get() = origins.first { it.startsWith(HTTPS_PREFIX) }

    companion object {
        private const val HTTPS_PREFIX = "https://"
        private const val ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:"

        fun fromEnvironment(
            relyingPartyId: String? = System.getenv("WEBAUTHN_RP_ID"),
            relyingPartyName: String? = System.getenv("WEBAUTHN_RP_NAME"),
            origin: String? = System.getenv("WEBAUTHN_ORIGIN"),
            allowedOrigins: String? = System.getenv("WEBAUTHN_ALLOWED_ORIGINS"),
            serverOrigin: String? = System.getenv("LOGDATE_PUBLIC_ORIGIN"),
        ): WebAuthnConfig {
            val resolvedOrigin = normalizeOrigin(origin, serverOrigin)
            val resolvedRpId = relyingPartyId?.trim().orEmpty().ifBlank { deriveRpId(resolvedOrigin) ?: "logdate.app" }
            // The canonical web origin always leads so [origin] resolves to it deterministically.
            val origins = linkedSetOf(resolvedOrigin, "$HTTPS_PREFIX$resolvedRpId") + parseAllowedOrigins(allowedOrigins)
            // A ceremony rejected for a mismatched origin reports the origin it presented but not
            // the set it was checked against, which leaves an operator guessing. Record the
            // resolved set once at startup so the two can be compared.
            Napier.i("WebAuthn rpId=$resolvedRpId origins=$origins")
            return WebAuthnConfig(
                relyingPartyId = resolvedRpId,
                relyingPartyName = relyingPartyName?.trim().orEmpty().ifBlank { "LogDate" },
                origins = origins,
            )
        }

        private fun normalizeOrigin(
            explicitOrigin: String?,
            serverOrigin: String?,
        ): String =
            explicitOrigin
                ?.trim()
                .orEmpty()
                .ifBlank {
                    serverOrigin
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "https://app.logdate.com" }
                }.removeSuffix("/")

        private fun deriveRpId(origin: String): String? = runCatching { URI(origin).host?.trim()?.ifBlank { null } }.getOrNull()

        /**
         * Parses the comma-separated `WEBAUTHN_ALLOWED_ORIGINS`. Each entry must be an `https://` URL
         * or an `android:apk-key-hash:` origin; anything else is logged and skipped so a single typo
         * can't take down passkey verification for everyone.
         */
        private fun parseAllowedOrigins(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw
                .split(",")
                .map { it.trim().removeSuffix("/") }
                .filter { it.isNotBlank() }
                .mapNotNull { entry ->
                    when {
                        entry.startsWith(HTTPS_PREFIX) -> {
                            entry
                        }

                        entry.startsWith(ANDROID_ORIGIN_PREFIX) && entry.length > ANDROID_ORIGIN_PREFIX.length -> {
                            entry
                        }

                        else -> {
                            Napier.w("Skipping malformed WEBAUTHN_ALLOWED_ORIGINS entry: $entry")
                            null
                        }
                    }
                }.toSet()
        }
    }
}
