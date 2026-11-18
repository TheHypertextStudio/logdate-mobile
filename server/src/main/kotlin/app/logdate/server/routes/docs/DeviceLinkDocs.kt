package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.DEVICE_LINK_REDEEM_RATE_LIMIT
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.apiError
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.server.routes.rateLimited
import app.logdate.shared.model.devices.DeviceLinkCodeResponse
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.devices.DeviceLinkRedeemRequest
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import app.logdate.shared.model.devices.LinkedDevice
import app.logdate.shared.model.devices.LinkedDeviceListResponse
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

private const val EXAMPLE_SESSION_ID = "8b0f5a4e-2c1d-4f3a-9e8b-7c6d5e4f3a2b"
private const val EXAMPLE_LINK_CODE = "q3J9xV0mH5tY2bW8nK1pR6sD4fG7hL0zC2vB5nM8aQ4"
private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1_000

private val scopeDenied =
    ErrorCase(
        DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED,
        "A linked device sent this request. Only a signed-in phone or computer can do it.",
        "A linked device cannot do this",
    )
private val deviceLimit =
    ErrorCase(
        DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED,
        "The account already has five linked devices in use. Remove one, then try again.",
        "This account already has 5 linked devices",
    )
private val pixelWatch =
    LinkedDevice(
        id = EXAMPLE_SESSION_ID,
        deviceName = "Pixel Watch 3",
        platform = "wear_os",
        createdAt = DocExamples.EPOCH_NOW,
        lastRefreshedAt = DocExamples.EPOCH_NOW,
        expiresAt = DocExamples.EPOCH_NOW + THIRTY_DAYS_MS,
    )

/** Documentation for `/auth/devices` in `DeviceRoutes.kt`. */
internal object DeviceLinkDocs {
    val createLink: RouteConfig.() -> Unit = {
        bearerOperation(
            "createDeviceLink",
            ApiTags.DEVICES,
            "Create a device link code",
            """
            Mints a one-time code that links another device, such as a watch, to the signed-in account with no
            sign-in on that device. Pass the code to the device over a private channel. It works once and
            expires at `expiresAt`, two minutes after it is minted; the server stores only a hash of it.

            Only a full sign-in can mint codes. An account has at most five linked devices and mints at most
            ten codes an hour. A device that hasn't refreshed for 14 days stays linked but doesn't count toward the five.
            """,
        )
        response {
            ok("A new link code.", DeviceLinkCodeResponse(EXAMPLE_LINK_CODE, DocExamples.EPOCH_NOW + 120_000))
            apiError(HttpStatusCode.Forbidden, scopeDenied)
            apiError(HttpStatusCode.Conflict, deviceLimit)
            apiError(
                HttpStatusCode.TooManyRequests,
                ErrorCase(
                    DeviceLinkErrorCodes.DEVICE_LINK_RATE_LIMITED,
                    "The account minted ten codes in the last hour. Wait, then try again.",
                    "Too many link codes this hour",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiServerError()
        }
    }

    val redeemLink: RouteConfig.() -> Unit = {
        publicOperation(
            "redeemDeviceLink",
            ApiTags.DEVICES,
            "Redeem a device link code",
            """
            The device being linked exchanges the code from **Create a device link code** for its own session.
            Its tokens contain `scp=sync`: they reach the sync routes (entries, journals, links, drafts, media,
            moments, sync status), **Get the signed-in account** and the linked-device routes, and nothing
            else. Refresh them with **Refresh the access token**. The session lasts 30 days and renews itself
            when a refresh arrives in its last 15 days.

            No `Authorization` header is needed; the code is the credential. A lost response spends the code,
            so ask the phone for a new one.
            """,
        )
        request {
            jsonBody(
                DeviceLinkRedeemRequest(code = EXAMPLE_LINK_CODE, deviceName = "Pixel Watch 3"),
                "The code from the phone, a name the account owner will recognize, and the platform (`wear_os`).",
            )
        }
        response {
            ok(
                "The new device session.",
                DeviceLinkRedeemResponse(accountId = DocExamples.ACCOUNT_ID, sessionId = EXAMPLE_SESSION_ID, tokens = DocExamples.tokens),
            )
            apiError(
                HttpStatusCode.BadRequest,
                DocExamples.apiInvalidRequest,
                ErrorCase(
                    DeviceLinkErrorCodes.LINK_CODE_INVALID,
                    "The code is unknown, expired, or was already used. Ask the phone for a new one.",
                    "The link code is invalid, expired, or already used",
                ),
                ErrorCase(
                    "VALIDATION_ERROR",
                    "`deviceName` is blank, longer than 120 characters or has control characters, or `platform` is not a short " +
                        "lowercase identifier.",
                    "Device name or platform is invalid",
                ),
            )
            apiError(HttpStatusCode.Conflict, deviceLimit)
            rateLimited(DEVICE_LINK_REDEEM_RATE_LIMIT, ErrorEnvelope.API)
            apiServerError()
        }
    }

    val listDevices: RouteConfig.() -> Unit = {
        bearerOperation(
            "listLinkedDevices",
            ApiTags.DEVICES,
            "List linked devices",
            """
            Returns the account's active linked devices, oldest first. A linked device may call it too; its own
            entry has `current: true`. Removed and expired devices are not listed.
            """,
        )
        response {
            ok("The active linked devices.", LinkedDeviceListResponse(listOf(pixelWatch)))
            bearerUnauthorized(ErrorEnvelope.API)
            apiServerError()
        }
    }

    val unlinkSelf: RouteConfig.() -> Unit = {
        bearerOperation(
            "unlinkThisDevice",
            ApiTags.DEVICES,
            "Unlink this device",
            """
            Ends the calling linked device's own session. Its tokens stop working at once on this server and
            within 30 seconds everywhere. Repeating the call after success answers `401 DEVICE_SESSION_REVOKED`.
            """,
        )
        response {
            noContent("The device's session has ended.")
            apiError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    DeviceLinkErrorCodes.NOT_A_LINKED_DEVICE,
                    "The token belongs to a full sign-in, not a linked device. Use **Log out** instead.",
                    "This token does not belong to a linked device",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiServerError()
        }
    }

    val revokeDevice: RouteConfig.() -> Unit = {
        bearerOperation(
            "revokeLinkedDevice",
            ApiTags.DEVICES,
            "Remove a linked device",
            """
            Ends a linked device's session from a signed-in phone or computer. The device's next request or
            refresh answers `401 DEVICE_SESSION_REVOKED` (within 30 seconds on every server). Removing a device
            that was already removed succeeds again.
            """,
        )
        request {
            pathParameter<String>("id") {
                description = "The linked device's session ID, from **List linked devices**."
                example("Example") { value = EXAMPLE_SESSION_ID }
            }
        }
        response {
            noContent("The device's session has ended.")
            apiError(HttpStatusCode.BadRequest, ErrorCase("VALIDATION_ERROR", "`id` is not a UUID.", "Device ID must be a UUID"))
            apiError(HttpStatusCode.Forbidden, scopeDenied)
            apiError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    DeviceLinkErrorCodes.DEVICE_NOT_FOUND,
                    "No linked device with this ID belongs to the account.",
                    "Linked device not found",
                ),
            )
            bearerUnauthorized(ErrorEnvelope.API)
            apiServerError()
        }
    }
}
