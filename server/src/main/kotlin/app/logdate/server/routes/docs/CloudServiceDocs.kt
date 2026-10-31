package app.logdate.server.routes.docs

import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.AssetLinkStatement
import app.logdate.server.routes.AssetLinkTarget
import app.logdate.server.routes.CLOUD_TRANSCRIPTION_SESSION_LIMIT
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.ResourceResponse
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.messageError
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.server.routes.rateLimited
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.PlanCatalogResponse
import app.logdate.shared.model.PlanOption
import app.logdate.shared.model.QuotaUsage
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerInfoResponse
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.transcription.CloudAudioInputFormat
import app.logdate.shared.model.transcription.CloudTranscriptionClientSecret
import app.logdate.shared.model.transcription.CloudTranscriptionMode
import app.logdate.shared.model.transcription.CloudTranscriptionSessionRequest
import app.logdate.shared.model.transcription.CloudTranscriptionSessionResponse
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

/**
 * Documentation for the Getting-started and Cloud-services endpoints: `ServerInfoRoutes.kt`,
 * `PlanRoutes.kt`, `WellKnownRoutes.kt`, `QuotaRoutes.kt`, `TranscriptionRoutes.kt`, `ResourceRoutes.kt`.
 */
internal object CloudServiceDocs {
    val getServerInfo: RouteConfig.() -> Unit = {
        publicOperation(
            "getServerInfo",
            ApiTags.SERVER,
            "Describe this server",
            """
            The first call any client should make. It reports where the API lives, whether this is LogDate
            Cloud or a self-hosted server, which features are switched on, and the passkey relying party to use.
            No token needed.

            Read `capabilities` before showing a sign-in or feature button: a deployment without
            `CLOUD_TRANSCRIPTION` will refuse transcription sessions, one without `ATPROTO_OAUTH` has no usable
            OAuth endpoints, and so on. `protocolFeatures` lists additive behaviors newer servers have
            that older clients can safely ignore.
            """,
        )
        response {
            ok(
                "What this deployment is and can do.",
                ServerInfoResponse(
                    success = true,
                    data =
                        ServerDescriptor(
                            serverOrigin = "https://cloud.logdate.app",
                            apiBaseUrl = "https://cloud.logdate.app/api/v1",
                            deploymentKind = DeploymentKind.FIRST_PARTY,
                            displayName = "LogDate Cloud",
                            handleDomain = "logdate.app",
                            passkey = ServerPasskeyConfig(rpId = "logdate.app", rpName = "LogDate"),
                            capabilities = ServerCapability.entries,
                            protocolFeatures = listOf("canonicalOwnerBindingV1"),
                            privacyPolicyUrl = "https://logdate.app/privacy",
                            termsOfServiceUrl = "https://logdate.app/terms",
                        ),
                ),
            )
        }
    }

    val listPlans: RouteConfig.() -> Unit = {
        publicOperation(
            "listPlans",
            ApiTags.SERVER,
            "List the plans on offer",
            """
            Lists the subscription plans this deployment sells, so an app can show them before a person has
            an account. No token needed.

            Prices are deliberately not included: the app store (Google Play or Stripe) returns them already
            formatted for the viewer's locale and currency, using `playProductId` or `stripePriceId` as the
            key. A self-hosted server with billing switched off answers an empty list, which means "this server
            sells nothing"; hide plan selection in that case rather than treating it as an error.
            """,
        )
        response {
            ok(
                "The plans on offer, cheapest first. May be empty.",
                PlanCatalogResponse(
                    success = true,
                    data =
                        listOf(
                            PlanOption(
                                id = "free",
                                name = "Free",
                                tier = EntitlementTierWire.FREE,
                                storageBytesLimit = 1_073_741_824,
                                backupCountLimit = 1,
                                features = mapOf("cloud_transcription_realtime" to false),
                            ),
                            PlanOption(
                                id = "cloud-standard",
                                name = "LogDate Cloud",
                                tier = EntitlementTierWire.STANDARD,
                                storageBytesLimit = 5_368_709_120,
                                backupCountLimit = 5,
                                features = mapOf("cloud_transcription_realtime" to true, "cloud_transcript_refinement" to true),
                                playProductId = "logdate_cloud_standard_monthly",
                                stripePriceId = "price_1Q3xAbC2dEfGhIjK",
                            ),
                        ),
                ),
            )
        }
    }

    val getAssetLinks: RouteConfig.() -> Unit = {
        publicOperation(
            "getAssetLinks",
            ApiTags.SERVER,
            "Get Android asset links",
            """
            The Android Digital Asset Links statement for this host. Android's Credential Manager fetches it
            before letting an app use passkeys for this relying party, and Android uses it to open
            `https://<host>/...` links in the app instead of the browser. Only the LogDate app package and
            signing certificates the operator configured are listed.

            You never call this from a client; Android does. It is documented so self-hosters can check what
            their server publishes.
            """,
        )
        response {
            ok(
                "The asset-links statements, as Android expects them.",
                listOf(
                    AssetLinkStatement(
                        relation = listOf("delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls"),
                        target =
                            AssetLinkTarget(
                                namespace = "android_app",
                                packageName = "studio.hypertext.logdate",
                                sha256CertFingerprints =
                                    listOf(
                                        "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5",
                                    ),
                            ),
                    ),
                ),
            )
        }
    }

    val getQuota: RouteConfig.() -> Unit = {
        bearerOperation(
            "getQuota",
            ApiTags.QUOTA,
            "Get storage quota",
            """
            How much of the account's storage allowance is used, in bytes. `totalBytes` comes from the plan;
            `usedBytes` counts synced media and backups. Show it as a meter, and check it before a large upload
            to avoid a `402`.

            On a self-hosted server without billing the account is unlimited and `totalBytes` is the largest
            64-bit value. `categories` is currently always empty; the server tracks totals only.
            """,
        )
        response {
            ok("Allowance and usage in bytes.", QuotaUsage(totalBytes = 5_368_709_120, usedBytes = 1_298_403_211, categories = emptyList()))
            bearerUnauthorized(ErrorEnvelope.MESSAGE)
        }
    }

    val createTranscriptionSession: RouteConfig.() -> Unit = {
        bearerOperation(
            "createTranscriptionSession",
            ApiTags.TRANSCRIPTION,
            "Start a transcription session",
            """
            Reserves a short-lived cloud speech-to-text session for one voice note and returns what the client
            needs to stream audio: a session ID, the audio format to send, and, for realtime sessions, a
            provider socket URL with a short-lived `clientSecret`. Audio goes straight from the device to the
            provider; the server never sees it and never exposes its own provider credentials.

            `mode` chooses between `REALTIME` (live captions while recording) and `REFINEMENT` (a second,
            more accurate pass over a finished recording). Each needs the matching feature on the account's
            plan; check **Get the plan and limits** first, or handle `402`.

            > [!NOTE]
            > Limited to {{transcription.sessions}} per account.
            """,
        )
        request {
            jsonBody(
                CloudTranscriptionSessionRequest(
                    noteId = SyncExamples.CONTENT_ID,
                    language = "en-US",
                    mode = CloudTranscriptionMode.REALTIME,
                ),
                "Which note the transcript is for, the language, and the mode.",
            )
        }
        response {
            code(HttpStatusCode.Created) {
                description = "A session is reserved. Connect to `realtimeUrl` with `clientSecret` before it expires."
                body<CloudTranscriptionSessionResponse> {
                    example("Realtime session") {
                        value =
                            CloudTranscriptionSessionResponse(
                                sessionId = "9e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b",
                                noteId = SyncExamples.CONTENT_ID,
                                language = "en-US",
                                mode = CloudTranscriptionMode.REALTIME,
                                streamPath = "/api/v1/transcription/sessions/9e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b/stream",
                                inputFormat = CloudAudioInputFormat.PCM16_MONO_24KHZ,
                                realtimeUrl = "wss://transcribe.example.com/v1/realtime?session=9e1f2a3b",
                                clientSecret =
                                    CloudTranscriptionClientSecret(
                                        value = "ek_live_c3VwZXItc2VjcmV0",
                                        expiresAtEpochSeconds = 1_789_221_851,
                                    ),
                                modelId = "realtime-transcribe-2026-05",
                            )
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                description = "The body is not valid JSON for this request (for example an unknown `mode`). The body is empty."
            }
            bearerUnauthorized(ErrorEnvelope.MESSAGE)
            messageError(
                HttpStatusCode.PaymentRequired,
                ErrorCase(
                    "subscription_required",
                    "The account's plan does not include this transcription mode, or the subscription has lapsed. Show an upgrade prompt.",
                    "cloud transcription requires an active LogDate Cloud subscription",
                ),
            )
            rateLimited(CLOUD_TRANSCRIPTION_SESSION_LIMIT, ErrorEnvelope.MESSAGE)
            messageError(
                HttpStatusCode.ServiceUnavailable,
                ErrorCase(
                    "transcription_unavailable",
                    "This deployment has no transcription provider configured, or the provider is down. Fall back to on-device transcription.",
                    "cloud transcription is unavailable",
                ),
            )
        }
    }

    val resolveResource: RouteConfig.() -> Unit = {
        publicOperation(
            "resolveResource",
            ApiTags.RESOURCES,
            "Resolve a shared link",
            """
            Resolves the opaque ID from a shared LogDate link to the public URL it points at, on the owner's
            own handle domain (for example `https://willie.logdate.app/journal/<id>`). No token needed.

            `kind` says whether the ID is a `journal`, a `note` or a `rewind`. Unknown IDs, and IDs whose owner
            has no handle yet, answer `404` with an empty body.
            """,
        )
        request {
            pathParameter<String>("resourceId") {
                description = "The opaque resource ID from the shared link."
                example("Example") { value = SyncExamples.JOURNAL_ID }
            }
        }
        response {
            ok(
                "Where the resource lives.",
                ResourceResponse(
                    ownerHandle = DocExamples.HANDLE,
                    kind = "journal",
                    canonicalPath = "/journal/${SyncExamples.JOURNAL_ID}",
                    canonicalUrl = "https://${DocExamples.HANDLE}/journal/${SyncExamples.JOURNAL_ID}",
                ),
            )
            code(HttpStatusCode.NotFound) { description = "No resource has that ID, or its owner has no handle. The body is empty." }
            code(HttpStatusCode.TooManyRequests) {
                description =
                    "This network address has looked up too many IDs the server had to search for in the last minute. " +
                    "Wait for `Retry-After` seconds. The body is empty."
                header<String>("Retry-After") { description = "Seconds to wait before the next lookup." }
            }
        }
    }
}
