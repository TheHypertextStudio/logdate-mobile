package app.logdate.server.oauth

import studio.hypertext.atproto.pds.AuthorizationServerMetadata
import studio.hypertext.atproto.pds.ProtectedResourceMetadata
import java.net.URI

/**
 * Server configuration and discovery metadata for AT Protocol OAuth surfaces.
 *
 * [passkeySignInEnabled] turns on the browser authorization page, where a person signs in with a
 * passkey to let another app use their identity. It is off by default; see
 * `LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED`.
 */
data class OAuthConfig(
    val issuer: String,
    val resource: String = issuer,
    val passkeySignInEnabled: Boolean = false,
    /**
     * Client IDs whose self-reported names the authorization page may show and whose refresh
     * tokens last longer. Every other app is named by its client ID URL.
     */
    val trustedClientIds: Set<String> = emptySet(),
) {
    init {
        require(issuer.startsWith("https://")) { "issuer must use https" }
        require(resource.startsWith("https://")) { "resource must use https" }
    }

    val normalizedIssuer: String = issuer.trim().removeSuffix("/")
    val normalizedResource: String = resource.trim().removeSuffix("/")
    val authorizationEndpoint: String = "$normalizedIssuer/oauth/authorize"
    val tokenEndpoint: String = "$normalizedIssuer/oauth/token"
    val pushedAuthorizationRequestEndpoint: String = "$normalizedIssuer/oauth/par"
    val revocationEndpoint: String = "$normalizedIssuer/oauth/revoke"
    val jwksUri: String = "$normalizedIssuer/oauth/jwks"

    /** The scheme, host and port of [issuer], which is where the authorization page is served. */
    val issuerOrigin: String =
        URI(normalizedIssuer).let { uri ->
            val port = if (uri.port == -1 || uri.port == DEFAULT_HTTPS_PORT) "" else ":${uri.port}"
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
        }

    /**
     * Returns the origin a browser presents for a passkey ceremony on the authorization page, or
     * `null` when browsers will refuse one there.
     *
     * A browser only offers a passkey to a page whose host is the passkey's relying-party ID or a
     * subdomain of it, so the issuer's host must sit inside [relyingPartyId].
     */
    fun passkeySignInOrigin(relyingPartyId: String): String? {
        val host = URI(issuerOrigin).host
        val rpId = relyingPartyId.trim().lowercase()
        if (host != rpId && !host.endsWith(".$rpId")) return null
        return issuerOrigin
    }

    /**
     * Builds OAuth authorization server metadata for discovery.
     */
    fun authorizationServerMetadata(): AuthorizationServerMetadata =
        AuthorizationServerMetadata(
            issuer = normalizedIssuer,
            authorization_endpoint = authorizationEndpoint,
            token_endpoint = tokenEndpoint,
            pushed_authorization_request_endpoint = pushedAuthorizationRequestEndpoint,
            revocation_endpoint = revocationEndpoint,
            jwks_uri = jwksUri,
            response_types_supported = listOf("code"),
            grant_types_supported = listOf("authorization_code", "refresh_token"),
            code_challenge_methods_supported = listOf("S256"),
            token_endpoint_auth_methods_supported = listOf("none", "private_key_jwt"),
            token_endpoint_auth_signing_alg_values_supported = listOf("ES256", "ES256K"),
            dpop_signing_alg_values_supported = listOf("ES256", "ES256K"),
            scopes_supported = OAuthScopes.supported,
            authorization_response_iss_parameter_supported = true,
            require_pushed_authorization_requests = true,
            client_id_metadata_document_supported = true,
        )

    /**
     * Builds protected resource metadata for AT Protocol clients.
     */
    fun protectedResourceMetadata(): ProtectedResourceMetadata =
        ProtectedResourceMetadata(
            resource = normalizedResource,
            authorization_servers = listOf(normalizedIssuer),
        )

    companion object {
        private const val PASSKEY_SIGN_IN_FLAG = "LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED"
        private const val TRUSTED_CLIENT_IDS = "LOGDATE_OAUTH_TRUSTED_CLIENT_IDS"
        private const val DEFAULT_HTTPS_PORT = 443

        /** Reads `LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED`; only the exact value `true` turns it on. */
        fun passkeySignInEnabledFromEnvironment(readEnv: (String) -> String? = System::getenv): Boolean =
            readEnv(PASSKEY_SIGN_IN_FLAG)?.trim() == "true"

        /** Reads the comma-separated `LOGDATE_OAUTH_TRUSTED_CLIENT_IDS`. */
        fun trustedClientIdsFromEnvironment(readEnv: (String) -> String? = System::getenv): Set<String> =
            readEnv(TRUSTED_CLIENT_IDS)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()

        /**
         * Reads OAuth discovery configuration from environment variables.
         */
        fun fromEnvironment(
            defaultIssuer: String = "https://logdate.app",
            issuer: String? = System.getenv("ATPROTO_OAUTH_ISSUER"),
            resource: String? = System.getenv("ATPROTO_OAUTH_RESOURCE"),
        ): OAuthConfig {
            val resolvedIssuer =
                issuer
                    ?.trim()
                    .orEmpty()
                    .ifBlank { defaultIssuer }
                    .removeSuffix("/")
            val resolvedResource =
                resource
                    ?.trim()
                    .orEmpty()
                    .ifBlank { resolvedIssuer }
                    .removeSuffix("/")
            return OAuthConfig(
                issuer = resolvedIssuer,
                resource = resolvedResource,
            )
        }
    }
}

/*
 * RFC 8414-style authorization server metadata for AT Protocol clients.
 */
