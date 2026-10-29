package app.logdate.server.routes.docs

import app.logdate.server.routes.AuthAccountView
import app.logdate.server.routes.AuthResponse
import app.logdate.server.routes.AuthResponseData
import app.logdate.server.routes.ErrorCase
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Values reused across examples so the reference reads as one consistent story: the same
 * account, the same device, timestamps that agree with each other. Tokens are obviously
 * placeholders.
 */
@OptIn(ExperimentalUuidApi::class)
internal object DocExamples {
    const val ACCOUNT_ID = "3f8a1c2e-5b7d-4e9f-a1b2-c3d4e5f60718"
    val accountUuid: Uuid = Uuid.parse(ACCOUNT_ID)
    const val USERNAME = "willie"
    const val DISPLAY_NAME = "Willie"
    const val EMAIL = "willie@example.com"
    const val DID = "did:plc:7iza6de2dwap2sbkpav7c6c6"
    const val HANDLE = "willie.logdate.app"
    const val DEVICE_ID = "pixel-9"

    /** 2026-09-12T14:03:11.412Z, the moment every example happens. */
    const val ISO_NOW = "2026-09-12T14:03:11.412Z"
    const val EPOCH_NOW = 1_789_221_791_412L
    val instantNow: Instant = Instant.parse(ISO_NOW)

    const val ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.access-token-example"
    const val REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.refresh-token-example"
    const val GOOGLE_ID_TOKEN = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.google-id-token-example"

    const val CHALLENGE = "c29tZS1yYW5kb20tY2hhbGxlbmdlLWZyb20tdGhlLXNlcnZlcg"
    const val CREDENTIAL_ID = "AZW7fYi6HGAxk0p4Z2M9Jq"
    const val CLIENT_DATA_JSON = "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiLi4uIn0"
    const val ATTESTATION_OBJECT = "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVik"
    const val AUTHENTICATOR_DATA = "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAA"
    const val SIGNATURE = "MEUCIQDf8kJ5vXk3rH2xY6Q3w1FvZ7YkR2c0mQvXk9fN1sEbKgIgJd4cP0q6X5A"

    val tokens = AccountTokens(accessToken = ACCESS_TOKEN, refreshToken = REFRESH_TOKEN)

    val accountView =
        AuthAccountView(
            id = ACCOUNT_ID,
            username = USERNAME,
            displayName = DISPLAY_NAME,
            did = DID,
            handle = HANDLE,
            bio = "Morning walker, occasional photographer.",
            email = EMAIL,
            emailVerified = true,
            emailVerifiedAt = ISO_NOW,
            linkedProviders = listOf("passkey", "google"),
            passkeyCredentialIds = listOf(CREDENTIAL_ID),
            createdAt = ISO_NOW,
            updatedAt = ISO_NOW,
            storageMode = AccountStorageMode.LEGACY,
            identityKind = app.logdate.shared.model.AccountIdentityKind.HOSTED,
        )

    val authResponse = AuthResponse(success = true, data = AuthResponseData(account = accountView, tokens = tokens))

    val registrationOptions =
        PasskeyRegistrationOptions(
            challenge = CHALLENGE,
            rpId = "logdate.app",
            rpName = "LogDate",
            user = PasskeyUser(id = ACCOUNT_ID, name = USERNAME, displayName = DISPLAY_NAME),
        )

    // ---- Error cases shared by many operations ---------------------------------------------

    val apiInvalidRequest =
        ErrorCase(
            "INVALID_REQUEST",
            "The body is not valid JSON, or a required field is missing or has the wrong type. Fix the request; " +
                "retrying it unchanged will fail the same way.",
            "Invalid request body",
        )
    val apiServerError =
        ErrorCase(
            "SERVER_ERROR",
            "Something failed on the server. Retry with backoff; if it keeps happening, report it with the " +
                "`release` value from `/health`.",
            "Internal server error",
        )
    val apiAccountNotFound =
        ErrorCase(
            "ACCOUNT_NOT_FOUND",
            "The token is valid but its account no longer exists (it was deleted). Sign the person out.",
            "Account not found",
        )
    val googleNotConfigured =
        ErrorCase(
            "GOOGLE_AUTH_NOT_CONFIGURED",
            "This deployment has no Google client IDs configured (`GOOGLE_OIDC_CLIENT_IDS`). Offer passkeys instead, " +
                "or configure Google sign-in on the server.",
            "Google sign-in is not configured on this server",
        )
}
