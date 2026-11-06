package app.logdate.server.routes.docs

import app.logdate.server.atproto.BlueskyPreferences
import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.bearerOrDpopOperation
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.noContent
import app.logdate.server.routes.ok
import app.logdate.server.routes.pdsError
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BlueskyPreferencesDocs {
    private val example =
        BlueskyPreferences(
            listOf(
                buildJsonObject {
                    put("\$type", "app.bsky.actor.defs#feedViewPref")
                    put("feed", "home")
                    put("hideReplies", true)
                },
            ),
        )

    val get: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getBlueskyPreferences",
            ApiTags.XRPC,
            "Read Bluesky preferences",
            "Return the preferences stored for the signed-in public identity. OAuth clients need the generic transition grant.",
        )
        response {
            ok("The account's private preferences.", example)
            preferenceErrors()
        }
    }

    val put: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "putBlueskyPreferences",
            ApiTags.XRPC,
            "Replace Bluesky preferences",
            "Replace the signed-in public identity's preferences with one document, up to 256 KiB.",
        )
        request {
            jsonBody(example, "The new preferences document. An empty list clears saved preferences.")
        }
        response {
            noContent("The preferences were saved.")
            preferenceErrors()
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "The document is invalid.", "Provide valid Bluesky preferences."),
            )
            pdsError(
                HttpStatusCode.PayloadTooLarge,
                ErrorCase("PayloadTooLarge", "The document exceeds 256 KiB.", "The preferences are too large."),
            )
        }
    }

    private fun ResponsesConfig.preferenceErrors() {
        authRequired()
        notConfigured()
        pdsError(
            HttpStatusCode.Forbidden,
            ErrorCase("Forbidden", "The account lacks an active public identity.", "Preferences require an active public identity."),
            ErrorCase(
                "InsufficientScope",
                "The OAuth grant lacks preference access.",
                "This app has not been granted access to preferences.",
            ),
        )
    }
}
