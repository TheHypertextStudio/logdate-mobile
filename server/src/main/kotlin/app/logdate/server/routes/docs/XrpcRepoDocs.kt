package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.CreateRecordInput
import app.logdate.server.routes.DeleteRecordInput
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ListReposResponse
import app.logdate.server.routes.ListedRepo
import app.logdate.server.routes.PutRecordInput
import app.logdate.server.routes.bearerOrDpopOperation
import app.logdate.server.routes.binarySchema
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.server.routes.pdsError
import app.logdate.server.routes.publicOperation
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import studio.hypertext.atproto.pds.BlobRef
import studio.hypertext.atproto.pds.CidLink
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.GetLatestCommitResponse
import studio.hypertext.atproto.pds.GetRepoStatusResponse
import studio.hypertext.atproto.pds.ListRecordsResponse
import studio.hypertext.atproto.pds.UploadBlobResponse
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoRecord
import studio.hypertext.atproto.repo.RepoValidationStatus
import studio.hypertext.atproto.repo.RepoWriteResult
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid

/**
 * Documentation for the repository, record and blob XRPC methods in `XrpcRoutes.kt`; identity and
 * session methods live in [XrpcDocs].
 */
internal object XrpcRepoDocs {
    private val closedToOAuthApps =
        ErrorCase(
            "Forbidden",
            "The caller is an app signed in through OAuth. Repository export, commit and status reads, and blob downloads are " +
                "only open to the account's own LogDate sessions.",
            "This method is not available to apps signed in through OAuth",
        )
    private val insufficientScope =
        ErrorCase(
            "InsufficientScope",
            "The caller is an app signed in through OAuth with only the `atproto` scope, which confirms the identity and " +
                "nothing more. Reading or writing the repository also needs `transition:generic`.",
            "This app was only granted the atproto scope, which does not include the repository",
        )
    private val closedCollection =
        ErrorCase(
            "Forbidden",
            "The caller is an app signed in through OAuth and the collection is not open to apps. No collection is open " +
                "yet; the LogDate journal (`studio.hypertext.logdate.*` and `app.logdate.*`) never will be.",
            "Apps signed in through OAuth cannot write to this collection",
        )
    private val uploadsClosed =
        ErrorCase(
            "Forbidden",
            "The caller is an app signed in through OAuth. Apps cannot upload files until a collection open to them refers " +
                "to files.",
            "Apps signed in through OAuth cannot upload files yet, because no collection open to apps refers to files",
        )

    private val publicDownloadsLimited =
        ErrorCase(
            "RateLimitExceeded",
            "A download from a public repository, made with no sign-in, went past this network address's budget for " +
                "the last minute. Repository exports and blobs are counted separately. Wait the number of seconds in " +
                "the `Retry-After` header, then retry.",
            "Too many downloads from this network. Try again in 12 seconds.",
        )
    private val publicDownloadsBusy =
        ErrorCase(
            "ServerBusy",
            "This server instance is already sending as many public downloads as it can hold. Wait the number of " +
                "seconds in the `Retry-After` header, then retry.",
            "The server is busy with other file transfers. Try again in 5 seconds.",
        )
    private val retryAfterHeader: io.github.smiley4.ktoropenapi.config.ResponseConfig.() -> Unit = {
        header<String>("Retry-After") { description = "Seconds to wait before the next attempt." }
    }

    val getRepo: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getRepo",
            ApiTags.XRPC,
            "Export a repository",
            """
            `com.atproto.sync.getRepo`. Streams the whole repository as a CAR file (a content-addressed
            archive of all its blocks), which is how AT Protocol servers copy and verify each other's data.
            Pass `since` (a revision) to get only the blocks written after it. Unless the repository is public, only
            its owner can read it: send their access token. Asking for any other repository answers exactly as if it
            did not exist. Public repositories are the exception: with `ATPROTO_FIREHOSE_ENABLED` on, anyone can
            export a repository whose account keeps its journal in a personal space, with no sign-in, as long as every
            record in it is public. A repository that holds anything private answers as if it did not exist.
            """,
        )
        request {
            didParameter()
            queryParameter<String>("since") {
                description = "Optional revision (TID) to export changes since. Omit for the full repository."
                required = false
                example("Example") { value = REV }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The CAR archive."
                body(binarySchema()) { mediaTypes(ContentType.parse("application/vnd.ipld.car")) }
            }
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "`did` is missing, or `did` or `since` is malformed.", "Invalid did or since"),
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, closedToOAuthApps)
            pdsError(HttpStatusCode.NotFound, repoNotFound)
            pdsError(HttpStatusCode.TooManyRequests, publicDownloadsLimited, headers = retryAfterHeader)
            pdsError(HttpStatusCode.ServiceUnavailable, publicDownloadsBusy, headers = retryAfterHeader)
            notConfigured()
        }
    }

    val getLatestCommit: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getLatestCommit",
            ApiTags.XRPC,
            "Get the latest commit",
            """
            `com.atproto.sync.getLatestCommit`. Returns the CID and revision of the repository's newest commit.
            Compare it with what you last saw to learn whether anything changed before exporting.
            Unless the repository is public, only its owner can read it: send their access token. Asking for any other
            repository answers exactly as if it did not exist. Public repositories are the exception: with
            `ATPROTO_FIREHOSE_ENABLED` on, anyone can read the latest commit of a repository whose account keeps its
            journal in a personal space, with no sign-in.
            """,
        )
        request { didParameter() }
        response {
            ok("The head of the repository.", GetLatestCommitResponse(cid = Cid.require(COMMIT_CID), rev = Tid.require(REV)))
            pdsError(HttpStatusCode.BadRequest, ErrorCase("InvalidRequest", "`did` is missing or malformed.", "did is required"))
            authRequired()
            pdsError(HttpStatusCode.Forbidden, closedToOAuthApps)
            pdsError(HttpStatusCode.NotFound, repoNotFound)
            notConfigured()
        }
    }

    val listRepos: RouteConfig.() -> Unit = {
        publicOperation(
            "listRepos",
            ApiTags.XRPC,
            "List the repositories this server shows",
            """
            `com.atproto.sync.listRepos`. Lists the repositories this server shows to everyone, ordered by DID, with
            each one's newest commit. Relays page through it to find repositories to follow. It needs no sign-in and
            exists only while the repository event stream is turned on (`ATPROTO_FIREHOSE_ENABLED`). An account
            appears once it keeps its journal in a personal space, has an identity other apps can look up, and has
            written a record.
            """,
        )
        request {
            queryParameter<Int>("limit") {
                description = "Repositories per page. Defaults to 500; clamped to 1…1000."
                required = false
                example("Example") { value = 500 }
            }
            queryParameter<String>("cursor") {
                description = "The `cursor` from the previous page."
                required = false
                example("Example") { value = DocExamples.DID }
            }
        }
        response {
            ok(
                "A page of repositories.",
                ListReposResponse(
                    repos = listOf(ListedRepo(did = did, head = Cid.require(COMMIT_CID), rev = Tid.require(REV), active = true)),
                ),
            )
        }
    }

    val getRepoStatus: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getRepoStatus",
            ApiTags.XRPC,
            "Get repository status",
            """
            `com.atproto.sync.getRepoStatus`. Says whether a repository is active on this server and, if so,
            its current revision. Deactivated or migrated repositories answer `active: false` with a `status`.
            Unless the repository is public, only its owner can read it: send their access token. Asking for any other
            repository answers exactly as if it did not exist. Public repositories are the exception: with
            `ATPROTO_FIREHOSE_ENABLED` on, anyone can read the status of a repository whose account keeps its journal
            in a personal space, with no sign-in. An account that is not active answers `active: false` with a `status`
            (`deactivated`, `suspended`, `takendown` or `deleted`) instead of an error.
            """,
        )
        request { didParameter() }
        response {
            ok("The repository's status.", GetRepoStatusResponse(did = did, active = true, rev = Tid.require(REV)))
            pdsError(HttpStatusCode.BadRequest, ErrorCase("InvalidRequest", "`did` is missing or malformed.", "did is required"))
            authRequired()
            pdsError(HttpStatusCode.Forbidden, closedToOAuthApps)
            pdsError(HttpStatusCode.NotFound, repoNotFound)
            notConfigured()
        }
    }

    // ---- Records --------------------------------------------------------------------------------

    val getRecord: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getRecord",
            ApiTags.XRPC,
            "Get a record",
            """
            `com.atproto.repo.getRecord`. Fetches one record by repository, collection and record key. A
            *collection* is a lexicon ID such as `studio.hypertext.logdate.content`; the *record key* is the
            record's ID within it. Records are returned as the JSON the writer stored, plus the `at://` URI and
            the CID (content hash) of this version. Unless the repository is public, only its owner can read it: send
            their access token. Asking for any other repository answers exactly as if it did not exist. Public records
            are the exception: with `ATPROTO_FIREHOSE_ENABLED` on, anyone can read a record in a public collection
            (the profile, sharing keys and published stories) of an account that keeps its journal in a personal
            space, with no sign-in. A record in any other collection answers `RecordNotFound`. An app signed in
            through OAuth needs
            `transition:generic`, and a record in a collection that is not open to apps answers `RecordNotFound`.
            No collection is open to apps yet.
            """,
        )
        request {
            repoParameter()
            queryParameter<String>("collection") {
                description = "The collection's lexicon ID."
                required = true
                example("Example") { value = COLLECTION }
            }
            queryParameter<String>("rkey") {
                description = "The record key within the collection."
                required = true
                example("Example") { value = RKEY }
            }
            queryParameter<String>("cid") {
                description = "Optional CID; when given, the record is returned only if its current CID matches."
                required = false
                example("Example") { value = CID }
            }
        }
        response {
            ok("The record.", RepoRecord(uri = recordUri, cid = CID, value = record))
            pdsError(
                HttpStatusCode.BadRequest,
                invalidRequest,
                ErrorCase(
                    "RecordNotFound",
                    "No record with that key exists in the collection, or `cid` does not match it.",
                    "Record not found",
                ),
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, insufficientScope)
            notConfigured()
        }
    }

    val listRecords: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "listRecords",
            ApiTags.XRPC,
            "List records",
            """
            `com.atproto.repo.listRecords`. Pages through the records in one collection of one repository.
            Pass the `cursor` from each response to get the next page; an absent cursor means you have reached
            the end. Unless the repository is public, only its owner can read it: send their access token. Asking for
            any other repository answers exactly as if it did not exist. Public records are the exception: with
            `ATPROTO_FIREHOSE_ENABLED` on, anyone can list a public collection (the profile, sharing keys and
            published stories) of an account that keeps its journal in a personal space, with no sign-in. Any other
            collection is empty. An app signed in through OAuth needs
            `transition:generic`, and a collection that is not open to apps is always empty. No collection is open
            to apps yet.
            """,
        )
        request {
            repoParameter()
            queryParameter<String>("collection") {
                description = "The collection's lexicon ID."
                required = true
                example("Example") { value = COLLECTION }
            }
            queryParameter<Int>("limit") {
                description = "Records per page. Defaults to 50; clamped to 1…100."
                required = false
                example("Example") { value = 50 }
            }
            queryParameter<String>("cursor") {
                description = "The `cursor` from the previous page."
                required = false
                example("Example") { value = "3lcfmzdwnz22c" }
            }
            queryParameter<Boolean>("reverse") {
                description = "`true` to page from oldest to newest instead of newest to oldest."
                required = false
                example("Example") { value = false }
            }
        }
        response {
            ok(
                "A page of records.",
                ListRecordsResponse(records = listOf(RepoRecord(uri = recordUri, cid = CID, value = record)), cursor = null),
            )
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "InvalidRequest",
                    "`repo` or `collection` is missing or malformed, the repository is not yours or does not exist, " +
                        "the collection is not one this server stores, or the cursor is invalid.",
                    "Invalid repo or collection",
                ),
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, insufficientScope)
            notConfigured()
        }
    }

    val createRecord: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "createRecord",
            ApiTags.XRPC,
            "Create a record",
            """
            `com.atproto.repo.createRecord`. Writes a new record into a collection of your own repository
            and returns its URI and CID. Omit `rkey` to let the server choose a key. Send `swapCommit` with
            the commit CID you last saw to fail instead of writing over a change you have not seen.

            Authenticate with either a LogDate access token (`Authorization: Bearer`) or an OAuth token
            (`Authorization: DPoP` plus a `DPoP` proof). The `repo` must be your own DID or handle. An app
            signed in through OAuth needs the `transition:generic` scope and may only write a collection open to
            apps. None is open yet, and the LogDate journal never will be.
            """,
        )
        request {
            jsonBody(
                CreateRecordInput(repo = DocExamples.DID, collection = Nsid.require(COLLECTION), record = record),
                "Where to write and what. `record` is the record's JSON, including `\$type`.",
            )
        }
        response {
            ok("The record was written.", RepoWriteResult(uri = recordUri, cid = CID, validationStatus = RepoValidationStatus.VALID))
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "InvalidRequest",
                    "The repo is unknown or the collection is not one this server stores. A body that is not valid JSON for this method answers `400` with an empty body instead.",
                    "Invalid repo",
                ),
                invalidSwap,
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, repoMismatch, insufficientScope, closedCollection)
            notConfigured()
        }
    }

    val putRecord: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "putRecord",
            ApiTags.XRPC,
            "Create or replace a record",
            """
            `com.atproto.repo.putRecord`. Writes a record under a key you choose, creating or replacing it.
            `swapRecord` (the record's current CID) and `swapCommit` let you refuse to overwrite a version you
            have not seen. Same authentication and ownership rules as **Create a record**.
            """,
        )
        request {
            jsonBody(
                PutRecordInput(
                    repo = DocExamples.DID,
                    collection = Nsid.require(COLLECTION),
                    recordKey = RecordKey.require(RKEY),
                    record = record,
                    swapRecord = CID,
                ),
                "Where to write, the key, and the record's JSON.",
            )
        }
        response {
            ok("The record was written.", RepoWriteResult(uri = recordUri, cid = CID, validationStatus = RepoValidationStatus.VALID))
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "InvalidRequest",
                    "The repo is unknown or the collection is not one this server stores. A body that is not valid JSON for this method answers `400` with an empty body instead.",
                    "Invalid repo",
                ),
                invalidSwap,
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, repoMismatch, insufficientScope, closedCollection)
            notConfigured()
        }
    }

    val deleteRecord: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "deleteRecord",
            ApiTags.XRPC,
            "Delete a record",
            """
            `com.atproto.repo.deleteRecord`. Removes a record from your repository. `swapRecord` and
            `swapCommit` work as in **Create or replace a record**. Deleting a record that does not exist
            succeeds.
            """,
        )
        request {
            jsonBody(
                DeleteRecordInput(repo = DocExamples.DID, collection = Nsid.require(COLLECTION), recordKey = RecordKey.require(RKEY)),
                "Which record to delete.",
            )
        }
        response {
            ok("The record is gone. The body is an empty object.", EmptyPdsResponse())
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "InvalidRequest",
                    "The repo is unknown or not yours to write. A body that is not valid JSON for this method answers `400` with an empty body instead.",
                    "Invalid repo",
                ),
                invalidSwap,
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, repoMismatch, insufficientScope, closedCollection)
            notConfigured()
        }
    }

    // ---- Blobs ----------------------------------------------------------------------------------

    val uploadBlob: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "uploadBlob",
            ApiTags.XRPC,
            "Upload a blob",
            """
            `com.atproto.repo.uploadBlob`. Stores a binary file (an image, say) in your repository and returns
            a *blob reference* to embed in a record. Send the raw bytes as the body with the file's
            `Content-Type`; there is no multipart wrapper. Blobs that no record references may be cleaned up.
            Apps signed in through OAuth cannot upload files yet.
            """,
        )
        request {
            body(binarySchema()) {
                description =
                    "The file's bytes. The request's `Content-Type` (for example `image/jpeg`) is stored as the blob's media type " +
                    "and defaults to `application/octet-stream`."
                required = true
                mediaTypes(ContentType.Application.OctetStream, ContentType.Image.Any)
            }
        }
        response {
            ok(
                "The blob is stored. Embed `blob` in a record to keep it.",
                UploadBlobResponse(blob = BlobRef(ref = CidLink(Cid.require(CID)), mimeType = "image/jpeg", size = 2_483_113)),
            )
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "InvalidRequest",
                    "The account has no DID yet, or the blob could not be stored.",
                    "Authenticated account does not have a valid DID",
                ),
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, uploadsClosed)
            notConfigured()
        }
    }

    val getBlob: RouteConfig.() -> Unit = {
        bearerOrDpopOperation(
            "getBlob",
            ApiTags.XRPC,
            "Download a blob",
            """
            `com.atproto.sync.getBlob`. Streams a blob's bytes by repository DID and CID, with the
            `Content-Type` it was uploaded with. Unless the repository is public, only its owner can read it: send
            their access token. Asking for any other repository answers exactly as if it did not exist. Public blobs
            are the exception: with `ATPROTO_FIREHOSE_ENABLED` on, anyone can download a blob that a public record of
            an account in a personal space refers to, with no sign-in. A blob no public record refers to answers as if
            it did not exist.
            """,
        )
        request {
            didParameter()
            queryParameter<String>("cid") {
                description = "The blob's CID, from its blob reference."
                required = true
                example("Example") { value = CID }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "The blob's bytes. `Content-Type` is the type given at upload."
                body(binarySchema()) { mediaTypes(ContentType.Application.OctetStream) }
            }
            pdsError(
                HttpStatusCode.BadRequest,
                ErrorCase("InvalidRequest", "`did` or `cid` is missing or malformed.", "did and cid are required"),
            )
            authRequired()
            pdsError(HttpStatusCode.Forbidden, closedToOAuthApps)
            pdsError(
                HttpStatusCode.NotFound,
                ErrorCase(
                    "BlobNotFound",
                    "No blob with that CID exists in the repository, or the repository is not yours.",
                    "Blob not found",
                ),
            )
            pdsError(HttpStatusCode.TooManyRequests, publicDownloadsLimited, headers = retryAfterHeader)
            pdsError(HttpStatusCode.ServiceUnavailable, publicDownloadsBusy, headers = retryAfterHeader)
            notConfigured()
        }
    }
}
