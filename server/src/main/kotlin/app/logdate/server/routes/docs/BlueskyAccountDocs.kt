package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.apiError
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationBeginResponse
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyExchangeResponse
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationBeginResponse
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import app.logdate.shared.model.PasskeyUser
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode
import java.util.Base64

/** The app-bound handoff is distinct from provider OAuth tokens and local journal-key unlock. */
internal object BlueskyAccountDocs {
    private val attempt = "A".repeat(43)
    private const val APP_CHALLENGE = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private const val CONTINUATION = "example-identity-continuation"
    private const val ENROLLMENT = "example-passkey-continuation"
    private const val USERNAME = "alex_private"
    private const val DISPLAY_NAME = "Alex Example"
    private val userId = Base64.getEncoder().encodeToString(DocExamples.ACCOUNT_ID.toByteArray())
    private val registrationOptions =
        DocExamples.registrationOptions.copy(user = PasskeyUser(userId, USERNAME, DISPLAY_NAME))
    private val authenticationOptions =
        PasskeyAuthenticationOptions(
            challenge = DocExamples.CHALLENGE,
            rpId = "logdate.app",
            allowCredentials = listOf(DocExamples.CREDENTIAL_ID),
        )
    private val externalAccount =
        DocExamples.accountView.copy(
            username = USERNAME,
            displayName = DISPLAY_NAME,
            did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
            handle = "alex.example",
            bio = "A private journal.",
            email = null,
            emailVerified = false,
            emailVerifiedAt = null,
            linkedProviders = listOf("passkey"),
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            identityKind = AccountIdentityKind.EXTERNAL,
        )
    private val session =
        DocExamples.authResponse.copy(
            data = DocExamples.authResponse.data.copy(account = externalAccount, stepUpGrant = null),
        )
    private val registrationCredential =
        PasskeyRegistrationResponse(
            id = DocExamples.CREDENTIAL_ID,
            rawId = DocExamples.CREDENTIAL_ID,
            response = AuthenticatorAttestationResponse(DocExamples.CLIENT_DATA_JSON, DocExamples.ATTESTATION_OBJECT),
        )
    private val authenticationCredential =
        PasskeyAuthenticationResponse(
            id = DocExamples.CREDENTIAL_ID,
            rawId = DocExamples.CREDENTIAL_ID,
            response =
                AuthenticatorAssertionResponse(
                    clientDataJSON =
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                            """{"type":"webauthn.get","challenge":"${DocExamples.CHALLENGE}","origin":"https://logdate.app"}"""
                                .toByteArray(),
                        ),
                    authenticatorData = DocExamples.AUTHENTICATOR_DATA,
                    signature = DocExamples.SIGNATURE,
                    userHandle = userId,
                ),
        )

    val start: RouteConfig.() -> Unit = {
        publicOperation(
            "startBlueskySignin",
            ApiTags.AUTHENTICATION,
            "Begin signing in with a Bluesky identity",
            """
            Generate a private 32-byte app verifier, encoded as unpadded base64url. Send the unpadded base64url
            SHA-256 of that verifier's UTF-8 text as `challenge`. This proof is separate from the provider PKCE
            managed by LogDate's confidential OAuth client. Only `atproto` permission is requested.

            Securely save the returned attempt, original backend and local owner before opening `authorizationUrl`.
            The browser returns only an opaque attempt identifier. Neither this response nor the return URL is
            a LogDate session or journal key. Responses are not cacheable.
            """,
        )
        request { jsonBody(BlueskyStartRequest("alex.example", APP_CHALLENGE), "The handle and originating app's proof challenge.") }
        response {
            ok(
                "Open this server-generated URL only after saving the private app proof.",
                BlueskyStartResponse(attempt, "https://auth.example.com/oauth/authorize?request_uri=example-request"),
            )
            failures(Stage.START)
        }
    }

    val exchange: RouteConfig.() -> Unit = {
        publicOperation(
            "exchangeBlueskyIdentityProof",
            ApiTags.AUTHENTICATION,
            "Exchange a returning Bluesky identity proof",
            """
            Send the returned attempt with the verifier held by the originating app. A successful exchange consumes
            the handoff once and returns a continuation, not provider tokens or a LogDate session. A wrong verifier
            cannot consume the legitimate attempt. A bound provider refusal reports `BLUESKY_CANCELED`.

            Follow `nextStep`: `create_account` needs a new LogDate profile and passkey; `confirm_passkey` needs the
            existing account's passkey. Persist the continuation securely. Browser return must not automatically
            launch either credential ceremony.
            """,
        )
        request { jsonBody(BlueskyExchangeRequest(attempt, attempt), "The public attempt and its separate private verifier.") }
        response {
            ok("A mandatory account/passkey continuation.", BlueskyExchangeResponse(CONTINUATION, BlueskyAccountNextStep.CREATE_ACCOUNT))
            failures(Stage.EXCHANGE)
        }
    }

    val beginRegistration: RouteConfig.() -> Unit = {
        publicOperation(
            "beginBlueskyRegistration",
            ApiTags.AUTHENTICATION,
            "Begin a LogDate account with an external identity",
            """
            Continue only a `create_account` decision. `requestedOwnerId` is the UUID fixed by the owning app's
            journal workflow, not an editable profile field. The returned challenge binds that UUID and profile
            to the verified external DID. Existing identities are never associated by email or handle.

            Securely save the new continuation and registration options before asking the native credential
            provider. `user.id` is standard base64 of the UUID's UTF-8 text. A resumed request keeps that profile;
            changing it requires a fresh sign-in. No hosted DID, PLC publication or public repository is created.
            """,
        )
        request {
            jsonBody(
                BlueskyRegistrationBeginRequest(CONTINUATION, DocExamples.ACCOUNT_ID, USERNAME, DISPLAY_NAME),
                "The verified continuation, fixed owner UUID and profile.",
            )
        }
        response {
            ok("Save these account-bound native registration options.", BlueskyRegistrationBeginResponse(ENROLLMENT, registrationOptions))
            failures(Stage.REGISTRATION_BEGIN)
        }
    }

    val completeRegistration: RouteConfig.() -> Unit = {
        publicOperation(
            "completeBlueskyRegistration",
            ApiTags.AUTHENTICATION,
            "Complete external account passkey setup",
            """
            Verify the native registration against its saved continuation. The server creates the external account
            and verified passkey atomically, then issues LogDate tokens in the ordinary `success/data` envelope.
            This response has `identityKind: external` and `storageMode: personalSpaceV1`, without a hosted
            recovery-key step-up grant. It never contains provider access tokens or a local unlock secret.

            Treat the session as a candidate until the app's guarded journal/key acceptance succeeds. An interrupted
            one-use completion needs a fresh identity handoff; replaying it is not journal recovery.
            """,
        )
        request {
            jsonBody(
                BlueskyRegistrationCompleteRequest(ENROLLMENT, registrationCredential, "Example device"),
                "The native attestation and matching saved continuation; never journal keys or a PRF secret.",
            )
        }
        response {
            ok("LogDate account and tokens; local journal-key acceptance remains mandatory.", session)
            failures(Stage.REGISTRATION_COMPLETE)
        }
    }

    val beginAuthentication: RouteConfig.() -> Unit = {
        publicOperation(
            "beginBlueskyAuthentication",
            ApiTags.AUTHENTICATION,
            "Begin passkey confirmation for Bluesky sign-in",
            """
            Continue a `confirm_passkey` decision for the account selected by the verified external DID. The saved
            continuation is replaced with account-bound authentication options. Persist the replacement before
            presenting the native credential action; provider identity alone cannot open a sealed journal.

            Serializer defaults may be omitted: absent `allowCredentials` means an empty list, and absent `timeout`
            means 300000 milliseconds. Malformed values are not defaults.
            """,
        )
        request { jsonBody(BlueskyAuthenticationBeginRequest(CONTINUATION), "The saved existing-account continuation.") }
        response {
            ok(
                "Save these options and wait for an explicit passkey action.",
                BlueskyAuthenticationBeginResponse(ENROLLMENT, authenticationOptions),
            )
            failures(Stage.AUTHENTICATION_BEGIN)
        }
    }

    val completeAuthentication: RouteConfig.() -> Unit = {
        publicOperation(
            "completeBlueskyAuthentication",
            ApiTags.AUTHENTICATION,
            "Complete Bluesky sign-in with a passkey",
            """
            Confirm the saved account-bound challenge with its native assertion. The passkey must belong to the
            canonical external account; another account's credential cannot substitute for it. The response is
            the ordinary LogDate `success/data` session envelope, without provider tokens or a hosted recovery grant.

            Keep PRF unlock material local. Before publishing this session, recheck the originating backend,
            owner, current enrollment and journal keys. A connection outage or a new DID does not authorize
            replacing another journal or its keys.
            """,
        )
        request {
            jsonBody(
                BlueskyAuthenticationCompleteRequest(ENROLLMENT, authenticationCredential),
                "The native assertion and matching saved continuation.",
            )
        }
        response {
            ok("LogDate account and tokens, pending guarded native journal acceptance.", session)
            failures(Stage.AUTHENTICATION_COMPLETE)
        }
    }

    private enum class Stage { START, EXCHANGE, REGISTRATION_BEGIN, REGISTRATION_COMPLETE, AUTHENTICATION_BEGIN, AUTHENTICATION_COMPLETE }

    private fun ResponsesConfig.failures(stage: Stage) {
        val invalid = mutableListOf(ErrorCase("INVALID_REQUEST", "The body or required input is invalid.", "Request body is invalid"))
        if (stage ==
            Stage.REGISTRATION_BEGIN
        ) {
            invalid += ErrorCase("VALIDATION_ERROR", "The profile is invalid.", "Check the account details")
        }
        if (stage ==
            Stage.EXCHANGE
        ) {
            invalid +=
                ErrorCase("BLUESKY_CANCELED", "The person declined this bound provider attempt.", "Bluesky sign-in was canceled")
        }
        apiError(HttpStatusCode.BadRequest, *invalid.toTypedArray())
        if (stage != Stage.START) {
            val unauthorized =
                mutableListOf(
                    ErrorCase(
                        "BLUESKY_HANDOFF_EXPIRED",
                        "The proof or continuation expired or was already consumed.",
                        "This sign-in request has expired",
                    ),
                )
            if (stage == Stage.REGISTRATION_COMPLETE || stage == Stage.AUTHENTICATION_COMPLETE) {
                unauthorized +=
                    ErrorCase(
                        "PASSKEY_VERIFICATION_FAILED",
                        "The credential did not confirm this account.",
                        "The passkey could not be confirmed",
                    )
            }
            apiError(HttpStatusCode.Unauthorized, *unauthorized.toTypedArray())
            apiError(
                HttpStatusCode.Conflict,
                ErrorCase(
                    "ACCOUNT_LINK_CONFLICT",
                    "The account/identity decision conflicts with existing state.",
                    "This account could not be created or confirmed",
                ),
            )
        }
        code(HttpStatusCode.TooManyRequests) {
            description = "The sign-in request limit was exceeded. Wait for the Retry-After interval before trying again."
            header<Int>("Retry-After") { description = "Seconds to wait before trying again." }
            body<ApiErrorResponse> {
                example("RATE_LIMIT_EXCEEDED") { value = ApiErrorResponse(ApiError("RATE_LIMIT_EXCEEDED", "Too many sign-in requests")) }
            }
        }
        apiError(
            HttpStatusCode.ServiceUnavailable,
            ErrorCase("BLUESKY_UNAVAILABLE", "The provider or required service is unavailable.", "Bluesky sign-in is unavailable"),
        )
    }
}
