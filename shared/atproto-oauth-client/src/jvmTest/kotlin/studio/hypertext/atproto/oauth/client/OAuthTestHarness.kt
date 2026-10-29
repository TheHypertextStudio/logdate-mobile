package studio.hypertext.atproto.oauth.client

import io.ktor.client.HttpClient

/**
 * Wires an [AtprotoOAuthClient] to a [MockAtmosphere] with shared, in-memory stores.
 */
internal class OAuthTestHarness(
    val atmosphere: MockAtmosphere = MockAtmosphere(),
    val keys: List<OAuthClientKey> = listOf(JvmOAuthCrypto.generateClientKey("client-key-1")),
) {
    val stateStore = InMemoryOAuthStateStore(atmosphere.clock)
    val sessionStore = InMemoryOAuthSessionStore()

    val config =
        OAuthClientConfig(
            clientId = CLIENT_ID,
            redirectUris = listOf(REDIRECT_URI),
            keys = keys,
            scope = "atproto transition:generic",
            clientName = "LogDate test client",
            clientUri = "https://app.example.org",
        )

    /** A client instance, as one server process would create it. */
    val client: AtprotoOAuthClient = newClientInstance()

    init {
        atmosphere.clientMetadataJson = client.clientMetadataJson()
    }

    /** Another client instance sharing the same stores, as a second server process would. */
    fun newClientInstance(
        configuration: OAuthClientConfig = config,
        strictDpopNonces: Boolean = false,
        pendingStore: OAuthStateStore = stateStore,
        httpClient: HttpClient = atmosphere.httpClient,
        sessions: OAuthSessionStore = sessionStore,
        requireAuthorizationAfterPdsChange: Boolean = false,
        sessionLock: OAuthSessionLock = InProcessOAuthSessionLock(),
    ): AtprotoOAuthClient =
        AtprotoOAuthClient(
            config = configuration,
            identityResolver = atmosphere.identityResolver,
            httpClient = httpClient,
            crypto = JvmOAuthCrypto,
            stateStore = pendingStore,
            sessionStore = sessions,
            clock = atmosphere.clock,
            strictDpopNonces = strictDpopNonces,
            requireAuthorizationAfterPdsChange = requireAuthorizationAfterPdsChange,
            sessionLock = sessionLock,
        )

    suspend fun signIn(
        input: String = atmosphere.alice.handle,
        account: MockAccount = atmosphere.alice,
        options: OAuthAuthorizeOptions = OAuthAuthorizeOptions(),
    ): OAuthCallbackResult {
        val redirect = client.authorize(input, options)
        return client.callback(atmosphere.approve(redirect.url, account))
    }

    companion object {
        const val CLIENT_ID: String = "https://app.example.org/oauth-client-metadata.json"
        const val REDIRECT_URI: String = "https://app.example.org/oauth/callback"
    }
}
