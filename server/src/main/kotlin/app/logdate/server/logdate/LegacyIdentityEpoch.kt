package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException

/** Unversioned adapters cannot silently accept authority from an epoch they cannot check. */
internal fun requireLegacyIdentityEpoch(identityEpoch: Long) {
    if (identityEpoch != 0L) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
}
