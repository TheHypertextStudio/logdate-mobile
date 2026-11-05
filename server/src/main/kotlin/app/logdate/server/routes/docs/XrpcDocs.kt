package app.logdate.server.routes.docs

import app.logdate.server.atproto.ServiceAuthResponse
import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerOrDpopOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.ok
import app.logdate.server.routes.pdsError
import app.logdate.server.routes.publicOperation
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode
import studio.hypertext.atproto.pds.DescribeRepoResponse
import studio.hypertext.atproto.pds.DescribeServerResponse
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.ResolveHandleResponse
import studio.hypertext.atproto.pds.SessionInfoResponse

/**
 * Documentation for the AT Protocol XRPC methods in `XrpcRoutes.kt`. Method names are lexicon IDs
 * (`com.atproto.repo.getRecord`); descriptions explain them for readers who have never used AT Protocol.
 */
internal object XrpcDocs {
    // ---- Identity and server ------------------------------------------------------------------

    val resolveHandle: RouteConfig.() -> Unit = {
        publicOperation(
            "resolveHandle",
            ApiTags.XRPC,
            "Resolve a handle",
            """
            `com.atproto.identity.resolveHandle`. Resolves a handle such as `willie.logdate.app` into the DID it
            currently points at. A handle is the human-readable name; the DID is the stable identifier
            everything else keys on, so most other methods want the DID.
            """,
        )
        request {
            queryParameter<String>("handle") {
                description = "The handle to resolve, without `@`."
                required = true
                example("Example") { value = DocExamples.HANDLE }
            }
        }
        response {
            ok("The handle's DID.", ResolveHandleResponse(did = did))
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "`handle` is missing.", "Handle is required"),
                ErrorCase("HandleNotFound", "No account here has that handle.", "Handle not found: willie.logdate.app"),
            )
        }
    }

    val describeServer: RouteConfig.() -> Unit = {
        publicOperation(
            "describeAtprotoServer",
            ApiTags.XRPC,
            "Describe the PDS",
            """
            `com.atproto.server.describeServer`. Returns the server's own DID, the handle domains it can issue,
            and whether account creation needs an invite code or a phone number (it needs neither here). The
            AT Protocol equivalent of **Describe this server**.
            """,
        )
        response {
            ok(
                "The PDS description.",
                DescribeServerResponse(
                    did = "did:web:cloud.logdate.app",
                    availableUserDomains = listOf("logdate.app"),
                    inviteCodeRequired = false,
                    phoneVerificationRequired = false,
                ),
            )
        }
    }

    val describeRepo: RouteConfig.() -> Unit = {
        publicOperation(
            "describeRepo",
            ApiTags.XRPC,
            "Describe a repository",
            """
            `com.atproto.repo.describeRepo`. Returns a repository's handle, DID, DID document and the record
            collections it contains. A *repository* is one person's signed, content-addressed store of records.
            Anyone can call this, so it only lists collections that are open to other apps, and never the LogDate
            journal (`studio.hypertext.logdate.*` or `app.logdate.*`). No collection is open to apps yet, so
            `collections` is empty today.
            """,
        )
        request { repoParameter() }
        response {
            ok(
                "The repository's description.",
                DescribeRepoResponse(
                    handle = DocExamples.HANDLE,
                    did = did,
                    didDoc = IdentityDocs.didDocumentExample,
                    collections = emptyList(),
                    handleIsCorrect = true,
                ),
            )
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "`repo` is missing.", "Repo is required"),
                ErrorCase("RepoNotFound", "No repository here matches that DID or handle.", "Repo not found: willie.logdate.app"),
            )
        }
    }

    // ---- Sessions --------------------------------------------------------------------------------

    val getSession: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getAtprotoSession",
            ApiTags.XRPC,
            "Get the current session",
            """
            `com.atproto.server.getSession`. Accepts a legacy session access token as
            `Authorization: Bearer` or an OAuth access token as `Authorization: DPoP` with a
            `DPoP` proof. OAuth responses omit email and email verification fields.
            """,
        )
        response {
            ok(
                "The session's account.",
                SessionInfoResponse(
                    handle = DocExamples.HANDLE,
                    did = did,
                    email = DocExamples.EMAIL,
                    emailConfirmed = true,
                    active = true,
                ),
            )
            authRequired()
            pdsError(
                HttpStatusCode.InternalServerError,
                ErrorCase("InternalServerError", "The request failed on the server; retry later.", "Session request failed"),
            )
            notConfigured()
        }
    }

    val getServiceAuth: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getAtprotoServiceAuth",
            ApiTags.XRPC,
            "Issue a service credential",
            "Issue a credential for one service and XRPC method using the account's public identity.",
        )
        request {
            queryParameter<String>("aud") {
                description = "Service DID, optionally followed by its service fragment."
                required = true
            }
            queryParameter<String>("lxm") {
                description = "XRPC method to bind to the credential."
                required = true
            }
            queryParameter<Long>("exp") {
                description = "Expiration in Unix seconds, no more than 60 seconds in the future."
            }
        }
        response {
            ok("The short-lived credential.", ServiceAuthResponse("example-token"))
            authRequired()
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "The audience or method is invalid.", "Provide a service DID and a valid XRPC method."),
                ErrorCase("BadExpiration", "The expiration is invalid.", "The service credential must expire within 60 seconds."),
            )
            pdsError(
                HttpStatusCode.Forbidden,
                ErrorCase("Forbidden", "The identity or grant cannot issue this credential.", "Service authentication is unavailable."),
            )
            notConfigured()
        }
    }

    val refreshSession: RouteConfig.() -> Unit = {
        bearerOperation(
            "refreshAtprotoSession",
            ApiTags.XRPC,
            "Refresh the session",
            """
            `com.atproto.server.refreshSession`. Send the `refreshJwt` (not the access token) as
            `Authorization: Bearer` and get a new `accessJwt` and `refreshJwt`. Replace both; the old refresh
            token is invalidated.
            """,
        )
        response {
            ok("A new session. Replace both stored tokens.", session)
            bearerUnauthorized(ErrorEnvelope.PDS)
            pdsError(
                HttpStatusCode.InternalServerError,
                ErrorCase("InternalServerError", "The request failed on the server; retry later.", "Session request failed"),
            )
            notConfigured()
        }
    }

    val deleteSession: RouteConfig.() -> Unit = {
        bearerOperation(
            "deleteAtprotoSession",
            ApiTags.XRPC,
            "End the session",
            """
            `com.atproto.server.deleteSession`. Send the `refreshJwt` as `Authorization: Bearer` to revoke it.
            The access token expires on its own.
            """,
        )
        response {
            ok("The session is ended. The body is an empty object.", EmptyPdsResponse())
            bearerUnauthorized(ErrorEnvelope.PDS)
            pdsError(
                HttpStatusCode.InternalServerError,
                ErrorCase("InternalServerError", "The request failed on the server; retry later.", "Session request failed"),
            )
            notConfigured()
        }
    }
}
