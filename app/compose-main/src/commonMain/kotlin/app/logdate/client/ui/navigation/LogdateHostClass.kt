package app.logdate.client.ui.navigation

/**
 * Result of [classifyLogdateHost] — covers the three cases the deep-link
 * resolvers need to distinguish before routing on the URL path.
 */
sealed class LogdateHostClass {
    /** The apex marketing host (e.g. `logdate.app`). Path scoping decides whether to claim. */
    data object Apex : LogdateHostClass()

    /**
     * A tenant subdomain (e.g. `alice.logdate.app`) where the
     * leftmost label is a syntactically valid handle and not on the
     * reserved list. The handle is exposed for callers that want to
     * surface owner context (currently unused — resolvers route to the
     * existing nav keys regardless of handle, see N-3 in
     * `logdate-web/docs/deep-linking.md`).
     */
    data class TenantClaimed(
        val handle: String,
    ) : LogdateHostClass()

    /**
     * Everything else: reserved subdomains (`app.logdate.app`,
     * `api.logdate.app`, …), syntactically invalid handles, or
     * unrelated hosts.
     */
    data object Other : LogdateHostClass()
}

/**
 * Subdomains that the apex AASA + assetlinks contracts in `logdate-web`
 * explicitly reserve for non-portal use (auth, cloud, internal services).
 * Mirrors `RESERVED_SUBDOMAINS` in `logdate-web/apps/web/src/lib/site.ts` —
 * keep in sync, drift is silently broken Universal Link claims for those
 * hosts. iOS exclusion is enforced via AASA host-scoped components; Android
 * App Links can only filter by host pattern, so we reject these here.
 */
private val RESERVED_SUBDOMAIN_LABELS =
    setOf(
        "www",
        "app",
        "cloud",
        "api",
        "studio",
        "admin",
        "mail",
        "blog",
        "docs",
        "status",
    )

/**
 * Classifies a host into [LogdateHostClass.Apex], a
 * [LogdateHostClass.TenantClaimed] tenant portal, or
 * [LogdateHostClass.Other].
 *
 * `origin` is the apex domain — `logdate.app` in production. Tenant
 * subdomains must end with `.$origin` and have a single non-empty
 * leftmost label that's a valid DNS-shaped handle (alphanumerics and
 * hyphens, not starting/ending with a hyphen) and not on the reserved
 * list.
 */
fun classifyLogdateHost(
    host: String,
    origin: String,
): LogdateHostClass {
    if (host == origin) return LogdateHostClass.Apex
    if (!host.endsWith(".$origin")) return LogdateHostClass.Other
    val leftmost = host.removeSuffix(".$origin")
    if (leftmost.isEmpty() || leftmost.contains('.')) return LogdateHostClass.Other
    if (leftmost in RESERVED_SUBDOMAIN_LABELS) return LogdateHostClass.Other
    val valid =
        leftmost.all { it.isLetterOrDigit() || it == '-' } &&
            !leftmost.startsWith('-') &&
            !leftmost.endsWith('-')
    return if (valid) LogdateHostClass.TenantClaimed(leftmost) else LogdateHostClass.Other
}
