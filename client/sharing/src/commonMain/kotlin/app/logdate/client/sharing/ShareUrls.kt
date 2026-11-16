package app.logdate.client.sharing

import kotlin.uuid.Uuid

internal const val LOGDATE_APEX_HOST = "logdate.app"

/**
 * Builds the public web URL for a shared journal.
 *
 * When [ownerHandle] is the user's full ATProto handle (e.g.
 * `alice.logdate.app`), emits the canonical tenant-subdomain
 * shape: `https://alice.logdate.app/journal/<id>`. iOS Universal
 * Links and Android App Links claim this directly via the wildcard
 * subdomain coverage in the entitlement and intent-filter, with no
 * redirect hop, and the recipient's link-preview / OG metadata loads on
 * the first fetch.
 *
 * When [ownerHandle] is null, falls back to the apex shape
 * (`https://logdate.app/journal/<id>`); the web proxy 308s that to the
 * canonical URL when cloud knows the share's owner. See
 * `logdate-web/docs/deep-linking.md` for the full contract.
 *
 * Threading the handle through call sites — the auth-state lookup at the
 * point of share — is item N-4 in the deep-link coordination doc; until
 * that plumbing lands, every caller passes `null` and we rely on the
 * apex 308. This helper is the single point that flips when the
 * plumbing arrives.
 */
internal fun journalShareUrl(
    journalId: Uuid,
    ownerHandle: String? = null,
): String {
    val host = ownerHandle?.takeIf(String::isNotEmpty) ?: LOGDATE_APEX_HOST
    return "https://$host/journal/$journalId"
}
