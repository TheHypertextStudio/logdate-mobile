package app.logdate.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class DeploymentKind {
    FIRST_PARTY,
    SELF_HOSTED,
}

@Serializable
enum class ServerCapability {
    AUTH_PASSKEY,
    SYNC_CONTENT,
    SYNC_MEDIA,
    ATPROTO_IDENTITY,
    ATPROTO_OAUTH,
    BILLING_SUBSCRIPTIONS,
    MANAGED_QUOTA,
    CLOUD_TRANSCRIPTION,
}

/** Additive protocol flags that old clients can safely ignore. */
object ServerProtocolFeature {
    /** Confidential Bluesky sign-in followed by an account-bound LogDate passkey ceremony. */
    const val BLUESKY_SIGN_IN_V1 = "blueskySignInV1"
    const val ATPROTO_CONNECTION_V1 = "atprotoConnectionV1"
    const val CANONICAL_OWNER_BINDING_V1 = "canonicalOwnerBindingV1"
    const val JOURNAL_MERGE_V1 = "journalMergeV1"
    const val RICH_DRAFTS_V1 = "richDraftsV1"
    const val DIAGNOSTIC_REPORTS_V1 = "diagnosticReportsV1"

    /**
     * Devices link to an account with a one-time code and no sign-in (`/auth/devices`). Their tokens contain
     * `sid` and `scp=sync`, refreshes check that the session still exists, and removed devices answer
     * `401 DEVICE_SESSION_REVOKED`.
     */
    const val DEVICE_LINKING_V1 = "deviceLinkingV1"

    /**
     * `?ifAbsent=true` on `PUT /contents/{id}`, `PUT /journals/{id}`, `PUT /moments/{id}` and
     * `POST /associations` creates only: a live ID answers `412` with `details.serverVersion`, a deleted
     * one answers `410` with `details.deletedAt`, and nothing is written. Also `HEAD /contents/{id}`.
     * Servers without this flag ignore `ifAbsent` and overwrite, so never send it to them.
     */
    const val CONDITIONAL_CREATE_V1 = "conditionalCreateV1"

    /** Marked moments sync through `/moments`, a collection of their own that the entry feeds never carry. */
    const val MOMENTS_V1 = "momentsV1"

    /**
     * The server publishes hosted `did:plc` operations to the PLC directory, so identity changes
     * that need a published operation -- rotating the signing key, registering a recovery key,
     * recovering with one -- can succeed. Without it those requests are refused, and clients must
     * not offer them.
     */
    const val ATPROTO_PLC_PUBLISHING_V1 = "atprotoPlcPublishingV1"

    /**
     * People can register their own recovery key on their hosted identity
     * (`POST /identity/plc/recovery-key`) and use it to replace the server's key
     * (`POST /identity/recovery-key/recover/prepare` and `/complete`). The recovery key is listed
     * ahead of the server's key, so it can overrule it. This works without
     * [ATPROTO_PLC_PUBLISHING_V1]. Without this flag those requests are refused, and clients must not
     * offer them.
     */
    const val ATPROTO_RECOVERY_KEYS_V1 = "atprotoRecoveryKeysV1"

    /** Legacy flag retained for compatibility; servers never advertise server custody of keys. */
    const val ACCOUNT_KEY_VAULT_V1 = "accountKeyVaultV1"

    /** Account-bound passkey envelopes contain ciphertext only, unlocked locally with the PRF. */
    const val ENCRYPTED_ACCOUNT_KEYS_V1 = "encryptedAccountKeysV1"

    /**
     * The server hosts shared journals, so a journal link opens a readable copy for the people it
     * was shared with. Without it a journal link reaches no one, and clients must not offer one.
     */
    const val JOURNAL_SHARE_LINKS_V1 = "journalShareLinksV1"

    /**
     * The server stores each account's personal space through the owner-only `app.logdate.space.*`
     * XRPC methods. Every record in it is sealed, so the server holds only ciphertext. Without it
     * those methods answer `501 MethodNotImplemented`, and clients must not keep a space here.
     */
    const val PERSONAL_SPACE_V1 = "personalSpaceV1"

    /**
     * The server moves an existing account's journal from legacy storage into its personal space
     * through `/api/v1/space-migration`: it registers media the apps already encrypted as space
     * files, holds legacy writes while a device checks its copy, and switches the account's storage
     * mode once that check passes. Only offered together with [PERSONAL_SPACE_V1].
     */
    const val PERSONAL_SPACE_MIGRATION_V1 = "personalSpaceMigrationV1"
}

@Serializable
data class ServerPasskeyConfig(
    val rpId: String,
    val rpName: String,
)

@Serializable
data class ServerDescriptor(
    val serverOrigin: String,
    val apiBaseUrl: String,
    val apiVersion: String = "v1",
    val deploymentKind: DeploymentKind,
    val displayName: String,
    val handleDomain: String? = null,
    val passkey: ServerPasskeyConfig? = null,
    val capabilities: List<ServerCapability> = emptyList(),
    val protocolFeatures: List<String> = emptyList(),
    val privacyPolicyUrl: String? = null,
    val termsOfServiceUrl: String? = null,
) {
    fun hasCapability(capability: ServerCapability): Boolean = capabilities.contains(capability)

    fun hasProtocolFeature(feature: String): Boolean = protocolFeatures.contains(feature)
}

@Serializable
data class ServerInfoResponse(
    val success: Boolean,
    val data: ServerDescriptor,
)
