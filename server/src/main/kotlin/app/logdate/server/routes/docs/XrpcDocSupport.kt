package app.logdate.server.routes.docs

import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.dpopNonceHeader
import app.logdate.server.routes.pdsError
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.SessionResponse
import studio.hypertext.atproto.syntax.AtUri

/** Examples, error cases and parameter helpers shared by the XRPC documentation objects. */
internal const val COLLECTION = "studio.hypertext.logdate.content"
internal const val RKEY = "3lcfmzdwnz22c"
internal const val CID = "bafyreiha2g4xx3ny4ftcxg5dydjklm6q2nw7rz4bqdxu6yi3k7ovz5m7ei"
internal const val COMMIT_CID = "bafyreib2rxk3rh6kzwq5m5nq6tze3wgqqtlsm2pjpaom6xkf5x2yopcd3y"
internal const val REV = "3lcfmzdwnz22c"
internal const val ACCESS_JWT = "eyJhbGciOiJIUzI1NiJ9.atproto-access-example"
internal const val REFRESH_JWT = "eyJhbGciOiJIUzI1NiJ9.atproto-refresh-example"

internal val did = AtprotoDid.require(DocExamples.DID)
internal val recordUri = AtUri.require("at://${DocExamples.DID}/$COLLECTION/$RKEY")
internal val record =
    buildJsonObject {
        put("\$type", JsonPrimitive(COLLECTION))
        put("text", JsonPrimitive(SyncExamples.NOTE_TEXT))
        put("createdAt", JsonPrimitive(DocExamples.ISO_NOW))
    }

internal val unsupported =
    ErrorCase(
        "Unsupported",
        "This deployment has the AT Protocol service behind this method switched off. Check `capabilities` on **Describe this server**.",
        "Repo service is not configured",
    )
internal val invalidRequest =
    ErrorCase(
        "InvalidRequest",
        "A required parameter is missing or malformed, or the repository is not yours or does not exist. " +
            "The `message` names the parameter.",
        "repo, collection, and rkey are required",
    )
internal val repoMismatch =
    ErrorCase(
        "RepoMismatch",
        "The `repo` in the body is not the authenticated account's DID or handle. You can only write to your own repository.",
        "Authenticated account does not own repo",
    )
internal val invalidSwap =
    ErrorCase(
        "InvalidSwap",
        "`swapRecord` or `swapCommit` did not match the current state: someone wrote in between. Re-read and try again.",
        "swapRecord did not match",
    )
internal val repoNotFound =
    ErrorCase(
        "RepoNotFound",
        "No repository with that DID is hosted here, or it is not yours. The two cases answer identically.",
        "Unknown repo: did:plc:…",
    )

internal fun ResponsesConfig.notConfigured() = pdsError(HttpStatusCode.NotImplemented, unsupported)

/** Repository operations answer every credential problem with `AuthRequired`; only the session methods use `InvalidToken`. */
internal fun ResponsesConfig.authRequired() =
    pdsError(
        HttpStatusCode.Unauthorized,
        ErrorCase(
            "AuthRequired",
            "The bearer or DPoP-bound access token is missing, invalid, or belongs to no account here. " +
                "Sign in again or refresh the session, then retry. When the " +
                "`message` says the DPoP nonce is stale, take the new one from `DPoP-Nonce` and retry once.",
            "Missing bearer token",
        ),
        headers = dpopNonceHeader,
    )

internal fun RequestConfig.didParameter() {
    queryParameter<String>("did") {
        description = "The repository's DID."
        required = true
        example("Example") { value = DocExamples.DID }
    }
}

internal fun RequestConfig.repoParameter() {
    queryParameter<String>("repo") {
        description = "The repository, as a DID or a handle."
        required = true
        example("Example") { value = DocExamples.HANDLE }
    }
}

internal val session =
    SessionResponse(
        accessJwt = ACCESS_JWT,
        refreshJwt = REFRESH_JWT,
        handle = DocExamples.HANDLE,
        did = did,
        email = DocExamples.EMAIL,
        emailConfirmed = true,
        active = true,
    )
