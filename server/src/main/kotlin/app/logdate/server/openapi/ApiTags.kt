package app.logdate.server.openapi

/**
 * The one canonical set of tag names. Route documentation refers to these constants so a typo
 * cannot create a stray sidebar section.
 */
internal object ApiTags {
    const val SERVER = "Server"
    const val AUTHENTICATION = "Authentication"
    const val ACCOUNT = "Account"
    const val IDENTITY = "Identity"
    const val DEVICES = "Devices"
    const val CONTENTS = "Contents"
    const val JOURNALS = "Journals"
    const val ASSOCIATIONS = "Associations"
    const val LOCATION_HISTORY = "Location history"
    const val DRAFTS = "Drafts"
    const val MOMENTS = "Moments"
    const val MEDIA = "Media"
    const val BACKUPS = "Backups"
    const val SYNC_STATUS = "Sync status"
    const val SPACE_MIGRATION = "Moving to a personal space"
    const val QUOTA = "Quota"
    const val BILLING = "Plans and billing"
    const val TRANSCRIPTION = "Transcription"
    const val DIAGNOSTICS = "Diagnostics"
    const val RESOURCES = "Resources"
    const val OAUTH = "OAuth"
    const val XRPC = "XRPC"
}

internal data class ApiTag(
    val name: String,
    val description: String,
)

internal data class ApiTagGroup(
    val name: String,
    val tags: List<ApiTag>,
)

/**
 * Sidebar structure of the reference, in display order. Descriptions may use the same
 * `{{placeholders}}` as the overview so the numbers always come from the code.
 */
internal val apiTagGroups: List<ApiTagGroup> =
    listOf(
        ApiTagGroup(
            "Getting started",
            listOf(
                ApiTag(
                    ApiTags.SERVER,
                    """
                    Start here. These endpoints answer "what am I talking to?": which deployment this is, what it can do,
                    and which subscription plans it offers. None of them need a token.

                    Call **Describe this server** first. Its `capabilities` list states whether passkeys,
                    cloud transcription and AT Protocol features are switched on for this particular deployment, so your
                    client can hide what is not available instead of discovering it through errors.
                    """.trimIndent(),
                ),
            ),
        ),
        ApiTagGroup(
            "Accounts & identity",
            listOf(
                ApiTag(
                    ApiTags.AUTHENTICATION,
                    """
                    How a person proves who they are and obtains tokens. Every flow ends the same way: an `accessToken`
                    sent on each request and a `refreshToken` exchanged for a new access token when the old one
                    expires. Both are explained in the **Concepts** section of the overview.

                    Passkeys and Google are both first-class ways in. Each passkey flow is a two-step *begin/complete*
                    exchange: the server issues a challenge, the device's authenticator signs it, and the client returns
                    the signature. Sign-up is limited to {{auth.signup}} and sign-in to {{auth.signin}}, per IP address.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.ACCOUNT,
                    """
                    Everything about the account that is currently signed in: profile, passkeys, linked identities, email
                    verification, the plan it is on, and deletion. Every call needs `Authorization: Bearer <accessToken>`.

                    One rule to know before you build a passkey manager: removing the last passkey on an account that has
                    no other way to sign in is refused, so a person can never lock themselves out.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.IDENTITY,
                    """
                    A LogDate account can carry an AT Protocol identity, a `did:` identifier plus a handle such as
                    `alice.logdate.app`, so a journal is portable across servers that speak the protocol. These endpoints
                    manage the signing key behind that identity, its PLC history, and the public `.well-known` documents
                    other servers read to verify it.

                    Most app developers never call these. Self-hosters and people building AT Protocol tooling do. The
                    private key never leaves a device unprotected: every export and import is wrapped with a passphrase.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.DEVICES,
                    """
                    A signed-in device can ask another device on the same account to approve it. The new device
                    displays a short-lived QR and confirmation code; an existing device verifies both and sends
                    an encrypted key envelope. The server relays that envelope without reading the keys, and the
                    new device may consume it only once before the request expires. A new device that is not yet
                    signed in receives its own account session in that envelope, so each device signs out on its own.

                    Account-key envelopes contain only client-encrypted ciphertext. A compatible passkey unlocks
                    them locally; the server never receives a journal key or a passkey unlock secret.

                    A watch links differently: the signed-in phone mints a one-time code and the watch redeems it
                    for its own session, with no sign-in on the watch. That session reaches only the sync routes,
                    and the phone can remove it at any time.
                    """.trimIndent(),
                ),
            ),
        ),
        ApiTagGroup(
            "Sync",
            listOf(
                ApiTag(
                    ApiTags.CONTENTS,
                    """
                    An entry (a note, a photo, a voice recording) is a *content*. This is the heart of sync: create or
                    replace an entry by ID, patch part of it with conflict detection, page through what changed since your
                    last cursor, and delete.

                    Read **Sync model** in the overview before you write a client. The `since` cursor and the
                    `versionConstraint` rule are what stop two devices from silently overwriting each other.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.JOURNALS,
                    """
                    A journal is a named collection that entries can belong to. Journals sync exactly like contents:
                    an idempotent `PUT` by ID, a `PATCH` with optional conflict detection, a `since` change feed, and
                    soft deletes that arrive as tombstones in the `deletions` list.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.ASSOCIATIONS,
                    """
                    An association links one entry to one journal; an entry can sit in many journals. Associations are
                    addressed by the pair `{journalId}/{contentId}`, uploaded in batches, and paged with the same `since`
                    cursor as everything else. Deleting an association never touches the entry or the journal.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.DRAFTS,
                    """
                    A draft is an entry someone is still writing. Syncing drafts lets a person start a note on their
                    phone and finish it on their laptop. Drafts have their own, simpler change feed and are meant to be
                    short-lived: delete the draft once the finished entry has been saved as a content.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.MOMENTS,
                    """
                    A marked moment is a point in time someone flagged, usually with one tap on a watch, to fill in
                    later. Moments have a collection of their own: entry and journal feeds never include them, so
                    clients that do not know moments never see one. Everything but the times is encrypted on the
                    device. Use these endpoints only when the server descriptor lists `momentsV1`.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.LOCATION_HISTORY,
                    "Encrypted observations, activity, places, corrections and memory links. Each account has its own " +
                        "version cursor; permanent tombstones let offline devices learn about deletions.",
                ),
                ApiTag(
                    ApiTags.MEDIA,
                    """
                    Photos, videos and audio recordings attached to entries. Uploads use `multipart/form-data` (see
                    **Concepts**) and count against the account's storage quota.

                    The media ID is derived from the account, the entry and the file name, so retrying an interrupted
                    upload replaces the same file instead of creating a duplicate and charging the quota twice. Uploads
                    are limited to {{media.upload}} per account.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.BACKUPS,
                    """
                    An encrypted snapshot of a whole device's data, uploaded as one blob together with a manifest that
                    describes it. Backups are what the app restores from on a new phone.

                    Uploads are limited to {{backup.upload}} per account and count against both the storage quota and
                    the plan's backup-count limit.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.SPACE_MIGRATION,
                    """
                    An account created before personal spaces keeps its journal in legacy storage, the per-kind endpoints
                    above. The server can't read the journal, so a device moves it: it uploads every record sealed into the
                    account's personal space, has the server copy the media it already encrypted, holds legacy writes while
                    it reads everything back and compares it with its own copy, and then switches the account. Until the
                    switch nothing changes for legacy sync, and the legacy copy is kept for 30 days after it, during which the
                    account can be switched back. Use these calls only when the server descriptor lists
                    `personalSpaceMigrationV1`.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.SYNC_STATUS,
                    """
                    A quick picture of the account's synced data: how many entries, journals and associations the server
                    holds and the newest version it has seen. Useful for a "last synced" screen, or for determining
                    whether a client that appears to be missing data ever uploaded it.
                    """.trimIndent(),
                ),
            ),
        ),
        ApiTagGroup(
            "Cloud services",
            listOf(
                ApiTag(
                    ApiTags.QUOTA,
                    """
                    How much of the account's storage allowance is in use. Limits come from the plan the account is on.
                    On a self-hosted server without billing the quota is unlimited, and `totalBytes` is reported as the
                    largest 64-bit value rather than as a special marker.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.BILLING,
                    """
                    Buying, changing and cancelling a LogDate Cloud plan. A plan is paid for at the payment provider's
                    own pages, which these calls open; the server then reads the result back from the provider, so a
                    plan changes only when a payment has.

                    Every answer says whether purchases are available on this server. LogDate Cloud Plus can be bought
                    as soon as they are. The base plan is free until launch, and accounts that join before then keep
                    it at no charge afterwards.
                    A self-hosted server without billing answers each of them with `404`.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.TRANSCRIPTION,
                    """
                    Cloud speech-to-text for voice entries. The server issues the client a short-lived session it uses to
                    talk to the transcription provider directly, so audio never passes through LogDate Cloud.

                    Needs an active subscription with the matching entitlement and is limited to
                    {{transcription.sessions}} per account.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.DIAGNOSTICS,
                    """
                    Sync diagnostic reports a person chose to share from the app. Reports carry only fixed event codes,
                    counters and random IDs, never journal content or credentials, and only the account that sent a
                    report can read or delete it.

                    Reporting is off unless the operator switches it on. Check for `diagnosticReportsV1` in
                    **Describe this server** first; a server without it answers `503 DIAGNOSTIC_REPORTS_UNAVAILABLE`.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.RESOURCES,
                    """
                    Resolve the opaque resource ID from a shared link to the public URL it points at. No token needed,
                    and safe to call from a link-preview service.
                    """.trimIndent(),
                ),
            ),
        ),
        ApiTagGroup(
            "AT Protocol",
            listOf(
                ApiTag(
                    ApiTags.OAUTH,
                    """
                    OAuth 2.0 for AT Protocol clients, with DPoP-bound tokens and the `atproto` scope. If you are building
                    a LogDate app client you do not need this section: use **Authentication** instead.

                    If you are building a third-party AT Protocol client that should act on a LogDate-hosted repository,
                    the flow is: read the discovery metadata, push your authorization request with `POST /oauth/par`,
                    send the person to `/oauth/authorize`, then exchange the code at `POST /oauth/token`. Every token
                    request carries a DPoP proof; **Concepts** explains what that is in one paragraph.
                    """.trimIndent(),
                ),
                ApiTag(
                    ApiTags.XRPC,
                    """
                    The AT Protocol's HTTP RPC surface, served under `/xrpc/`. Method names are lexicon IDs such as
                    `com.atproto.repo.getRecord`. LogDate Cloud implements the subset a personal data server needs for
                    identity, sessions, repository reads and writes, and blobs.

                    It is deliberately not a complete PDS. There is no firehose, no `applyWrites`, and no invite codes,
                    and every method except handle resolution and the two describe calls answers `501 Unsupported` on a
                    deployment that has AT Protocol switched off.
                    """.trimIndent(),
                ),
            ),
        ),
    )
