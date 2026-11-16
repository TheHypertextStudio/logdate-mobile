package app.logdate.server.openapi.schemadocs

import app.logdate.server.openapi.SchemaDoc

private const val SUCCESS_FLAG = "Always `true` on a successful response."

/** Prose for the server-info, plans, billing, asset-links, quota, transcription and resource schemas. */
internal object CloudSchemaDocs {
    val docs: Map<String, SchemaDoc> =
        mapOf(
            "MessageErrorResponse" to
                SchemaDoc(
                    "The bare error envelope used by the Quota and Transcription endpoints.",
                    mapOf("error" to "A short sentence saying what went wrong. Match on the HTTP status, not on this text."),
                ),
            // ---- Server -----------------------------------------------------------------------------
            "ServerInfoResponse" to
                SchemaDoc("What this deployment is and can do.", mapOf("success" to SUCCESS_FLAG, "data" to "The description.")),
            "ServerDescriptor" to
                SchemaDoc(
                    "Everything a client needs to know about a deployment before making any other call.",
                    mapOf(
                        "serverOrigin" to "The server's public origin, such as `https://cloud.logdate.app`.",
                        "apiBaseUrl" to "Prefix for the versioned API: `serverOrigin` plus `/api/v1`.",
                        "apiVersion" to "The API version segment, currently `v1`.",
                        "deploymentKind" to "Whether this is LogDate Cloud or someone's own server.",
                        "displayName" to "Human-readable name to show for this server.",
                        "handleDomain" to
                            "Domain under which AT Protocol handles are issued (`alice.<handleDomain>`), or `null` if identities are off.",
                        "passkey" to "The WebAuthn relying party to use for passkeys, or `null` if passkeys are off.",
                        "capabilities" to "Features switched on for this deployment. Hide anything not listed.",
                        "protocolFeatures" to "Additive behaviors newer servers have; older clients can ignore unknown ones.",
                        "privacyPolicyUrl" to "Where the privacy policy lives, or `null`.",
                        "termsOfServiceUrl" to "Where the terms of service live, or `null`.",
                    ),
                ),
            "DeploymentKind" to
                SchemaDoc(
                    "Who runs the server.",
                    enumValues =
                        mapOf(
                            "FIRST_PARTY" to "LogDate Cloud, run by LogDate.",
                            "SELF_HOSTED" to "Someone's own copy of the server.",
                        ),
                ),
            "ServerCapability" to
                SchemaDoc(
                    "A feature a deployment can have switched on.",
                    enumValues =
                        mapOf(
                            "AUTH_PASSKEY" to "Passkey sign-up and sign-in.",
                            "SYNC_CONTENT" to "Entry, journal, link and draft sync.",
                            "SYNC_MEDIA" to "Media and backup upload and download.",
                            "ATPROTO_IDENTITY" to "AT Protocol identities (DIDs and handles) for accounts.",
                            "ATPROTO_OAUTH" to "OAuth for third-party AT Protocol clients.",
                            "BILLING_SUBSCRIPTIONS" to "Paid plans; **List the plans on offer** returns something.",
                            "MANAGED_QUOTA" to "Storage quotas are enforced.",
                            "CLOUD_TRANSCRIPTION" to "Cloud speech-to-text sessions.",
                        ),
                ),
            "ServerPasskeyConfig" to
                SchemaDoc(
                    "The WebAuthn relying party for passkeys.",
                    mapOf(
                        "rpId" to "Relying party ID (a domain). Passkeys are bound to it.",
                        "rpName" to "Relying party name shown by the platform.",
                    ),
                ),
            // ---- Plans ---------------------------------------------------------------------------------
            "PlanCatalogResponse" to
                SchemaDoc("The plans on offer.", mapOf("success" to SUCCESS_FLAG, "data" to "Plans, cheapest first. May be empty.")),
            "PlanOption" to
                SchemaDoc(
                    "A plan a person could subscribe to.",
                    mapOf(
                        "id" to "Stable plan identifier such as `cloud` or `cloud_plus`.",
                        "name" to "Display name.",
                        "tier" to "Coarse tier for UI copy.",
                        "storageBytesLimit" to "Storage the plan includes, in bytes, or `null` for unlimited.",
                        "backupCountLimit" to "Backups the plan keeps, or `null` for unlimited.",
                        "features" to "Feature flags by name, `true` when the plan includes the feature.",
                        "playProductId" to "Google Play subscription product ID, or `null` if not sold there.",
                        "stripePriceId" to "Always `null`. Prices are in `prices`.",
                        "prices" to "What the plan costs for each interval it can bill at. Empty when the payment provider gave none.",
                        "extraStorage" to "Storage sold past `storageBytesLimit`, or `null` when the plan stops there.",
                        "onSale" to "Whether the server will sell this plan right now. Offer a purchase only when it is `true`.",
                    ),
                ),
            "PlanPrice" to
                SchemaDoc(
                    "What a plan costs for one billing interval, exactly as the payment provider charges it.",
                    mapOf(
                        "interval" to "How often this price bills.",
                        "amountMinor" to "The amount in the currency's smallest unit: cents for USD.",
                        "currency" to "ISO 4217 currency code in lower case, such as `usd`.",
                    ),
                ),
            "BillingInterval" to
                SchemaDoc(
                    "How often a plan bills.",
                    enumValues = mapOf("MONTH" to "Every month.", "YEAR" to "Every year."),
                ),
            "ExtraStorageOffer" to
                SchemaDoc(
                    "Storage a plan sells past what it includes, one step at a time, charged each month only while it is used.",
                    mapOf(
                        "stepBytes" to "The size of one step, in bytes.",
                        "monthlyPrice" to "The monthly charge for one step, or `null` when the payment provider gave none.",
                    ),
                ),
            // ---- Billing -----------------------------------------------------------------------------
            "CloudSubscription" to
                SchemaDoc(
                    "An account's LogDate Cloud plan and how it is paid for.",
                    mapOf(
                        "planId" to "The plan the account is on, or `null` when it has none.",
                        "status" to "Whether payments are current.",
                        "includedWithBeta" to "The account has its current plan at no charge because it joined during the beta.",
                        "betaMember" to "The account joined during the beta, so LogDate Cloud never costs it anything.",
                        "freePlanId" to
                            "The plan the account has at no charge whenever it pays for nothing, or `null`. It cannot be bought.",
                        "interval" to "How often the plan bills, or `null` when nothing is billed.",
                        "renewsAtEpochSeconds" to
                            "When the plan next renews, in seconds since the epoch. `null` when nothing is billed or the plan is ending.",
                        "endsAtEpochSeconds" to "When a cancelled plan stops, in seconds since the epoch. `null` unless it was cancelled.",
                        "storageUsedBytes" to "Bytes the account stores now.",
                        "storageIncludedBytes" to "Bytes the plan includes, `null` for no limit, `0` when there is no plan.",
                        "extraStorageSteps" to "Whole steps of extra storage in use past `storageIncludedBytes`.",
                        "purchasesAvailable" to
                            "The server can start, change and cancel a plan right now. When `false`, show no purchase action.",
                        "paymentDetailsAvailable" to
                            "The account has billing details at the payment provider, so its payment page can be opened.",
                    ),
                ),
            "CloudPlanSelection" to
                SchemaDoc(
                    "A plan and how often to bill it.",
                    mapOf("planId" to "A plan identifier from the catalog.", "interval" to "How often to bill."),
                ),
            "StripeWebhookReceipt" to
                SchemaDoc("Tells Stripe its notification was accepted.", mapOf("received" to "Always `true`.")),
            "BillingPage" to
                SchemaDoc(
                    "A page at the payment provider to open in a browser.",
                    mapOf("url" to "The page's address. It is short-lived and for this account only."),
                ),
            // ---- Asset links ----------------------------------------------------------------------------
            "AssetLinkStatement" to
                SchemaDoc(
                    "One Android Digital Asset Links statement.",
                    mapOf("relation" to "What the app is allowed to do for this host.", "target" to "The app the statement is about."),
                ),
            "AssetLinkTarget" to
                SchemaDoc(
                    "The Android app a statement grants permissions to.",
                    mapOf(
                        "namespace" to "Always `android_app`.",
                        "package_name" to "The app's package name.",
                        "sha256_cert_fingerprints" to "SHA-256 fingerprints of the signing certificates, colon-separated hex.",
                    ),
                ),
            // ---- Quota -----------------------------------------------------------------------------------
            "QuotaUsage" to
                SchemaDoc(
                    "Storage allowance and usage.",
                    mapOf(
                        "totalBytes" to
                            "The plan's included storage plus any extra storage in use, in bytes. The largest 64-bit value means unlimited.",
                        "usedBytes" to "Bytes in use across media and backups.",
                        "categories" to "Per-category breakdown. Currently always empty.",
                        "isOverQuota" to "Not sent by the server today. Compute it as `usedBytes > totalBytes`.",
                        "usagePercentage" to
                            "Not sent by the server today. Compute it as `usedBytes / totalBytes`, treating the largest 64-bit " +
                            "`totalBytes` as unlimited rather than dividing.",
                    ),
                ),
            "QuotaCategoryUsage" to
                SchemaDoc(
                    "Usage for one category of stored object. Not populated yet.",
                    mapOf("category" to "The category.", "sizeBytes" to "Bytes in use.", "objectCount" to "How many objects."),
                ),
            "QuotaContentType" to
                SchemaDoc(
                    "Categories of stored data.",
                    enumValues =
                        mapOf(
                            "TEXT_NOTES" to "Text entries.",
                            "IMAGE_NOTES" to "Photo entries.",
                            "VIDEO_NOTES" to "Video entries.",
                            "VOICE_NOTES" to "Voice recordings.",
                            "JOURNAL_DATA" to "Journals and links.",
                            "USER_PROFILE" to "Profile data.",
                            "ATTACHMENTS" to "Other attached files.",
                        ),
                ),
            // ---- Transcription -----------------------------------------------------------------------------
            "CloudTranscriptionSessionRequest" to
                SchemaDoc(
                    "What to transcribe and how.",
                    mapOf(
                        "noteId" to "The voice entry the transcript belongs to.",
                        "language" to "BCP-47 language tag, such as `en-US`.",
                        "mode" to "Live captions or a second, more accurate pass.",
                    ),
                ),
            "CloudTranscriptionMode" to
                SchemaDoc(
                    "What kind of transcription session to open.",
                    enumValues =
                        mapOf(
                            "REALTIME" to "Live captions while recording; needs the `cloud_transcription_realtime` feature.",
                            "REFINEMENT" to "A slower, more accurate pass over a finished recording; needs `cloud_transcript_refinement`.",
                        ),
                ),
            "CloudTranscriptionSessionResponse" to
                SchemaDoc(
                    "How to stream audio for a reserved session.",
                    mapOf(
                        "sessionId" to "LogDate's ID for the session; quote it when reporting problems.",
                        "noteId" to "The voice entry the session is for.",
                        "language" to "The language the session accepts.",
                        "mode" to "The mode reserved.",
                        "streamPath" to "Path on this server for proxied streaming, when the deployment proxies audio.",
                        "inputFormat" to "The PCM format to send.",
                        "provider" to "Always `logdate-cloud`; the underlying vendor is not exposed.",
                        "realtimeUrl" to
                            "WebSocket URL to connect to for a realtime session, or `null` when audio is proxied through `streamPath`.",
                        "clientSecret" to "Short-lived credential for `realtimeUrl`, or `null` when not needed.",
                        "modelId" to "Which recognition model was chosen, for transcript metadata; may be `null`.",
                    ),
                ),
            "CloudAudioInputFormat" to
                SchemaDoc(
                    "PCM audio formats the recognizer accepts.",
                    enumValues =
                        mapOf(
                            "PCM16_MONO_16KHZ" to "16-bit signed little-endian, one channel, 16 000 samples per second.",
                            "PCM16_MONO_24KHZ" to "16-bit signed little-endian, one channel, 24 000 samples per second.",
                        ),
                ),
            "CloudTranscriptionClientSecret" to
                SchemaDoc(
                    "A credential scoped to one realtime session.",
                    mapOf(
                        "value" to "The secret to present when connecting.",
                        "expiresAtEpochSeconds" to "When it expires, as seconds since the Unix epoch.",
                    ),
                ),
            // ---- Resources ----------------------------------------------------------------------------------
            "ResourceResponse" to
                SchemaDoc(
                    "Where a shared resource lives.",
                    mapOf(
                        "ownerHandle" to "The owner's handle, which is also the host the resource is served from.",
                        "kind" to "`journal`, `note` or `rewind`.",
                        "canonicalPath" to "Path of the resource on the owner's host.",
                        "canonicalUrl" to "The full public URL.",
                    ),
                ),
        )
}
