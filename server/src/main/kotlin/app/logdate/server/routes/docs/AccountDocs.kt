package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.AddPasskeyCompleteRequest
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.IdentityListResponse
import app.logdate.server.routes.IdentityView
import app.logdate.server.routes.PasskeyListResponse
import app.logdate.server.routes.PasskeyResponse
import app.logdate.server.routes.RestoreRegisterBeginResponse
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.shared.model.AccountInfoResponse
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.BeginEmailVerificationResponse
import app.logdate.shared.model.CompleteEmailVerificationRequest
import app.logdate.shared.model.EmailVerificationConflictResponse
import app.logdate.shared.model.EmailVerificationErrorResponse
import app.logdate.shared.model.EmailVerifiedResponse
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAuthenticatorResponse
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.UpdateAccountProfileRequest
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.github.smiley4.ktoropenapi.config.descriptors.AnyOfTypeDescriptor
import io.github.smiley4.ktoropenapi.config.descriptors.KTypeDescriptor
import io.ktor.http.HttpStatusCode
import io.swagger.v3.oas.models.examples.Example
import kotlin.reflect.typeOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Documentation for the `/auth/me` family in `AuthV1Routes.kt`. */
@OptIn(ExperimentalUuidApi::class)
internal object AccountDocs {
    private val passkeyInfo =
        PasskeyInfo(
            id = Uuid.parse("9c1d2e3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5"),
            credentialId = DocExamples.CREDENTIAL_ID,
            nickname = "Pixel 9",
            deviceType = "platform",
            createdAt = DocExamples.instantNow,
            lastUsedAt = DocExamples.instantNow,
        )

    private val account =
        LogDateAccount(
            id = DocExamples.accountUuid,
            username = DocExamples.USERNAME,
            displayName = DocExamples.DISPLAY_NAME,
            did = DocExamples.DID,
            handle = DocExamples.HANDLE,
            bio = "Morning walker, occasional photographer.",
            passkeyCredentialIds = listOf(DocExamples.CREDENTIAL_ID),
            createdAt = DocExamples.instantNow,
            updatedAt = DocExamples.instantNow,
            email = DocExamples.EMAIL,
            emailVerified = true,
            emailVerifiedAt = DocExamples.instantNow,
        )

    private fun ResponsesConfig.standardFailures() {
        bearerUnauthorized(ErrorEnvelope.API)
        apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
        apiServerError()
    }

    val getCurrentAccount: RouteConfig.() -> Unit = {
        bearerOperation(
            "getCurrentAccount",
            ApiTags.ACCOUNT,
            "Get the signed-in account",
            """
            Returns the account behind the access token you sent, together with a brand-new pair of tokens.
            Apps call it on launch to refresh what they show on the profile screen and to confirm the stored
            token still works.

            Because the response includes fresh tokens, treat it like a sign-in: replace the ones you stored.
            A linked device gets tokens for its own session and scope again, never full-access tokens.
            """,
        )
        response {
            ok("The account and new tokens.", DocExamples.authResponse)
            standardFailures()
        }
    }

    val updateProfile: RouteConfig.() -> Unit = {
        bearerOperation(
            "updateProfile",
            ApiTags.ACCOUNT,
            "Update the profile",
            """
            Changes the username, display name or bio. Send only the fields you want to change; the others keep
            their values. At least one field must be present. Changing the username does not change the account's
            AT Protocol handle.
            """,
        )
        request {
            jsonBody(
                UpdateAccountProfileRequest(displayName = "Alice P.", bio = "Morning walker, occasional photographer."),
                "The fields to change. Omitted fields are left as they are.",
            )
        }
        response {
            ok("The updated account.", AccountInfoResponse(success = true, data = account))
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "VALIDATION_ERROR",
                    "No field was provided, or the new username or display name breaks a rule (usernames: 3–50 " +
                        "letters, digits and underscores, not reserved; display names: 1–100 characters).",
                    "At least one field must be provided",
                ),
                DocExamples.apiInvalidRequest,
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
            apiError(
                HttpStatusCode.Conflict,
                ErrorCase(
                    "USERNAME_TAKEN",
                    "Another account already has the requested username. Choose a different one.",
                    "Username is already taken",
                ),
            )
            apiServerError()
        }
    }

    val deleteAccount: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteAccount",
            ApiTags.ACCOUNT,
            "Delete the account",
            """
            Permanently deletes the account and everything it owns: entries, journals, media, backups, passkeys
            and linked identities. There is no undo and no grace period, so confirm with the person first.
            Discard the account's tokens on the client: the access token is a stateless JWT and stays technically
            valid until it expires, although endpoints that look the account up answer `404` from now on.

            > [!WARNING]
            > This cannot be reversed. Export the person's data before calling it.
            """,
        )
        response {
            noContent("The account and all of its data are gone.")
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
            apiError(
                HttpStatusCode.InternalServerError,
                ErrorCase(
                    "DELETION_FAILED",
                    "The account row could not be removed. Media and backup files are deleted first and are not restored, so the " +
                        "account still exists but its uploads may already be gone. Retry, and report it if it persists.",
                    "Account could not be deleted",
                ),
                DocExamples.apiServerError,
            )
            apiError(
                HttpStatusCode.ServiceUnavailable,
                ErrorCase(
                    "DELETION_UNAVAILABLE",
                    "This deployment has account deletion switched off. Self-hosters delete accounts through the database.",
                    "Account deletion is not configured on this server",
                ),
            )
        }
    }

    val listPasskeys: RouteConfig.() -> Unit = {
        bearerOperation(
            "listPasskeys",
            ApiTags.ACCOUNT,
            "List passkeys",
            """
            Lists the active passkeys on the account, with the nickname and device type recorded when each was
            added and when it was last used. Use it to build a "Your passkeys" settings screen.
            """,
        )
        response {
            ok(
                "The account's active passkeys, most recently used first. Passkeys that have never signed in come before " +
                    "those, in no defined order.",
                PasskeyListResponse(success = true, data = listOf(passkeyInfo)),
            )
            standardFailures()
        }
    }

    val beginAddPasskey: RouteConfig.() -> Unit = {
        bearerOperation(
            "beginAddPasskey",
            ApiTags.ACCOUNT,
            "Begin adding a passkey",
            """
            Returns registration options for adding another passkey to the signed-in account, for example on
            a second device. Hand `data` to the platform's credential API, then send the result to
            **Complete adding a passkey**. This works like sign-up, without the session token.
            """,
        )
        response {
            ok(
                "Registration options for the new passkey.",
                RestoreRegisterBeginResponse(success = true, data = DocExamples.registrationOptions),
            )
            standardFailures()
        }
    }

    val completeAddPasskey: RouteConfig.() -> Unit = {
        bearerOperation(
            "completeAddPasskey",
            ApiTags.ACCOUNT,
            "Complete adding a passkey",
            """
            Verifies the credential created from **Begin adding a passkey** and stores it. The person can sign
            in with it immediately.
            """,
        )
        request {
            jsonBody(
                AddPasskeyCompleteRequest(
                    challenge = DocExamples.CHALLENGE,
                    credential =
                        PasskeyCredentialResponse(
                            id = DocExamples.CREDENTIAL_ID,
                            rawId = DocExamples.CREDENTIAL_ID,
                            response =
                                PasskeyAuthenticatorResponse(
                                    clientDataJSON = DocExamples.CLIENT_DATA_JSON,
                                    attestationObject = DocExamples.ATTESTATION_OBJECT,
                                ),
                        ),
                ),
                "The challenge from step one and the credential the authenticator produced.",
            )
        }
        response {
            code(HttpStatusCode.Created) {
                description = "The passkey is stored and usable."
                body<PasskeyResponse> { example("Example") { value = PasskeyResponse(success = true, data = passkeyInfo) } }
            }
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "PASSKEY_VERIFICATION_FAILED",
                    "The credential did not verify against the challenge. Start again from **Begin adding a passkey**.",
                    "Challenge mismatch",
                ),
                DocExamples.apiInvalidRequest,
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(HttpStatusCode.NotFound, DocExamples.apiAccountNotFound)
            apiError(
                HttpStatusCode.InternalServerError,
                ErrorCase("PASSKEY_STORE_FAILED", "The passkey verified but could not be saved. Try again.", "Failed to store passkey"),
                DocExamples.apiServerError,
            )
        }
    }

    val deletePasskey: RouteConfig.() -> Unit = {
        bearerOperation(
            "deletePasskey",
            ApiTags.ACCOUNT,
            "Remove a passkey",
            """
            Removes one passkey from the account. Deleting a passkey that was already removed answers `204`
            again, so the call is safe to retry.

            The server refuses to remove the last passkey when the account has no other way to sign in (no
            Google identity), because that would lock the person out for good.
            """,
        )
        request {
            pathParameter<String>("credentialId") {
                description = "The passkey's `credentialId`, as listed by **List passkeys**."
                example("Example") { value = DocExamples.CREDENTIAL_ID }
            }
        }
        response {
            noContent("The passkey is gone (or already was).")
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase("VALIDATION_ERROR", "`credentialId` is blank.", "Credential ID is required"),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "PASSKEY_NOT_FOUND",
                    "No passkey with that ID belongs to this account. Refresh the list; it may belong to another account.",
                    "Passkey not found",
                ),
                DocExamples.apiAccountNotFound,
            )
            apiError(
                HttpStatusCode.Conflict,
                ErrorCase(
                    "LAST_SIGNIN_FACTOR",
                    "This is the only way into the account. Add another passkey or link Google first, then remove it.",
                    "Cannot delete the last sign-in factor",
                ),
            )
            apiServerError()
        }
    }

    val beginEmailVerification: RouteConfig.() -> Unit = {
        bearerOperation(
            "beginEmailVerification",
            ApiTags.ACCOUNT,
            "Begin email verification",
            """
            Starts proving that the person controls an email address using a digital credential from the
            device's wallet (Android Credential Manager). The server issues a `transactionId`, a `nonce` to bind
            the credential to this attempt, and the `audience` the credential must be issued for. Pass those to
            the credential request, then send the result to **Complete email verification**.

            Only available when this deployment has the verifier configured and the account's plan includes
            email verification.
            """,
        )
        response {
            ok(
                "A verification transaction. Keep `transactionId` for the next step.",
                BeginEmailVerificationResponse(
                    transactionId = "0f9e8d7c-6b5a-4c3d-9e2f-1a0b9c8d7e6f",
                    nonce = "bm9uY2UtZm9yLXRoaXMtYXR0ZW1wdA",
                    audience = "https://logdate.app/auth/email",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "FEATURE_DISABLED",
                    "The account's plan does not include email verification. Hide the feature.",
                    "Email verification is not enabled for this account",
                ),
                DocExamples.apiAccountNotFound,
            )
            apiServerError()
            apiError(
                HttpStatusCode.NotImplemented,
                ErrorCase(
                    "EMAIL_VERIFICATION_UNAVAILABLE",
                    "This deployment has no digital-credential verifier configured. Hide the feature.",
                    "Email verification is not configured on this server",
                ),
            )
        }
    }

    val completeEmailVerification: RouteConfig.() -> Unit = {
        bearerOperation(
            "completeEmailVerification",
            ApiTags.ACCOUNT,
            "Complete email verification",
            """
            Verifies the digital credential the wallet returned for the transaction from **Begin email
            verification** and, if it checks out, records the email address as verified on the account.

            The failure responses use their own shapes rather than the usual envelope: `400` carries a `reason`
            code from the verifier, and `409` a fixed `code` of `email_already_attached`. A malformed
            `transactionId` is the one exception and answers `400` in the standard envelope with code
            `INVALID_TRANSACTION_ID`.
            """,
        )
        request {
            jsonBody(
                CompleteEmailVerificationRequest(
                    transactionId = "0f9e8d7c-6b5a-4c3d-9e2f-1a0b9c8d7e6f",
                    credentialJson = """{"protocol":"openid4vp","data":{"vp_token":"eyJhbGciOi..."}}""",
                ),
                "The transaction from step one and the raw credential JSON the wallet returned.",
            )
        }
        response {
            ok(
                "The email is now verified on the account.",
                EmailVerifiedResponse(email = DocExamples.EMAIL, emailVerifiedAt = DocExamples.instantNow),
            )
            code(HttpStatusCode.BadRequest) {
                description =
                    "Two shapes. When the credential did not verify, `{ \"reason\" }` carries a stable code from the " +
                    "verifier (for example an expired credential or a nonce mismatch); start again from step one. A " +
                    "malformed `transactionId` or an unreadable body instead answers the standard envelope with " +
                    "`INVALID_TRANSACTION_ID` or `INVALID_REQUEST`."
                body(
                    AnyOfTypeDescriptor(
                        listOf(
                            KTypeDescriptor(typeOf<EmailVerificationErrorResponse>()),
                            KTypeDescriptor(typeOf<ApiErrorResponse>()),
                        ),
                    ),
                ) {
                    example("Verifier rejected", Example().value(mapOf("reason" to "nonce_mismatch")))
                    example(
                        "Malformed transaction id",
                        Example().value(
                            mapOf("error" to mapOf("code" to "INVALID_TRANSACTION_ID", "message" to "Malformed transaction id")),
                        ),
                    )
                }
            }
            bearerUnauthorized(ErrorEnvelope.API)
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "FEATURE_DISABLED",
                    "The account's plan does not include email verification. Hide the feature.",
                    "Email verification is not enabled for this account",
                ),
                DocExamples.apiAccountNotFound,
            )
            code(HttpStatusCode.Conflict) {
                description =
                    "Another LogDate account already has this email verified. The person must use that account or a different email."
                body<EmailVerificationConflictResponse> {
                    example("Already attached") {
                        value = EmailVerificationConflictResponse(message = "This email is already attached to another LogDate account.")
                    }
                }
            }
            apiServerError()
            apiError(
                HttpStatusCode.NotImplemented,
                ErrorCase(
                    "EMAIL_VERIFICATION_UNAVAILABLE",
                    "This deployment has no digital-credential verifier configured. Hide the feature.",
                    "Email verification is not configured on this server",
                ),
            )
        }
    }

    val getEntitlement: RouteConfig.() -> Unit = {
        bearerOperation(
            "getEntitlement",
            ApiTags.ACCOUNT,
            "Get the plan and limits",
            """
            Reports which plan the account is on, whether it is in good standing, the limits that apply, and
            which premium features are switched on. Apps use it to render the "Your plan" row and to cap
            uploads before the server would answer `402`.

            On a self-hosted server without billing this always answers the unlimited `self_host_unlimited`
            plan with `status` `SELF_HOST`, so a client never has to special-case self-hosting.
            """,
        )
        response {
            ok(
                "The account's plan, status and limits.",
                EntitlementResponse(
                    planId = "cloud-standard",
                    tier = EntitlementTierWire.STANDARD,
                    status = EntitlementStatusWire.ACTIVE,
                    storageBytesLimit = 5_368_709_120,
                    backupCountLimit = 5,
                    transcriptionSecondsPerMonthLimit = 18_000,
                    features =
                        mapOf(
                            "cloud_transcription_realtime" to true,
                            "cloud_transcript_refinement" to true,
                            "email_verification" to true,
                        ),
                ),
            )
            standardFailures()
        }
    }

    val listIdentities: RouteConfig.() -> Unit = {
        bearerOperation(
            "listIdentities",
            ApiTags.ACCOUNT,
            "List sign-in methods",
            """
            Lists the identities linked to the account: one entry per passkey and one for Google, with the
            email each carries and when it was last used to sign in. This is how a settings screen shows
            "Signed in with Google as alice@example.com". `lastSignInAt` is absent for a passkey that was
            registered but has not signed in yet; Google identities always carry it.
            """,
        )
        response {
            ok(
                "The linked identities.",
                IdentityListResponse(
                    success = true,
                    data =
                        listOf(
                            IdentityView(
                                provider = "passkey",
                                providerSubject = DocExamples.CREDENTIAL_ID,
                                email = DocExamples.EMAIL,
                                emailVerified = true,
                                createdAt = DocExamples.ISO_NOW,
                                lastSignInAt = null,
                            ),
                            IdentityView(
                                provider = "google",
                                providerSubject = "115442087633256981223",
                                email = DocExamples.EMAIL,
                                emailVerified = true,
                                createdAt = DocExamples.ISO_NOW,
                                lastSignInAt = DocExamples.ISO_NOW,
                            ),
                        ),
                ),
            )
            standardFailures()
        }
    }
}
