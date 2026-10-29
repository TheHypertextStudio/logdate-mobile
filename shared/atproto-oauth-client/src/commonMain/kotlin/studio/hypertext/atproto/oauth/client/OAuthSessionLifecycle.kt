package studio.hypertext.atproto.oauth.client

import studio.hypertext.atproto.identity.AtprotoDid

/** Bounded evidence retained by stores after a session ends; never a raw provider description. */
public enum class OAuthSessionEndReason {
    EXPIRED,
    REVOKED,
    REJECTED,
    AUTHORITY_CHANGED,
}

/** Only CONFIRMED means a provider actually confirmed revocation. */
public enum class OAuthConditionalRevocation {
    SKIPPED,
    NO_SESSION,
    CONFIRMED,
}

/** The identity's verified hosting authority changed; this is not evidence of revoked consent. */
public class OAuthSessionAuthorityChangedException(
    did: AtprotoDid,
    cause: Throwable? = null,
) : OAuthSessionEndedException(did, "The OAuth authority for $did changed", cause)
