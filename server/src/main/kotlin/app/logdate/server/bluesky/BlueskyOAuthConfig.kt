package app.logdate.server.bluesky

import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.oauth.client.JsonWebKeySet
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthClientConfig
import studio.hypertext.atproto.oauth.client.OAuthClientKey
import java.net.URI
import java.util.Base64

/** Explicit server-owned client configuration; disabled until provisioned for its environment. */
internal object BlueskyOAuthConfig {
    fun fromEnvironment(readEnv: (String) -> String? = System::getenv): OAuthClientConfig? {
        when (readEnv("LOGDATE_BLUESKY_SIGN_IN_ENABLED")?.trim()?.lowercase()) {
            null, "", "false" -> return null
            "true" -> Unit
            else -> throw IllegalArgumentException("LOGDATE_BLUESKY_SIGN_IN_ENABLED must be true or false")
        }
        requirePrivateJournalServices(readEnv)
        val databaseUrl = readEnv("DATABASE_URL").orEmpty()
        require(
            listOf("jdbc:postgresql://", "postgresql://", "postgres://").any(databaseUrl::startsWith) ||
                !readEnv("INSTANCE_CONNECTION_NAME").isNullOrBlank(),
        ) {
            "Bluesky sign-in requires shared PostgreSQL storage"
        }
        val encryptionKey = readEnv("ATPROTO_SIGNING_KEY_KEK")
        require(!encryptionKey.isNullOrBlank() && encryptionKey.length >= 32) {
            "Bluesky sign-in requires an explicit encryption key of at least 32 characters"
        }
        val origin = readEnv("LOGDATE_PUBLIC_ORIGIN").orEmpty().removeSuffix("/")
        val uri =
            try {
                URI(origin)
            } catch (_: Exception) {
                throw IllegalArgumentException("Bluesky sign-in requires a valid public origin")
            }
        require(
            uri.scheme == "https" &&
                uri.host?.endsWith(".logdate.app") == true &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) { "Bluesky sign-in requires an HTTPS LogDate origin without credentials, port or path" }
        val keys =
            try {
                val encoded = requireNotNull(readEnv("BLUESKY_OAUTH_CLIENT_JWKS"))
                require(encoded.length <= 65_536)
                val parsed = Json.decodeFromString<JsonWebKeySet>(encoded)
                require(parsed.keys.size in 1..10)
                parsed.keys.map(::OAuthClientKey).also { keys ->
                    require(keys.map { it.kid }.distinct().size == keys.size)
                    keys.forEach(::validateKeyPair)
                }
            } catch (_: Exception) {
                // JSON errors may contain the secret value. Do not retain the original exception.
                throw IllegalArgumentException("BLUESKY_OAUTH_CLIENT_JWKS must contain provisioned private ES256 client keys")
            }
        return OAuthClientConfig(
            clientId = "$origin/oauth/client-metadata.json",
            redirectUris = listOf("$origin/oauth/bluesky/callback"),
            keys = keys,
            scope = "atproto",
            clientName = "LogDate",
            clientUri = origin,
        )
    }

    private fun requirePrivateJournalServices(readEnv: (String) -> String?) {
        require(
            readEnv("LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED") == "true" &&
                readEnv("LOGDATE_PERSONAL_SPACE_ENABLED") == "true",
        ) {
            "Bluesky sign-in requires encrypted account keys and personal-space storage"
        }
    }

    private fun validateKeyPair(key: OAuthClientKey) {
        val jwk = key.privateJwk

        fun coordinate(encoded: String): ByteArray {
            require(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
            return Base64.getUrlDecoder().decode(encoded).also { require(it.size == 32) }
        }
        coordinate(requireNotNull(jwk.d))
        val publicKey = EcKeySupport.decodePublicKeyFromJwk(EcCurve.P256, coordinate(jwk.x), coordinate(jwk.y))
        val payload = "logdate:bluesky-client-key-validation".encodeToByteArray()
        val signature = JvmOAuthCrypto.signEs256(jwk, payload)
        require(EcKeySupport.verifySha256(publicKey, EcCurve.P256, payload, signature))
    }
}
