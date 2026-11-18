package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.LogoutRequestV1
import app.logdate.server.routes.OkResponse
import app.logdate.server.routes.RefreshTokenDataV1
import app.logdate.server.routes.RefreshTokenRequestV1
import app.logdate.server.routes.RefreshTokenResponseV1
import app.logdate.server.routes.RestoreRegisterBeginResponse
import app.logdate.server.routes.RestoreRegisterCompleteRequest
import app.logdate.server.routes.SigninPasskeyBeginData
import app.logdate.server.routes.SigninPasskeyBeginResponse
import app.logdate.server.routes.SigninPasskeyCompleteRequest
import app.logdate.server.routes.SuccessResponse
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

/** Documentation for the restore-credential, token refresh and logout endpoints in `AuthV1Routes.kt`. */
internal object AuthSessionDocs {
    val beginRestoreCredentialRegistration: RouteConfig.() -> Unit = {
        bearerOperation(
            "beginRestoreCredentialRegistration",
            ApiTags.AUTHENTICATION,
            "Begin restore credential setup",
            """
            A restore credential is a special passkey the app registers while a person is signed in, kept by the
            platform's credential manager, and used later to regain access to the account when every other device
            is gone. This call returns the registration options for creating one; it works exactly like
            **Begin passkey sign-up** except that the account already exists and you are authenticated.

            Pass `data` to the credential API, then send the result to **Complete restore credential setup**.
            """,
        )
        response {
            ok(
                "Registration options for the restore credential.",
                RestoreRegisterBeginResponse(success = true, data = DocExamples.registrationOptions),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
            apiServerError()
        }
    }

    val completeRestoreCredentialRegistration: RouteConfig.() -> Unit = {
        bearerOperation(
            "completeRestoreCredentialRegistration",
            ApiTags.AUTHENTICATION,
            "Complete restore credential setup",
            """
            Verifies and stores the restore credential created from **Begin restore credential setup**.
            `credentialJson` is a JSON string with only `id`, `rawId`, `type` and `response.clientDataJSON` /
            `response.attestationObject`, the same fields as the `credential` object of **Complete passkey
            sign-up**. Unlike that endpoint, this one rejects any other key, so strip `authenticatorAttachment`,
            `clientExtensionResults`, `response.transports` and the like before sending, or the answer is
            `400 INVALID_REQUEST`.
            """,
        )
        request {
            jsonBody(
                RestoreRegisterCompleteRequest(
                    credentialJson =
                        """{"id":"${DocExamples.CREDENTIAL_ID}","rawId":"${DocExamples.CREDENTIAL_ID}","type":"public-key",""" +
                            """"response":{"clientDataJSON":"${DocExamples.CLIENT_DATA_JSON}","attestationObject":"${DocExamples.ATTESTATION_OBJECT}"}}""",
                    challenge = DocExamples.CHALLENGE,
                ),
                "The credential as a JSON string, and the challenge it was created for.",
            )
        }
        response {
            ok("The restore credential is stored.", SuccessResponse(success = true))
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "RESTORE_KEY_REGISTRATION_FAILED",
                    "The credential did not verify against the challenge. Start again from **Begin restore credential setup**.",
                    "Challenge mismatch",
                ),
                DocExamples.apiInvalidRequest,
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
            apiServerError()
        }
    }

    val beginRestoreSignin: RouteConfig.() -> Unit = {
        publicOperation(
            "beginRestoreSignin",
            ApiTags.AUTHENTICATION,
            "Begin restore sign-in",
            """
            Starts signing in with a restore credential on a device that has nothing else: no tokens, no
            username. The server returns a challenge with an empty `allowCredentials` list, so the credential
            manager offers whatever restore credentials it holds for this relying party. `userVerification` is
            `discouraged` because the credential must work when the person has no other option.

            Send the signed result to **Complete restore sign-in**.
            """,
        )
        response {
            ok(
                "A challenge to sign with the restore credential.",
                SigninPasskeyBeginResponse(
                    success = true,
                    data =
                        SigninPasskeyBeginData(
                            challenge = DocExamples.CHALLENGE,
                            rpId = "logdate.app",
                            allowCredentials = emptyList(),
                            timeout = 300_000,
                            userVerification = "discouraged",
                        ),
                ),
            )
            apiServerError()
        }
    }

    val completeRestoreSignin: RouteConfig.() -> Unit = {
        publicOperation(
            "completeRestoreSignin",
            ApiTags.AUTHENTICATION,
            "Complete restore sign-in",
            """
            Verifies the restore credential's signature over the challenge from **Begin restore sign-in** and,
            if it matches an account, signs the person in with fresh tokens. From here the app can register a
            normal passkey on the new device with **Begin adding a passkey**.
            """,
        )
        request {
            jsonBody(
                SigninPasskeyCompleteRequest(credential = passkeyAssertion, challenge = DocExamples.CHALLENGE),
                "The assertion produced by the restore credential and the challenge it signed.",
            )
        }
        response {
            ok("Signed in. Store both tokens.", DocExamples.authResponse)
            apiError(HttpStatusCode.BadRequest, DocExamples.apiInvalidRequest)
            apiError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "RESTORE_FAILED",
                    "The signature did not verify or the credential is not a registered restore credential. Start again from step one.",
                    "Restore sign-in failed",
                ),
            )
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase("ACCOUNT_NOT_FOUND", "The credential verified but its account no longer exists.", "Account not found"),
            )
            apiServerError()
        }
    }

    val refreshAccessToken: RouteConfig.() -> Unit = {
        publicOperation(
            "refreshAccessToken",
            ApiTags.AUTHENTICATION,
            "Refresh the access token",
            """
            Exchanges a refresh token for a new access token. This is the one authentication call a client makes
            routinely: whenever a request answers `401`, refresh once and retry it. The refresh token itself is
            not rotated; keep using it until it expires or the person logs out.

            A linked device's refresh also checks that its session still exists. In the last 15 days of the
            session the response also contains a new `refreshToken`; store it and use it from then on.

            No `Authorization` header is needed; the refresh token in the body is the credential.
            """,
        )
        request { jsonBody(RefreshTokenRequestV1(refreshToken = DocExamples.REFRESH_TOKEN), "The refresh token from sign-in.") }
        response {
            ok(
                "A new access token. The refresh token stays the same unless the response contains a new one for a linked device.",
                RefreshTokenResponseV1(success = true, data = RefreshTokenDataV1(accessToken = DocExamples.ACCESS_TOKEN)),
            )
            apiError(HttpStatusCode.BadRequest, DocExamples.apiInvalidRequest)
            apiError(
                HttpStatusCode.Unauthorized,
                ErrorCase(
                    "INVALID_REFRESH_TOKEN",
                    "The refresh token is blank, malformed or expired. Sign the person in again.",
                    "Invalid or expired refresh token",
                ),
                ErrorCase(
                    "REFRESH_TOKEN_REVOKED",
                    "This refresh token was invalidated by **Log out**. Sign the person in again; do not retry.",
                    "Refresh token has been revoked",
                ),
                ErrorCase(
                    DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                    "The linked device was removed from the account. Stop syncing and erase what the device holds.",
                    "This device was removed from the account",
                ),
                ErrorCase(
                    DeviceLinkErrorCodes.DEVICE_SESSION_EXPIRED,
                    "The linked device's session ran out after going unused. Keep the device's data; its phone links it again.",
                    "This device's session ran out after going unused; its phone links it again",
                ),
            )
            apiServerError()
        }
    }

    val logout: RouteConfig.() -> Unit = {
        publicOperation(
            "logout",
            ApiTags.AUTHENTICATION,
            "Log out",
            """
            Revokes a refresh token so it can never mint another access token. Call it when the person signs
            out, then discard both tokens locally. The current access token is not revoked; it expires
            on its own within minutes, which is why logout takes the refresh token and not the access token.

            Logging out an already revoked token succeeds again; the call is safe to repeat.
            """,
        )
        request { jsonBody(LogoutRequestV1(refreshToken = DocExamples.REFRESH_TOKEN), "The refresh token to revoke.") }
        response {
            ok("The refresh token is revoked.", OkResponse(ok = true))
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "INVALID_REFRESH_TOKEN",
                    "`refreshToken` is blank. Send the token you want revoked.",
                    "Refresh token is required",
                ),
                DocExamples.apiInvalidRequest,
            )
            apiServerError()
        }
    }
}
