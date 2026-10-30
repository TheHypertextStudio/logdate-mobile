package app.logdate.server.routes.docs

import app.logdate.server.oauth.JsonWebKey
import app.logdate.server.oauth.JsonWebKeySet
import app.logdate.server.oauth.OAuthPasskeyApprovalRequest
import app.logdate.server.oauth.OAuthPasskeyApprovalResponse
import app.logdate.server.oauth.OAuthPasskeyOptionsRequest
import app.logdate.server.oauth.OAuthPasskeyOptionsResponse
import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.OAuthAuthorizationDecisionForm
import app.logdate.server.routes.OAuthParForm
import app.logdate.server.routes.OAuthRevokeForm
import app.logdate.server.routes.OAuthTokenForm
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.dpopNonceHeader
import app.logdate.server.routes.oauthError
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import studio.hypertext.atproto.pds.AuthorizationPromptResponse
import studio.hypertext.atproto.pds.AuthorizationServerMetadata
import studio.hypertext.atproto.pds.OAuthTokenResponse
import studio.hypertext.atproto.pds.ProtectedResourceMetadata
import studio.hypertext.atproto.pds.PushedAuthorizationBody

/** Documentation for the OAuth endpoints in `OAuthRoutes.kt`. */
internal object OAuthDocs {
    private const val ISSUER = "https://cloud.logdate.app"
    private const val CLIENT_ID = "https://journalviewer.example/oauth-client-metadata.json"
    private const val REQUEST_URI = "urn:ietf:params:oauth:request_uri:req_7f3c9a1b2d4e"
    private const val DPOP_PROOF = "eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Li4ufX0.dpop-proof-example"

    private val notConfigured =
        ErrorCase(
            "server_error",
            "This deployment has AT Protocol OAuth switched off (`ATPROTO_OAUTH` is missing from **Describe this server**). " +
                "There is nothing a client can do; use the LogDate sign-in instead.",
            "OAuth is not configured",
        )
    private val serverError =
        ErrorCase("server_error", "Something failed on the server. Retry with backoff.", "OAuth request failed")

    private val invalidRequest =
        ErrorCase(
            "invalid_request",
            "A required form field or the `DPoP` header is missing or malformed. The `error_description` names it.",
            "client_id is required",
        )
    private val invalidClient =
        ErrorCase(
            "invalid_client",
            "The `client_id` is not a valid client metadata URL, or the document it points at is invalid.",
            "Client metadata could not be loaded",
        )
    private val invalidDpop =
        ErrorCase(
            "invalid_dpop_proof",
            "The DPoP proof does not verify, or its `htm`/`htu` do not match this request. Sign a fresh proof.",
            "DPoP proof signature is invalid",
        )
    private val useDpopNonce =
        ErrorCase(
            "use_dpop_nonce",
            "The proof must carry the server's current nonce. Read it from the `DPoP-Nonce` response header, put it in the proof's `nonce` claim, and retry once.",
            "The DPoP proof must include the current server nonce",
        )

    private fun RequestConfig.dpopHeader() {
        headerParameter<String>("DPoP") {
            description =
                "A DPoP proof JWT for this exact request: `htm` = the method, `htu` = this URL, `nonce` = the latest `DPoP-Nonce` " +
                "the server sent you, signed with your client's key pair."
            required = true
            example("Example") { value = DPOP_PROOF }
        }
    }

    val getAuthorizationServerMetadata: RouteConfig.() -> Unit = {
        publicOperation(
            "getOAuthAuthorizationServerMetadata",
            ApiTags.OAUTH,
            "Get authorization server metadata",
            """
            The RFC 8414 discovery document for this server's OAuth authorization server. An AT Protocol client
            reads it first to learn the endpoint URLs and what the server supports: only the `code` response
            type with PKCE (`S256`), pushed authorization requests are required, DPoP proofs must be signed with
            `ES256` or `ES256K`. The scopes are `atproto`, which only confirms which account signed in, and
            `transition:generic`, which also allows reading and writing collections open to apps (none yet). Access tokens last 15
            minutes.
            """,
        )
        response {
            ok(
                "The discovery document.",
                AuthorizationServerMetadata(
                    issuer = ISSUER,
                    authorization_endpoint = "$ISSUER/oauth/authorize",
                    token_endpoint = "$ISSUER/oauth/token",
                    pushed_authorization_request_endpoint = "$ISSUER/oauth/par",
                    revocation_endpoint = "$ISSUER/oauth/revoke",
                    jwks_uri = "$ISSUER/oauth/jwks",
                    response_types_supported = listOf("code"),
                    grant_types_supported = listOf("authorization_code", "refresh_token"),
                    code_challenge_methods_supported = listOf("S256"),
                    token_endpoint_auth_methods_supported = listOf("none", "private_key_jwt"),
                    token_endpoint_auth_signing_alg_values_supported = listOf("ES256", "ES256K"),
                    dpop_signing_alg_values_supported = listOf("ES256", "ES256K"),
                    scopes_supported = listOf("atproto", "transition:generic"),
                    authorization_response_iss_parameter_supported = true,
                    require_pushed_authorization_requests = true,
                    client_id_metadata_document_supported = true,
                ),
            )
        }
    }

    val getProtectedResourceMetadata: RouteConfig.() -> Unit = {
        publicOperation(
            "getOAuthProtectedResourceMetadata",
            ApiTags.OAUTH,
            "Get protected resource metadata",
            """
            The RFC 9728 document that says which authorization server issues tokens for this personal data
            server. Clients that only know the PDS URL read this to find the authorization server; here it is
            the same host.
            """,
        )
        response {
            ok("The protected-resource document.", ProtectedResourceMetadata(resource = ISSUER, authorization_servers = listOf(ISSUER)))
        }
    }

    val getJwks: RouteConfig.() -> Unit = {
        publicOperation(
            "getOAuthJwks",
            ApiTags.OAUTH,
            "Get the signing keys",
            """
            The JSON Web Key Set the server signs OAuth tokens with. Clients and other servers fetch it to
            verify token signatures. Keys may rotate; cache by `kid` and refetch on an unknown key.
            """,
        )
        response {
            ok(
                "The public keys.",
                JsonWebKeySet(
                    keys =
                        listOf(
                            JsonWebKey(
                                kty = "EC",
                                use = "sig",
                                key_ops = listOf("verify"),
                                alg = "ES256",
                                kid = "2026-09-01",
                                crv = "P-256",
                                x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                                y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0",
                            ),
                        ),
                ),
            )
        }
    }

    val pushAuthorizationRequest: RouteConfig.() -> Unit = {
        publicOperation(
            "pushAuthorizationRequest",
            ApiTags.OAUTH,
            "Push an authorization request",
            """
            Step one of the OAuth flow (RFC 9126, required here). Instead of putting your authorization
            parameters in a browser URL, you POST them as a form and receive a short-lived `request_uri`.
            Then send the person to `/oauth/authorize?request_uri=<it>`.

            The form fields are `client_id` (the URL of your client metadata document), `redirect_uri`,
            `scope` (`atproto`, optionally with `transition:generic`), `response_type` (`code`), `code_challenge`
            and `code_challenge_method` (`S256`), plus optional `state`, `login_hint`, and
            `client_assertion_type` / `client_assertion` for confidential clients. The request must carry a
            `DPoP` proof; the response's `DPoP-Nonce` header is the nonce to use on your next proof.

            Scopes this server does not enforce are dropped, so the token's `scope` lists exactly what it allows.
            Each request needs a fresh PKCE verifier: a `code_challenge` that was already used is refused.
            """,
        )
        request {
            dpopHeader()
            body<OAuthParForm> {
                description =
                    "`application/x-www-form-urlencoded`. Confidential clients add `client_assertion_type` and `client_assertion`."
                required = true
                mediaTypes(ContentType.Application.FormUrlEncoded)
                example("Example") {
                    value =
                        OAuthParForm(
                            clientId = CLIENT_ID,
                            redirectUri = "https://journalviewer.example/callback",
                            scope = "atproto",
                            responseType = "code",
                            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                            codeChallengeMethod = "S256",
                            state = "af0ifjsldkj",
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.Created) {
                description =
                    "The request is stored. Send the person to `/oauth/authorize?request_uri=<request_uri>` within `expires_in` seconds."
                header<String>("DPoP-Nonce") { this.description = "The nonce to put in your next DPoP proof." }
                body<PushedAuthorizationBody> {
                    example("Example") { value = PushedAuthorizationBody(requestUri = REQUEST_URI, expiresInSeconds = 300) }
                }
            }
            oauthError(HttpStatusCode.BadRequest, invalidRequest, invalidClient, invalidDpop, useDpopNonce, headers = dpopNonceHeader)
            oauthError(HttpStatusCode.TooManyRequests, tooManyAuthorizationRequests, headers = retryAfter)
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.NotImplemented, notConfigured)
        }
    }

    val getAuthorizationPrompt: RouteConfig.() -> Unit = {
        bearerOperation(
            "getAuthorizationPrompt",
            ApiTags.OAUTH,
            "Load the consent prompt",
            """
            Step two, called by the LogDate app or web front end, not by the third-party client. It loads the
            pushed request named by `request_uri` and returns what to show the signed-in person: which client
            is asking, what it wants, and where it will be sent back. The person must be signed in to LogDate
            (a LogDate bearer token, not an OAuth token).

            The third-party client never sees this; it only sends the person to this URL and waits for the
            redirect.

            When the server has passkey sign-in on the authorization page turned on, a browser that arrives
            without a LogDate bearer token gets an HTML page instead. It shows which app is asking and lets the
            person sign in with a passkey, which approves the request. See **Get passkey options for the
            authorization page**. Send `client_id` alongside `request_uri`; the page refuses a `client_id` that
            does not match the pushed request.
            """,
        )
        request {
            queryParameter<String>("request_uri") {
                description = "The `request_uri` returned by **Push an authorization request**."
                required = true
                example("Example") { value = REQUEST_URI }
            }
        }
        response {
            ok(
                "What to show on the consent screen.",
                AuthorizationPromptResponse(
                    clientId = CLIENT_ID,
                    clientName = "Journal Viewer",
                    redirectUri = "https://journalviewer.example/callback",
                    scope = "atproto",
                    state = "af0ifjsldkj",
                    loginHint = null,
                    did = DocExamples.DID,
                    handle = DocExamples.HANDLE,
                ),
            )
            oauthError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "invalid_request",
                    "`request_uri` is missing, unknown, already used, or expired. Start again from **Push an authorization request**.",
                    "request_uri is required",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.OAUTH)
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.NotImplemented, notConfigured)
        }
    }

    val submitAuthorizationDecision: RouteConfig.() -> Unit = {
        bearerOperation(
            "submitAuthorizationDecision",
            ApiTags.OAUTH,
            "Approve or deny the request",
            """
            Step three, also called by the LogDate front end. Records the signed-in person's decision for the
            pushed request and answers a `302` redirect to the client's `redirect_uri` carrying either an
            authorization `code` (and `iss` and `state`) or an `error`. The front end follows the redirect;
            the third-party client receives it and continues with **Exchange a code or refresh token**.

            With passkey sign-in on the authorization page turned on, the page itself posts here without a bearer
            token to cancel (`decision=deny`). Approving without a bearer token is refused; the page approves by
            signing in with a passkey instead.
            """,
        )
        request {
            body<OAuthAuthorizationDecisionForm> {
                description = "`application/x-www-form-urlencoded`."
                required = true
                mediaTypes(ContentType.Application.FormUrlEncoded)
                example("Approve") { value = OAuthAuthorizationDecisionForm(requestUri = REQUEST_URI, decision = "approve") }
            }
        }
        response {
            code(HttpStatusCode.Found) {
                description =
                    "Redirect to the client's `redirect_uri` with `code`, `iss` and `state` on approval, or `error=access_denied` on denial."
                header<String>(
                    "Location",
                ) { this.description = "The client's `redirect_uri` with the result appended as query parameters." }
            }
            oauthError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "invalid_request",
                    "`request_uri` is missing or expired, or `decision` is not `approve` or `deny`.",
                    "decision must be approve or deny",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.OAUTH)
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.NotImplemented, notConfigured)
        }
    }

    private val passkeySignInDisabled =
        ErrorCase(
            "server_error",
            "This server has passkey sign-in on the authorization page turned off. The page is not served either, so " +
                "only the LogDate apps can approve requests here.",
            "Passkey sign-in on the authorization page is not enabled on this server",
        )
    private val databaseUnavailable =
        ErrorCase(
            "temporarily_unavailable",
            "The server could not reach its database. Nothing about the request was wrong; retry shortly. Internal details " +
                "are logged on the server, never returned.",
            "The server could not reach its database. Try again shortly.",
        )
    private val tooManyPasskeyAttempts =
        ErrorCase(
            "temporarily_unavailable",
            "Too many passkey attempts came from this client address in the last minute. Wait for `Retry-After` seconds.",
            "Too many sign-in attempts from this network. Wait a minute and try again.",
        )
    private val tooManyAuthorizationRequests =
        ErrorCase(
            "temporarily_unavailable",
            "Too many authorization requests came from this client address in the last minute. Wait for `Retry-After` seconds.",
            "Too many authorization requests from this network. Wait a minute and try again.",
        )
    private val retryAfter: io.github.smiley4.ktoropenapi.config.ResponseConfig.() -> Unit = {
        header<String>("Retry-After") { description = "Seconds to wait before the next attempt." }
    }
    private val expiredRequest =
        ErrorCase(
            "invalid_request",
            "`request_uri` is missing, unknown, already used, or expired. The person needs to start again in the app.",
            "request_uri is invalid or expired",
        )

    val getPasskeyOptions: RouteConfig.() -> Unit = {
        publicOperation(
            "getAuthorizationPasskeyOptions",
            ApiTags.OAUTH,
            "Get passkey options for the authorization page",
            """
            Called by the browser authorization page, not by third-party clients. It starts a passkey sign-in
            for the pushed request named by `request_uri` and returns the options the page passes to
            `navigator.credentials.get`. When the client's `login_hint` names an account on this server,
            the page names that account, but `allow_credentials` is always empty, so the options reveal nothing about
            which passkeys an account has. The browser offers the person's discoverable passkeys, and **Approve a request
            by signing in with a passkey** refuses one that belongs to another account. User verification is always
            required. The challenge only approves
            this `request_uri`; it cannot be used for an app sign-in or for another request.
            """,
        )
        request {
            body<OAuthPasskeyOptionsRequest> {
                required = true
                example("Example") { value = OAuthPasskeyOptionsRequest(requestUri = REQUEST_URI) }
            }
        }
        response {
            ok(
                "The WebAuthn request options. Byte values are base64url without padding.",
                OAuthPasskeyOptionsResponse(
                    challenge = "example-challenge",
                    rpId = "logdate.app",
                    allowCredentials = listOf("example-credential-id"),
                    timeout = 300_000,
                    userVerification = "required",
                ),
            )
            oauthError(HttpStatusCode.BadRequest, expiredRequest)
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.TooManyRequests, tooManyPasskeyAttempts, headers = retryAfter)
            oauthError(HttpStatusCode.ServiceUnavailable, databaseUnavailable)
            oauthError(HttpStatusCode.NotImplemented, passkeySignInDisabled)
        }
    }

    val approveWithPasskey: RouteConfig.() -> Unit = {
        publicOperation(
            "approveAuthorizationWithPasskey",
            ApiTags.OAUTH,
            "Approve a request by signing in with a passkey",
            """
            Called by the browser authorization page with the passkey assertion from
            `navigator.credentials.get`. The server verifies the assertion against the challenge from **Get
            passkey options for the authorization page**. It then checks that the passkey's account is the one
            the client asked for in `login_hint`, if it named one, and approves the pushed request.

            Signing in is the approval. The answer is the client's `redirect_uri` with `code`, `state` and
            `iss` appended, and the page sends the browser there. A failed or mismatched sign-in leaves the
            request usable, so the person can try another passkey; `error_description` is written for them.
            """,
        )
        request {
            body<OAuthPasskeyApprovalRequest> {
                required = true
                example("Example") {
                    value =
                        OAuthPasskeyApprovalRequest(
                            requestUri = REQUEST_URI,
                            challenge = "example-challenge",
                            credential =
                                PasskeyAssertionResponse(
                                    id = "example-credential-id",
                                    rawId = "example-credential-id",
                                    response =
                                        PasskeyAssertionAuthenticatorResponse(
                                            clientDataJSON = "example-client-data",
                                            authenticatorData = "example-authenticator-data",
                                            signature = "example-signature",
                                            userHandle = "",
                                        ),
                                ),
                        )
                }
            }
        }
        response {
            ok(
                "The passkey signed in the right account and the request is approved. Send the browser to `redirect_uri`.",
                OAuthPasskeyApprovalResponse(
                    redirectUri = "https://journalviewer.example/callback?code=c-7f3c9a1b2d4e5f60&state=af0ifjsldkj&iss=$ISSUER",
                ),
            )
            oauthError(HttpStatusCode.BadRequest, expiredRequest)
            oauthError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "access_denied",
                    "The passkey could not be verified, is not registered to a LogDate account, or the challenge timed " +
                        "out. Show `error_description` and let the person try again.",
                    "This passkey isn't registered to a LogDate account. Choose a passkey you use for LogDate.",
                ),
            )
            oauthError(
                HttpStatusCode.Forbidden,
                ErrorCase(
                    "access_denied",
                    "The passkey belongs to a different account from the one the client named in `login_hint`.",
                    "This passkey is for @willie.logdate.app, but Journal Viewer asked to sign in as @sam.logdate.app. " +
                        "Use the passkey for @sam.logdate.app.",
                ),
            )
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.TooManyRequests, tooManyPasskeyAttempts, headers = retryAfter)
            oauthError(HttpStatusCode.ServiceUnavailable, databaseUnavailable)
            oauthError(HttpStatusCode.NotImplemented, passkeySignInDisabled)
        }
    }

    val exchangeToken: RouteConfig.() -> Unit = {
        publicOperation(
            "exchangeOAuthToken",
            ApiTags.OAUTH,
            "Exchange a code or refresh token",
            """
            Step four. Send `grant_type=authorization_code` with the `code` from the redirect, your
            `redirect_uri`, `client_id` and the PKCE `code_verifier`, and receive a DPoP-bound access token
            and a refresh token. Later, send `grant_type=refresh_token` with `refresh_token` and `client_id`
            to get a new pair.

            Tokens are bound to the key that signed the `DPoP` proof: send them as `Authorization: DPoP <token>`
            together with a fresh proof on every request, and rotate the nonce from each `DPoP-Nonce` header.
            The response is marked `Cache-Control: no-store`.
            """,
        )
        request {
            dpopHeader()
            body<OAuthTokenForm> {
                description = "`application/x-www-form-urlencoded`. Which fields are required depends on `grant_type`."
                required = true
                mediaTypes(ContentType.Application.FormUrlEncoded)
                example("Authorization code") {
                    value =
                        OAuthTokenForm(
                            grantType = "authorization_code",
                            code = "c-7f3c9a1b2d4e5f60",
                            redirectUri = "https://journalviewer.example/callback",
                            clientId = CLIENT_ID,
                            codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                        )
                }
                example("Refresh token") {
                    value = OAuthTokenForm(grantType = "refresh_token", clientId = CLIENT_ID, refreshToken = "rt-9a8b7c6d5e4f")
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Tokens bound to your DPoP key. `sub` is the person's DID."
                header<String>("DPoP-Nonce") { this.description = "The nonce to put in your next DPoP proof." }
                body<OAuthTokenResponse> {
                    example("Example") {
                        value =
                            OAuthTokenResponse(
                                access_token = "at-eyJhbGciOiJFUzI1NiJ9.access-example",
                                token_type = "DPoP",
                                expires_in = 900,
                                refresh_token = "rt-9a8b7c6d5e4f",
                                sub = DocExamples.DID,
                                scope = "atproto",
                            )
                    }
                }
            }
            oauthError(
                HttpStatusCode.BadRequest,
                invalidRequest,
                invalidClient,
                ErrorCase(
                    "invalid_grant",
                    "The code or refresh token is unknown, already used, expired, or issued to another client or key. Start the flow again.",
                    "Authorization code has expired",
                ),
                ErrorCase(
                    "unsupported_grant_type",
                    "`grant_type` is not `authorization_code` or `refresh_token`.",
                    "Unsupported grant_type: password",
                ),
                invalidDpop,
                useDpopNonce,
                headers = dpopNonceHeader,
            )
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.NotImplemented, notConfigured)
        }
    }

    val revokeToken: RouteConfig.() -> Unit = {
        publicOperation(
            "revokeOAuthToken",
            ApiTags.OAUTH,
            "Revoke a refresh token",
            """
            Ends an OAuth session (RFC 7009): send `token` (the refresh token) and `client_id` as a form, with
            a `DPoP` proof, and the refresh token is invalidated. Access tokens already issued expire on their
            own. Call it when the person disconnects your app.
            """,
        )
        request {
            dpopHeader()
            body<OAuthRevokeForm> {
                description =
                    "`application/x-www-form-urlencoded`. Confidential clients add `client_assertion_type` and `client_assertion`."
                required = true
                mediaTypes(ContentType.Application.FormUrlEncoded)
                example("Example") { value = OAuthRevokeForm(token = "rt-9a8b7c6d5e4f", clientId = CLIENT_ID) }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The refresh token is revoked. The body is empty."
                header<String>("DPoP-Nonce") { this.description = "The nonce to put in your next DPoP proof." }
            }
            oauthError(HttpStatusCode.BadRequest, invalidRequest, invalidClient, invalidDpop, useDpopNonce, headers = dpopNonceHeader)
            oauthError(HttpStatusCode.InternalServerError, serverError)
            oauthError(HttpStatusCode.NotImplemented, notConfigured)
        }
    }
}
