package app.logdate.server.openapi.schemadocs

import app.logdate.server.openapi.SchemaDoc

private const val DID_FIELD = "The repository's DID."
private const val SESSION_COMMON_ACCOUNT = "The account's handle."

/** Prose for the OAuth and XRPC (AT Protocol) schemas. */
internal object AtProtoSchemaDocs {
    private val sessionFields =
        mapOf(
            "handle" to SESSION_COMMON_ACCOUNT,
            "did" to "The account's DID.",
            "didDoc" to "The account's DID document, when the server includes it.",
            "email" to "Email on the account, or `null`.",
            "emailConfirmed" to "Whether the email is confirmed, or `null` if unknown.",
            "emailAuthFactor" to "Whether email is used as a second factor, or `null` if unknown.",
            "active" to "Whether the account is active, or `null` if unknown.",
            "status" to "Reason the account is inactive (for example `takendown`), or `null`.",
        )

    val docs: Map<String, SchemaDoc> =
        mapOf(
            // ---- Envelopes ---------------------------------------------------------------------------------
            "PdsErrorResponse" to
                SchemaDoc(
                    "The error envelope every `/xrpc/` method answers with, as defined by AT Protocol.",
                    mapOf(
                        "error" to "The AT Protocol error name, such as `InvalidRequest` or `RecordNotFound`. Branch on this.",
                        "message" to "A sentence for developers. May change; do not parse it.",
                    ),
                ),
            "PlcPublicationStatusResponse" to
                SchemaDoc(
                    "Current username visibility for the account.",
                    mapOf("username" to "The account's username.", "state" to "Private, pending, public, or needs attention."),
                ),
            "PlcPublicationReviewResponse" to
                SchemaDoc(
                    "An immutable review and its passkey options.",
                    mapOf(
                        "reviewId" to "The ID of this review.",
                        "username" to "The exact username being reviewed.",
                        "history" to "Earlier account changes included in the reviewed history.",
                        "passkey" to "Authentication options for this review.",
                    ),
                ),
            "PlcPublicationHistory" to
                SchemaDoc(
                    "The earlier account changes disclosed by this publication.",
                    mapOf(
                        "updates" to "The number of updates after the account identity was created.",
                        "signingChanges" to "How many updates changed the account signing key.",
                        "recoveryChanges" to "How many updates changed the account recovery keys.",
                        "previousUsernames" to "Distinct earlier usernames included in the public history.",
                    ),
                ),
            "PlcPublicationConfirmRequest" to
                SchemaDoc(
                    "A fresh passkey assertion authorizing one saved review.",
                    mapOf(
                        "reviewId" to "The ID returned by the review endpoint.",
                        "challenge" to "The challenge issued for this review.",
                        "credential" to "The passkey assertion.",
                    ),
                ),
            "PlcPublicationAcceptedResponse" to
                SchemaDoc("The review was authorized.", mapOf("state" to "Pending verification by the directory.")),
            "PasskeyAuthenticationOptions" to
                SchemaDoc(
                    "Options for a passkey assertion.",
                    mapOf(
                        "challenge" to "The challenge to sign.",
                        "rpId" to "The relying party identifier.",
                        "allowCredentials" to "Credential identifiers accepted for this review.",
                        "timeout" to "The challenge lifetime in milliseconds.",
                    ),
                ),
            "PasskeyAuthenticationResponse" to
                SchemaDoc(
                    "A passkey assertion from the platform.",
                    mapOf(
                        "id" to "The credential identifier.",
                        "rawId" to "The base64url credential identifier.",
                        "response" to "The signed assertion fields.",
                        "type" to "The credential type.",
                    ),
                ),
            "AuthenticatorAssertionResponse" to
                SchemaDoc(
                    "The signed fields returned by a passkey assertion.",
                    mapOf(
                        "clientDataJSON" to "The encoded client data.",
                        "authenticatorData" to "The encoded authenticator data.",
                        "signature" to "The encoded assertion signature.",
                        "userHandle" to "The optional encoded account handle supplied by the authenticator.",
                    ),
                ),
            "BlueskyPreferences" to
                SchemaDoc(
                    "The private Bluesky settings stored for one account.",
                    mapOf("preferences" to "The ordered list of typed Bluesky preferences."),
                ),
            "ServiceAuthResponse" to
                SchemaDoc(
                    "A credential for a specific service and XRPC method.",
                    mapOf("token" to "The short-lived service JWT to send as a bearer credential."),
                ),
            "OAuthErrorResponse" to
                SchemaDoc(
                    "The RFC 6749 error envelope every `/oauth/` endpoint answers with.",
                    mapOf(
                        "error" to "The OAuth error code, such as `invalid_grant` or `use_dpop_nonce`. Branch on this.",
                        "error_description" to "A sentence for developers. May change; do not parse it.",
                    ),
                ),
            "EmptyPdsResponse" to SchemaDoc("An empty object, returned by methods that have nothing to say on success."),
            "JsonElement" to
                SchemaDoc("Any JSON value. Records are stored and returned exactly as written, so their fields are not typed here."),
            // ---- OAuth -------------------------------------------------------------------------------------
            "AuthorizationServerMetadata" to
                SchemaDoc(
                    "RFC 8414 discovery document for the authorization server.",
                    mapOf(
                        "issuer" to "The authorization server's identifier; tokens carry it as `iss`.",
                        "authorization_endpoint" to "Where to send the person for consent.",
                        "token_endpoint" to "Where to exchange codes and refresh tokens.",
                        "pushed_authorization_request_endpoint" to "Where to push authorization requests (required here).",
                        "revocation_endpoint" to "Where to revoke refresh tokens.",
                        "jwks_uri" to "Where the server's signing keys are published.",
                        "response_types_supported" to "Only `code`.",
                        "grant_types_supported" to "`authorization_code` and `refresh_token`.",
                        "code_challenge_methods_supported" to "Only `S256`; PKCE is required.",
                        "token_endpoint_auth_methods_supported" to "`none` for public clients, `private_key_jwt` for confidential ones.",
                        "token_endpoint_auth_signing_alg_values_supported" to "Algorithms accepted for `private_key_jwt` assertions.",
                        "dpop_signing_alg_values_supported" to "Algorithms accepted for DPoP proofs.",
                        "scopes_supported" to "Only `atproto`.",
                        "authorization_response_iss_parameter_supported" to
                            "Always `true`: redirects carry `iss` so clients can detect mix-up attacks.",
                        "require_pushed_authorization_requests" to "Always `true`: start with **Push an authorization request**.",
                        "require_request_uri_registration" to "Always `true`.",
                        "client_id_metadata_document_supported" to
                            "Always `true`: `client_id` is the URL of your client metadata document.",
                    ),
                ),
            "ProtectedResourceMetadata" to
                SchemaDoc(
                    "RFC 9728 document naming the authorization servers for this resource server.",
                    mapOf(
                        "resource" to "This server's identifier.",
                        "authorization_servers" to "Issuers that mint tokens for it; here, this server.",
                    ),
                ),
            "JsonWebKeySet" to SchemaDoc("A JWK Set (RFC 7517).", mapOf("keys" to "The public keys.")),
            "JsonWebKey" to
                SchemaDoc(
                    "One public elliptic-curve key.",
                    mapOf(
                        "kty" to "Key type; `EC`.",
                        "use" to "`sig`: the key signs tokens.",
                        "key_ops" to "Operations the key is for; `verify`.",
                        "alg" to "Signing algorithm, such as `ES256`.",
                        "kid" to "Key ID. Tokens name the key that signed them by this.",
                        "crv" to "The curve, such as `P-256`.",
                        "x" to "The public point's X coordinate, base64url.",
                        "y" to "The public point's Y coordinate, base64url.",
                    ),
                ),
            "PushedAuthorizationBody" to
                SchemaDoc(
                    "The handle for a pushed authorization request.",
                    mapOf(
                        "request_uri" to "Opaque reference to send as `request_uri` to `/oauth/authorize`.",
                        "expires_in" to "Seconds until the pushed request expires.",
                    ),
                ),
            "AuthorizationPromptResponse" to
                SchemaDoc(
                    "What the consent screen shows about a pending request.",
                    mapOf(
                        "client_id" to "The client's metadata URL.",
                        "client_name" to "The client's display name from its metadata.",
                        "redirect_uri" to "Where the person will be sent after deciding.",
                        "scope" to "What the client asks for; `atproto`.",
                        "state" to "The client's `state`, echoed back on redirect, or `null`.",
                        "login_hint" to "The account the client suggested, or `null`.",
                        "did" to "The signed-in person's DID.",
                        "handle" to "The signed-in person's handle.",
                    ),
                ),
            "OAuthParForm" to
                SchemaDoc(
                    "The form body of **Push an authorization request**.",
                    mapOf(
                        "client_id" to "The URL of your client metadata document.",
                        "redirect_uri" to "Where the person is sent back to after consenting. Must be listed in your client metadata.",
                        "scope" to "`atproto`, plus any transition scopes your client metadata declares.",
                        "response_type" to "Always `code`.",
                        "code_challenge" to
                            "The `S256` hash of the PKCE `code_verifier` you will send to **Exchange a code or refresh token**.",
                        "code_challenge_method" to "Always `S256`.",
                        "state" to "An opaque value echoed back on the redirect so you can match it to this request.",
                        "login_hint" to "The handle or DID the person is expected to sign in as, if you know it.",
                        "client_assertion_type" to "`urn:ietf:params:oauth:client-assertion-type:jwt-bearer` for confidential clients.",
                        "client_assertion" to "The signed client assertion JWT for confidential clients.",
                    ),
                ),
            "OAuthAuthorizationDecisionForm" to
                SchemaDoc(
                    "The form body of **Approve or deny the request**.",
                    mapOf(
                        "request_uri" to "The `request_uri` from **Push an authorization request**.",
                        "decision" to "`approve` or `deny`.",
                    ),
                ),
            "OAuthPasskeyOptionsRequest" to
                SchemaDoc(
                    "Asks for a passkey challenge on the browser authorization page.",
                    mapOf("request_uri" to "The `request_uri` the page was opened with."),
                ),
            "OAuthPasskeyOptionsResponse" to
                SchemaDoc(
                    "The options the authorization page passes to `navigator.credentials.get`.",
                    mapOf(
                        "challenge" to "The challenge to sign, base64url without padding. Send it back with the assertion.",
                        "rp_id" to "The relying-party ID passkeys are registered under.",
                        "allow_credentials" to
                            "Credential IDs of the account the client named in `login_hint`, base64url; empty to allow any passkey.",
                        "timeout" to "How long the browser should wait for the person, in milliseconds.",
                        "user_verification" to "Always `required`.",
                    ),
                ),
            "OAuthPasskeyApprovalRequest" to
                SchemaDoc(
                    "A passkey assertion that signs in and approves a pending authorization request.",
                    mapOf(
                        "request_uri" to "The `request_uri` the page was opened with.",
                        "challenge" to "The challenge from **Get passkey options for the authorization page**.",
                        "credential" to "The assertion `navigator.credentials.get` returned, with byte values in base64url.",
                    ),
                ),
            "OAuthPasskeyApprovalResponse" to
                SchemaDoc(
                    "Where to send the browser after an approved passkey sign-in.",
                    mapOf("redirect_uri" to "The client's `redirect_uri` with `code`, `state` and `iss` appended."),
                ),
            "OAuthRevokeForm" to
                SchemaDoc(
                    "The form body of **Revoke a refresh token**.",
                    mapOf(
                        "token" to "The refresh token to invalidate.",
                        "client_id" to "Your client metadata URL; it must be the client the token was issued to.",
                        "client_assertion_type" to "`urn:ietf:params:oauth:client-assertion-type:jwt-bearer` for confidential clients.",
                        "client_assertion" to "The signed client assertion JWT for confidential clients.",
                    ),
                ),
            "OAuthTokenForm" to
                SchemaDoc(
                    "The form body of **Exchange a code or refresh token**. Which fields are required depends on `grant_type`.",
                    mapOf(
                        "grant_type" to "`authorization_code` or `refresh_token`.",
                        "code" to "The authorization code from the redirect. Required for `authorization_code`.",
                        "redirect_uri" to "The same `redirect_uri` as in the pushed request. Required for `authorization_code`.",
                        "client_id" to "Your client metadata URL. Always required.",
                        "code_verifier" to
                            "The PKCE verifier whose `S256` hash you sent as `code_challenge`. Required for `authorization_code`.",
                        "refresh_token" to "The refresh token to spend. Required for `refresh_token`.",
                        "client_assertion_type" to "`urn:ietf:params:oauth:client-assertion-type:jwt-bearer` for confidential clients.",
                        "client_assertion" to "The signed client assertion JWT for confidential clients.",
                    ),
                ),
            "OAuthTokenResponse" to
                SchemaDoc(
                    "Tokens bound to the client's DPoP key.",
                    mapOf(
                        "access_token" to "Send as `Authorization: DPoP <access_token>` with a DPoP proof.",
                        "token_type" to "Always `DPoP`.",
                        "expires_in" to "Seconds until the access token expires.",
                        "refresh_token" to "Spend it at **Exchange a code or refresh token** with `grant_type=refresh_token`.",
                        "sub" to "The DID the tokens act for.",
                        "scope" to "The granted scope; `atproto`.",
                    ),
                ),
            // ---- XRPC identity and server ----------------------------------------------------------------
            "ResolveHandleResponse" to SchemaDoc("The DID a handle points at.", mapOf("did" to "The DID.")),
            "DescribeServerResponse" to
                SchemaDoc(
                    "What this personal data server offers.",
                    mapOf(
                        "did" to "The server's own DID.",
                        "availableUserDomains" to "Domains handles can be created under.",
                        "inviteCodeRequired" to "Always `false`.",
                        "phoneVerificationRequired" to "Always `false`.",
                    ),
                ),
            "DescribeRepoResponse" to
                SchemaDoc(
                    "A repository's identity and contents.",
                    mapOf(
                        "handle" to "The owner's handle.",
                        "did" to DID_FIELD,
                        "didDoc" to "The owner's DID document.",
                        "collections" to "Lexicon IDs of the collections the repository contains.",
                        "handleIsCorrect" to "Whether the handle resolves back to this DID.",
                    ),
                ),
            // ---- XRPC sessions ------------------------------------------------------------------------------
            "SessionResponse" to
                SchemaDoc(
                    "An AT Protocol session: two tokens plus the account.",
                    mapOf(
                        "accessJwt" to "Send as `Authorization: Bearer` on XRPC calls. Short-lived.",
                        "refreshJwt" to "Send as `Authorization: Bearer` to **Refresh the session** or **End the session**.",
                    ) + sessionFields,
                ),
            "SessionInfoResponse" to SchemaDoc("The account behind a session.", sessionFields),
            // ---- XRPC sync --------------------------------------------------------------------------------------
            "GetLatestCommitResponse" to
                SchemaDoc(
                    "The head of a repository.",
                    mapOf("cid" to "Content hash of the latest commit.", "rev" to "Revision (a TID) of the latest commit."),
                ),
            "GetRepoStatusResponse" to
                SchemaDoc(
                    "Whether a repository is live here.",
                    mapOf(
                        "did" to DID_FIELD,
                        "active" to "`true` when the repository is served normally.",
                        "status" to "Why it is inactive (for example `deactivated`), or `null`.",
                        "rev" to "Current revision, or `null` when inactive.",
                    ),
                ),
            // ---- XRPC records -----------------------------------------------------------------------------------
            "RepoRecord" to
                SchemaDoc(
                    "A record with its address and content hash.",
                    mapOf(
                        "uri" to "The record's `at://` URI: `at://<did>/<collection>/<rkey>`.",
                        "cid" to "Content hash of this version of the record, or `null` if unknown.",
                        "value" to "The record's JSON as stored, including `\$type`.",
                    ),
                ),
            "ListRecordsResponse" to
                SchemaDoc(
                    "A page of records.",
                    mapOf(
                        "records" to "The records in this page.",
                        "cursor" to "Pass back as `cursor` for the next page; absent on the last page.",
                    ),
                ),
            "CreateRecordInput" to
                SchemaDoc(
                    "Body of `com.atproto.repo.createRecord`.",
                    mapOf(
                        "repo" to "Your DID or handle.",
                        "collection" to "Lexicon ID of the collection to write into.",
                        "record" to "The record's JSON, including `\$type`.",
                        "rkey" to "Record key to use, or omit to let the server choose.",
                        "validate" to "Whether to validate against the lexicon; the server currently accepts what it can store.",
                        "swapCommit" to "Only write if the repository's current commit CID equals this; otherwise `InvalidSwap`.",
                    ),
                ),
            "PutRecordInput" to
                SchemaDoc(
                    "Body of `com.atproto.repo.putRecord`.",
                    mapOf(
                        "repo" to "Your DID or handle.",
                        "collection" to "Lexicon ID of the collection to write into.",
                        "rkey" to "Record key to create or replace.",
                        "record" to "The record's JSON, including `\$type`.",
                        "validate" to "Whether to validate against the lexicon; the server currently accepts what it can store.",
                        "swapRecord" to "Only write if the record's current CID equals this; otherwise `InvalidSwap`.",
                        "swapCommit" to "Only write if the repository's current commit CID equals this; otherwise `InvalidSwap`.",
                    ),
                ),
            "DeleteRecordInput" to
                SchemaDoc(
                    "Body of `com.atproto.repo.deleteRecord`.",
                    mapOf(
                        "repo" to "Your DID or handle.",
                        "collection" to "Lexicon ID of the collection.",
                        "rkey" to "Record key to delete.",
                        "swapRecord" to "Only delete if the record's current CID equals this; otherwise `InvalidSwap`.",
                        "swapCommit" to "Only delete if the repository's current commit CID equals this; otherwise `InvalidSwap`.",
                    ),
                ),
            "RepoWriteResult" to
                SchemaDoc(
                    "What a write produced.",
                    mapOf(
                        "uri" to "The record's `at://` URI.",
                        "cid" to "Content hash of the written record.",
                        "validationStatus" to "Whether the record was validated against its lexicon.",
                    ),
                ),
            "RepoValidationStatus" to
                SchemaDoc(
                    "How much lexicon validation a write received.",
                    enumValues =
                        mapOf(
                            "valid" to "The record shape was checked and accepted.",
                            "unknown" to "The record was stored without full validation.",
                        ),
                ),
            // ---- XRPC blobs ----------------------------------------------------------------------------------------
            "UploadBlobResponse" to
                SchemaDoc("The stored blob's reference.", mapOf("blob" to "Embed this object in a record to keep the blob.")),
            "BlobRef" to
                SchemaDoc(
                    "A reference to a blob, as embedded in records.",
                    mapOf(
                        "\$type" to "Always `blob`.",
                        "ref" to "The blob's CID as a link.",
                        "mimeType" to "The blob's media type.",
                        "size" to "The blob's size in bytes.",
                    ),
                ),
            "CidLink" to SchemaDoc("A CID wrapped as an IPLD link.", mapOf("\$link" to "The CID.")),
        )
}
