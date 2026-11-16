package app.logdate.server.openapi.schemadocs

import app.logdate.server.openapi.SchemaDoc

private const val ISO_TIME = "ISO-8601 timestamp in UTC, for example `2026-09-12T14:03:11.412Z`."
private const val SUCCESS_FLAG = "Always `true` on a successful response. Errors use the error envelope instead."
private const val BASE64URL = "Base64url-encoded bytes, exactly as the browser or platform credential API produced them."

/** Prose for the authentication, account and identity schemas. */
internal object AuthSchemaDocs {
    val docs: Map<String, SchemaDoc> =
        mapOf(
            // ---- Envelopes -----------------------------------------------------------------
            "ApiErrorResponse" to
                SchemaDoc(
                    "The error envelope used by the Authentication, Account and Identity endpoints.",
                    mapOf("error" to "What went wrong."),
                ),
            "ApiError" to
                SchemaDoc(
                    "A machine-readable error code and a human-readable explanation.",
                    mapOf(
                        "code" to "Stable, upper-snake-case code to branch on, such as `INVALID_TOKEN`. Never localized.",
                        "message" to "A sentence for logs and developers. May change between releases; do not parse it.",
                    ),
                ),
            "OkResponse" to SchemaDoc("A bare acknowledgement.", mapOf("ok" to "Always `true`.")),
            "SuccessResponse" to SchemaDoc("A bare acknowledgement.", mapOf("success" to "Always `true`.")),
            // ---- Tokens and accounts --------------------------------------------------------
            "AccountTokens" to
                SchemaDoc(
                    "The pair of tokens a sign-in produces. Store both; see **Concepts** in the overview.",
                    mapOf(
                        "accessToken" to "Short-lived JWT to send as `Authorization: Bearer <accessToken>` on every request.",
                        "refreshToken" to "Longer-lived JWT used only with **Refresh the access token** and **Log out**. Keep it secret.",
                    ),
                ),
            "AuthResponse" to
                SchemaDoc(
                    "What every sign-in, sign-up and restore call returns: the account plus fresh tokens.",
                    mapOf("success" to SUCCESS_FLAG, "data" to "The account and its tokens."),
                ),
            "AuthResponseData" to
                SchemaDoc(
                    "The account together with a new pair of tokens.",
                    mapOf(
                        "account" to "The signed-in account.",
                        "tokens" to "New access and refresh tokens. Replace the ones you stored.",
                        "stepUpGrant" to
                            "Sent after a passkey sign-in or sign-up on a server with recovery keys. Proof, for five minutes and " +
                            "one use, that the person just confirmed with a passkey; send it to **Register a recovery key** " +
                            "in place of `stepUp`.",
                    ),
                ),
            "AuthAccountView" to
                SchemaDoc(
                    "An account as the authentication endpoints present it.",
                    mapOf(
                        "id" to "The account ID, a UUID. It equals the `requestedOwnerId` the app supplied at sign-up.",
                        "username" to "Unique, case-sensitive handle: 3–50 letters, digits and underscores.",
                        "displayName" to "Name shown in the app, 1–100 characters.",
                        "did" to "The account's AT Protocol identifier, or `null` when this deployment does not host identities.",
                        "handle" to "The account's AT Protocol handle (for example `alice.logdate.app`), or `null`.",
                        "bio" to "Free-text bio, or `null`.",
                        "originalBio" to "The person’s original bio text before processing, or `null`.",
                        "email" to "Email attached to the account, or `null` if none.",
                        "emailVerified" to "`true` once the email has been verified through Google or a digital credential.",
                        "emailVerifiedAt" to "When the email was verified, as an $ISO_TIME `null` if unverified.",
                        "linkedProviders" to "Which sign-in methods are linked: any of `passkey`, `google`.",
                        "passkeyCredentialIds" to "The `credentialId` of every active passkey.",
                        "createdAt" to "When the account was created. $ISO_TIME",
                        "updatedAt" to "When the account last signed in, or when it was created if it never has. $ISO_TIME",
                        "storageMode" to
                            "How the server keeps this account's journal data: `legacy` (one sync endpoint for each kind of " +
                            "data) or `personalSpaceV1` (sealed records in the account's personal space). Every device of the " +
                            "account syncs the way this says. A server that predates storage modes leaves it out, which " +
                            "means `legacy`; an app that does not recognize the value must not sync the account.",
                    ),
                ),
            "LogDateAccount" to
                SchemaDoc(
                    "An account as the profile endpoint presents it.",
                    mapOf(
                        "id" to "The account ID, a UUID.",
                        "username" to "Unique, case-sensitive handle: 3–50 letters, digits and underscores.",
                        "displayName" to "Name shown in the app, 1–100 characters.",
                        "did" to "The account's AT Protocol identifier, or `null` when this deployment does not host identities.",
                        "handle" to "The account's AT Protocol handle, or `null`.",
                        "bio" to "Free-text bio, or `null`.",
                        "originalBio" to "The person’s original bio text before processing, or `null`.",
                        "passkeyCredentialIds" to "The `credentialId` of every active passkey.",
                        "createdAt" to "When the account was created. $ISO_TIME",
                        "updatedAt" to "When the account last signed in, or when it was created if it never has. $ISO_TIME",
                        "email" to "Email attached to the account, or `null` if none.",
                        "emailVerified" to "`true` once the email has been verified.",
                        "emailVerifiedAt" to "When the email was verified, or `null`. $ISO_TIME",
                        "storageMode" to
                            "How the server keeps this account's journal data: `legacy` (one sync endpoint for each kind of " +
                            "data) or `personalSpaceV1` (sealed records in the account's personal space). Every device of the " +
                            "account syncs the way this says. A server that predates storage modes leaves it out, which " +
                            "means `legacy`; an app that does not recognize the value must not sync the account.",
                    ),
                ),
            "AccountInfoResponse" to
                SchemaDoc("The account after a profile update.", mapOf("success" to SUCCESS_FLAG, "data" to "The updated account.")),
            "UpdateAccountProfileRequest" to
                SchemaDoc(
                    "Fields to change on the profile. Omit what should stay the same; at least one must be present.",
                    mapOf(
                        "displayName" to "New display name, 1–100 characters.",
                        "username" to "New username, 3–50 letters, digits and underscores, not reserved.",
                        "bio" to "New bio.",
                        "originalBio" to "Original bio text as entered by the person.",
                    ),
                ),
            "UsernameAvailabilityResponse" to
                SchemaDoc("Whether a username can still be taken.", mapOf("success" to SUCCESS_FLAG, "data" to "The verdict.")),
            "UsernameAvailabilityData" to
                SchemaDoc(
                    "The verdict for one username.",
                    mapOf("username" to "The username that was checked, trimmed.", "available" to "`true` when nobody has it yet."),
                ),
            // ---- Passkey sign-up ------------------------------------------------------------
            "SignupPasskeyBeginRequest" to
                SchemaDoc(
                    "Who is signing up with a passkey.",
                    mapOf(
                        "username" to "Desired username: 3–50 letters, digits and underscores, not a reserved word.",
                        "displayName" to "Name to show in the app, 1–100 characters.",
                        "bio" to "Optional free-text bio.",
                        "originalBio" to "Original bio text before processing, or `null`.",
                        "requestedOwnerId" to "UUID the app generated once per install. Becomes the account ID; required.",
                    ),
                ),
            "SignupPasskeyBeginResponse" to
                SchemaDoc(
                    "What you need for the second sign-up step.",
                    mapOf(
                        "success" to SUCCESS_FLAG,
                        "data" to "Session token and registration options.",
                    ),
                ),
            "SignupPasskeyBeginData" to
                SchemaDoc(
                    "Session token and WebAuthn registration options.",
                    mapOf(
                        "sessionToken" to "Opaque, short-lived token to send back with **Complete passkey sign-up**.",
                        "registrationOptions" to "Pass these to the platform's credential-creation API unchanged.",
                    ),
                ),
            "PasskeyRegistrationOptions" to
                SchemaDoc(
                    "WebAuthn `PublicKeyCredentialCreationOptions`, ready to hand to the platform.",
                    mapOf(
                        "challenge" to "Random bytes the authenticator must sign, base64url.",
                        "rpId" to "The relying party ID (a domain). The credential is bound to it.",
                        "rpName" to "Human-readable relying party name shown by the platform.",
                        "user" to "The user record the passkey is created for.",
                        "pubKeyCredParams" to "Key algorithms the server accepts, in preference order (COSE identifiers).",
                        "excludeCredentials" to "Credential IDs that already exist, so the platform does not create a duplicate.",
                        "timeout" to "How long the platform should wait for the person, in milliseconds.",
                    ),
                ),
            "PasskeyUser" to
                SchemaDoc(
                    "The WebAuthn user entity.",
                    mapOf(
                        "id" to "Stable user handle the authenticator stores; here the account ID.",
                        "name" to "The username.",
                        "displayName" to "The display name.",
                    ),
                ),
            "PublicKeyCredentialParameter" to
                SchemaDoc(
                    "One acceptable key algorithm.",
                    mapOf("type" to "Always `public-key`.", "alg" to "COSE algorithm identifier: `-7` is ES256, `-257` is RS256."),
                ),
            "SignupPasskeyCompleteRequest" to
                SchemaDoc(
                    "The credential the device created, plus the session it belongs to.",
                    mapOf(
                        "sessionToken" to "The token from **Begin passkey sign-up**.",
                        "credential" to "The credential the platform returned, unchanged.",
                        "emailBinding" to "Optional proof of an email address to attach, currently a Google ID token.",
                        "nickname" to
                            "Optional name of the device the passkey was created on, shown in the passkey " +
                            "list. Trimmed and cut to 100 characters; blank keeps the server's default name.",
                    ),
                ),
            "EmailBindingRequest" to
                SchemaDoc(
                    "A proof of email ownership attached during passkey sign-up.",
                    mapOf(
                        "source" to "Where the proof comes from. Only `google_id_token` is supported.",
                        "value" to "The proof itself: a Google ID token.",
                        "nonce" to "The nonce used when requesting the ID token, if any.",
                    ),
                ),
            "PasskeyCredentialResponse" to
                SchemaDoc(
                    "A newly created passkey, as returned by the platform's credential-creation API.",
                    mapOf(
                        "id" to "The credential ID, base64url.",
                        "rawId" to "The same credential ID, base64url.",
                        "response" to "The attestation the authenticator produced.",
                        "type" to "Always `public-key`.",
                    ),
                ),
            "PasskeyAuthenticatorResponse" to
                SchemaDoc(
                    "The attestation half of a created credential.",
                    mapOf(
                        "clientDataJSON" to "The client data the browser or platform signed over. $BASE64URL",
                        "attestationObject" to "The CBOR attestation object with the new public key. $BASE64URL",
                    ),
                ),
            // ---- Passkey sign-in ------------------------------------------------------------
            "SigninPasskeyBeginRequest" to
                SchemaDoc(
                    "Optionally names the account that is signing in.",
                    mapOf(
                        "username" to "Username of the account so `allowCredentials` can list its passkeys; omit for discoverable sign-in.",
                    ),
                ),
            "SigninPasskeyBeginResponse" to
                SchemaDoc("A challenge to sign.", mapOf("success" to SUCCESS_FLAG, "data" to "The WebAuthn request options.")),
            "SigninPasskeyBeginData" to
                SchemaDoc(
                    "WebAuthn `PublicKeyCredentialRequestOptions`, ready to hand to the platform.",
                    mapOf(
                        "challenge" to "Random bytes the authenticator must sign, base64url. Send it back with the assertion.",
                        "rpId" to "The relying party ID the credential must be bound to.",
                        "allowCredentials" to "Passkeys the platform may use. Empty means any passkey for this relying party.",
                        "timeout" to "How long the platform should wait for the person, in milliseconds.",
                        "userVerification" to "`required` for normal sign-in; `discouraged` for restore sign-in.",
                    ),
                ),
            "PasskeyAllowCredentialDto" to
                SchemaDoc(
                    "One passkey the platform may use.",
                    mapOf(
                        "type" to "Always `public-key`.",
                        "id" to "The credential ID, base64url.",
                        "transports" to "Hints such as `internal` or `hybrid`; may be empty.",
                    ),
                ),
            "SigninPasskeyCompleteRequest" to
                SchemaDoc(
                    "The signed assertion and the challenge it answers.",
                    mapOf(
                        "credential" to "The assertion the platform returned, unchanged.",
                        "challenge" to "The challenge from the begin step.",
                    ),
                ),
            "PasskeyAssertionResponse" to
                SchemaDoc(
                    "A passkey assertion, as returned by the platform's credential-request API.",
                    mapOf(
                        "id" to "The credential ID, base64url.",
                        "rawId" to "The same credential ID, base64url.",
                        "response" to "The signature and the data it covers.",
                        "type" to "Always `public-key`.",
                    ),
                ),
            "PasskeyAssertionAuthenticatorResponse" to
                SchemaDoc(
                    "The signature half of an assertion.",
                    mapOf(
                        "clientDataJSON" to "The client data the platform signed over. $BASE64URL",
                        "authenticatorData" to "Authenticator flags and counter. $BASE64URL",
                        "signature" to "The signature over `authenticatorData` and the client data hash. $BASE64URL",
                        "userHandle" to "The user handle the passkey stored; here the account ID.",
                    ),
                ),
            // ---- Google -----------------------------------------------------------------------
            "GoogleAuthRequest" to
                SchemaDoc(
                    "A Google ID token and, for sign-up, optional profile fields.",
                    mapOf(
                        "idToken" to
                            "The ID token Google issued to your app. Its audience must be one of this deployment's allowed client IDs.",
                        "username" to "Sign-up only: desired username. Derived from the Google profile when omitted.",
                        "displayName" to "Sign-up only: desired display name. Derived from the Google profile when omitted.",
                        "nonce" to "The nonce you put in the token request, if you used one; the server checks it matches.",
                        "requestedOwnerId" to
                            "Sign-up only: the app's per-install UUID, which becomes the account ID. Required for sign-up.",
                    ),
                ),
            // ---- Restore credential ---------------------------------------------------------
            "RestoreRegisterBeginResponse" to
                SchemaDoc(
                    "Registration options for a new passkey or restore credential.",
                    mapOf(
                        "success" to SUCCESS_FLAG,
                        "data" to "Pass to the platform unchanged.",
                    ),
                ),
            "RestoreRegisterCompleteRequest" to
                SchemaDoc(
                    "A created restore credential.",
                    mapOf(
                        "credentialJson" to "The credential object the platform returned, serialized to a JSON string.",
                        "challenge" to "The challenge from the begin step.",
                    ),
                ),
            // ---- Tokens ---------------------------------------------------------------------
            "RefreshTokenRequestV1" to
                SchemaDoc(
                    "A refresh token to exchange.",
                    mapOf("refreshToken" to "The refresh token from sign-in."),
                ),
            "RefreshTokenResponseV1" to SchemaDoc("A new access token.", mapOf("success" to SUCCESS_FLAG, "data" to "The token.")),
            "RefreshTokenDataV1" to
                SchemaDoc(
                    "The new access token, and for a linked device whose session was just renewed, a new refresh token.",
                    mapOf(
                        "accessToken" to "Send as `Authorization: Bearer <accessToken>`.",
                        "refreshToken" to "Present only when a linked device's session was renewed. Replace the stored refresh token.",
                    ),
                ),
            "LogoutRequestV1" to SchemaDoc("A refresh token to revoke.", mapOf("refreshToken" to "The refresh token to invalidate.")),
            // ---- Passkey management ---------------------------------------------------------
            "PasskeyListResponse" to SchemaDoc("The account's passkeys.", mapOf("success" to SUCCESS_FLAG, "data" to "Active passkeys.")),
            "PasskeyResponse" to SchemaDoc("One passkey.", mapOf("success" to SUCCESS_FLAG, "data" to "The passkey.")),
            "PasskeyInfo" to
                SchemaDoc(
                    "A stored passkey, without any key material.",
                    mapOf(
                        "id" to "Internal record ID, a UUID.",
                        "credentialId" to "The WebAuthn credential ID; use it to remove the passkey.",
                        "nickname" to "Name the person gave the passkey, or `null`.",
                        "deviceType" to "`platform` for a built-in authenticator (phone, laptop), `cross-platform` for a security key.",
                        "createdAt" to "When it was registered. $ISO_TIME",
                        "lastUsedAt" to "When it last signed in, or `null` if never. $ISO_TIME",
                        "isActive" to "`false` once removed. Listings only include active passkeys.",
                    ),
                ),
            "AddPasskeyCompleteRequest" to
                SchemaDoc(
                    "A created passkey to add to the account.",
                    mapOf(
                        "challenge" to "The challenge from **Begin adding a passkey**.",
                        "credential" to "The credential the platform returned, unchanged.",
                        "nickname" to
                            "Optional name of the device the passkey was created on, shown in the passkey " +
                            "list. Trimmed and cut to 100 characters; blank keeps the server's default name.",
                    ),
                ),
            // ---- Email verification ----------------------------------------------------------
            "BeginEmailVerificationResponse" to
                SchemaDoc(
                    "Everything the wallet needs to issue a credential for this attempt.",
                    mapOf(
                        "transactionId" to "Opaque ID of this attempt; echo it on **Complete email verification**.",
                        "nonce" to "Base64url nonce to embed in the credential request so the credential cannot be replayed.",
                        "audience" to "The audience the key-binding JWT must declare. Published by the server; do not hard-code it.",
                    ),
                ),
            "CompleteEmailVerificationRequest" to
                SchemaDoc(
                    "The wallet's answer for one verification attempt.",
                    mapOf(
                        "transactionId" to "The ID from the begin step.",
                        "credentialJson" to "The raw `credentialJson` the platform returned, unchanged.",
                    ),
                ),
            "EmailVerifiedResponse" to
                SchemaDoc(
                    "Confirmation that an email is now verified.",
                    mapOf("email" to "The verified address, lower-cased.", "emailVerifiedAt" to "When it was verified. $ISO_TIME"),
                ),
            "EmailVerificationConflictResponse" to
                SchemaDoc(
                    "Answered when the email is already verified on another account.",
                    mapOf("code" to "Always `email_already_attached`.", "message" to "A sentence to show the person."),
                ),
            "EmailVerificationErrorResponse" to
                SchemaDoc(
                    "Answered when the credential did not verify.",
                    mapOf("reason" to "Stable reason code from the verifier, such as `nonce_mismatch` or `credential_expired`."),
                ),
            // ---- Entitlement ------------------------------------------------------------------
            "EntitlementResponse" to
                SchemaDoc(
                    "The account's plan and the limits that come with it.",
                    mapOf(
                        "planId" to
                            "Stable public plan identifier such as `cloud`, `cloud_plus` or `self_host_unlimited`; `none` when the account has no plan.",
                        "tier" to "Coarse tier for UI copy.",
                        "status" to "Whether the subscription is currently in good standing.",
                        "storageBytesLimit" to "Storage the plan includes, in bytes, or `null` for unlimited.",
                        "backupCountLimit" to "Maximum stored backups, or `null` for unlimited.",
                        "transcriptionSecondsPerMonthLimit" to
                            "Cloud transcription allowance per month in seconds, or `null` for unlimited.",
                        "features" to "Feature flags by name, `true` when the plan includes the feature.",
                        "extraStorageStepBytes" to
                            "The size of one step of extra storage the plan sells past `storageBytesLimit`, or `null` when it stops there.",
                        "includedWithBeta" to "The account has this plan at no charge because it joined during the beta.",
                    ),
                ),
            "EntitlementTierWire" to
                SchemaDoc(
                    "Coarse plan tier.",
                    enumValues =
                        mapOf(
                            "FREE" to "No plan.",
                            "STANDARD" to "LogDate Cloud, the base plan.",
                            "PRO" to "LogDate Cloud Plus, the larger plan.",
                            "UNLIMITED" to "No limits; used by self-hosted servers.",
                        ),
                ),
            "EntitlementStatusWire" to
                SchemaDoc(
                    "Subscription state.",
                    enumValues =
                        mapOf(
                            "ACTIVE" to "Paid and current.",
                            "PAST_DUE" to
                                "A payment failed but the grace window is still open; treat as active and prompt the person to update payment.",
                            "GRACE" to "The store's own grace period (for example a Play hold); treat as active.",
                            "CANCELLED" to "There is no plan. Reads keep working; uploads fail with `402`.",
                            "SELF_HOST" to "This server runs without billing. Hide billing UI and treat the account as unlimited.",
                        ),
                ),
            // ---- Identities ---------------------------------------------------------------------
            "IdentityListResponse" to
                SchemaDoc(
                    "The account's linked sign-in identities.",
                    mapOf("success" to SUCCESS_FLAG, "data" to "One entry per identity."),
                ),
            "IdentityView" to
                SchemaDoc(
                    "One way the person can sign in.",
                    mapOf(
                        "provider" to "`passkey` or `google`.",
                        "providerSubject" to
                            "The provider's stable ID for this identity: a credential ID for passkeys, Google's subject for Google.",
                        "email" to "Email the identity carries, or `null`.",
                        "emailVerified" to "Whether that email was verified by the provider.",
                        "createdAt" to "When the identity was linked. $ISO_TIME",
                        "lastSignInAt" to "When it last signed in, or `null` if never. $ISO_TIME",
                    ),
                ),
            // ---- AT Protocol identity ---------------------------------------------------------
            "IdentityStatusResponse" to
                SchemaDoc("The account's AT Protocol identity.", mapOf("success" to SUCCESS_FLAG, "data" to "The identity.")),
            "IdentityStatusData" to
                SchemaDoc(
                    "An AT Protocol identity and the public half of its keys.",
                    mapOf(
                        "did" to "The decentralized identifier, `did:plc:…` or `did:web:…`.",
                        "handle" to "The human-readable handle that resolves to the DID.",
                        "signingKeyPublicMultibase" to "The current signing key's public half, multibase-encoded.",
                        "signingKeyDidKey" to "The same public key as a `did:key`.",
                        "plcRecoveryDidKey" to "The registered PLC recovery key as a `did:key`, or `null` if none.",
                        "plcOperationCount" to "How many PLC operations this server has published for the identity.",
                    ),
                ),
            "ExportSigningKeyRequest" to
                SchemaDoc(
                    "Asks for an encrypted export.",
                    mapOf("passphrase" to "Passphrase to encrypt the private key with. Not stored."),
                ),
            "ExportSigningKeyResponse" to
                SchemaDoc("An encrypted signing-key export.", mapOf("success" to SUCCESS_FLAG, "data" to "The export.")),
            "ExportSigningKeyData" to
                SchemaDoc(
                    "The identity the export belongs to and the export itself.",
                    mapOf("did" to "The identity's DID.", "handle" to "The identity's handle.", "exportedKey" to "The encrypted key."),
                ),
            "SigningKeyService.ExportedSigningKey" to
                SchemaDoc(
                    "A private signing key encrypted under a passphrase. Safe to store; useless without the passphrase.",
                    mapOf(
                        "algorithm" to "The key's curve, such as `secp256k1`.",
                        "publicKeyMultibase" to "The public half, multibase-encoded.",
                        "publicKeyDidKey" to "The public half as a `did:key`.",
                        "encryptedPrivateKey" to "The private key encrypted with AES-GCM, base64.",
                        "salt" to "Salt used to derive the encryption key from the passphrase, base64.",
                        "iv" to "AES-GCM initialization vector, base64.",
                        "kdf" to "Key-derivation function used, `PBKDF2WithHmacSHA256`.",
                        "iterations" to "PBKDF2 iteration count.",
                    ),
                ),
            "RotateSigningKeyRequest" to
                SchemaDoc("Asks for a rotation.", mapOf("passphrase" to "Passphrase to encrypt the new key's export with. Not stored.")),
            "RotateSigningKeyResponse" to
                SchemaDoc("The result of a rotation.", mapOf("success" to SUCCESS_FLAG, "data" to "The new key and what it replaced.")),
            "RotateSigningKeyData" to
                SchemaDoc(
                    "The new key's export and the key it replaced.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "previousPublicKeyDidKey" to "The public key that was in use before the rotation, as a `did:key`.",
                        "exportedKey" to "The new key, encrypted under the passphrase.",
                    ),
                ),
            "ImportSigningKeyRequest" to
                SchemaDoc(
                    "An export to install.",
                    mapOf(
                        "passphrase" to "The passphrase the export was encrypted with.",
                        "exportedKey" to "The export from **Export the signing key**.",
                    ),
                ),
            "ImportSigningKeyResponse" to
                SchemaDoc("The result of an import.", mapOf("success" to SUCCESS_FLAG, "data" to "The installed key.")),
            "ImportSigningKeyData" to
                SchemaDoc(
                    "The identity and the key now in use.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "publicKeyDidKey" to "The installed public key as a `did:key`.",
                    ),
                ),
            "PrepareRecoverySigningKeyImportRequest" to
                SchemaDoc(
                    "An export to install through the recovery flow.",
                    mapOf("passphrase" to "The passphrase the export was encrypted with.", "exportedKey" to "The export to install."),
                ),
            "PrepareRecoverySigningKeyImportResponse" to
                SchemaDoc("The operation to sign.", mapOf("success" to SUCCESS_FLAG, "data" to "What to sign, and with which key.")),
            "PrepareRecoverySigningKeyImportData" to
                SchemaDoc(
                    "An unsigned PLC operation and the bytes the recovery key must sign.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "recoveryDidKey" to "The registered recovery key that must produce the signature.",
                        "nextPublicKeyDidKey" to "The public key the operation will install.",
                        "unsignedOperationJson" to "The PLC operation as JSON, for inspection.",
                        "signingPayloadBase64Url" to "The exact bytes to sign with the recovery key, base64url.",
                    ),
                ),
            "CompleteRecoverySigningKeyImportRequest" to
                SchemaDoc(
                    "The signed recovery import.",
                    mapOf(
                        "passphrase" to "The same passphrase as in the prepare step.",
                        "exportedKey" to "The same export as in the prepare step.",
                        "signature" to "The recovery key's signature over `signingPayloadBase64Url`, base64url.",
                    ),
                ),
            "HostedPlcOperationsResponse" to
                SchemaDoc("The identity's published PLC history.", mapOf("success" to SUCCESS_FLAG, "data" to "Operations, oldest first.")),
            "HostedPlcOperationData" to
                SchemaDoc(
                    "One PLC operation this server published.",
                    mapOf(
                        "did" to "The identity the operation belongs to.",
                        "cid" to "Content identifier of the operation in the PLC directory, or `null` if not yet known.",
                        "prevCid" to "The operation this one builds on, or `null` for the first.",
                        "operationType" to "`plc_operation` for creations and key changes; `plc_tombstone` for deactivation.",
                        "operationJson" to "The operation as published, JSON-encoded.",
                        "createdAt" to "When the server published it. $ISO_TIME",
                    ),
                ),
            // ---- DID documents ---------------------------------------------------------------
            "DidDocument" to
                SchemaDoc(
                    "A W3C DID document: the public keys and services that speak for an identity.",
                    mapOf(
                        "@context" to "JSON-LD contexts; always includes `https://www.w3.org/ns/did/v1`.",
                        "id" to "The DID this document describes.",
                        "alsoKnownAs" to "Other names for the identity, such as `at://alice.logdate.app`.",
                        "verificationMethod" to "Public keys that can sign for the identity.",
                        "service" to "Services acting for the identity, such as its personal data server.",
                    ),
                ),
            "VerificationMethod" to
                SchemaDoc(
                    "One public key of a DID.",
                    mapOf(
                        "id" to "Key identifier, the DID plus a fragment such as `#atproto`.",
                        "type" to "Key format; `Multikey` for AT Protocol.",
                        "controller" to "The DID that controls the key.",
                        "publicKeyMultibase" to "The public key, multibase-encoded.",
                    ),
                ),
            "Service" to
                SchemaDoc(
                    "One service endpoint of a DID.",
                    mapOf(
                        "id" to "Service identifier, such as `#atproto_pds`.",
                        "type" to "Service type; `AtprotoPersonalDataServer` for the data server.",
                        "serviceEndpoint" to "The service's base URL.",
                    ),
                ),
        )
}
