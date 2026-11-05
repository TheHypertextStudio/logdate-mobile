@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.identity.IdentityAdoptionActionChallengeView
import app.logdate.shared.model.identity.IdentityAdoptionExpectedOperation
import java.util.Base64
import kotlin.uuid.Uuid

internal fun finalizationOperation(
    account: Account,
    current: IdentityAdoptionOperation,
    expected: IdentityAdoptionExpectedOperation,
    phase: IdentityAdoptionPhase,
    replayCommit: Boolean = false,
): IdentityAdoptionOperation? {
    val b = current.binding
    if (account.id != b.accountId ||
        b.deviceId != expected.deviceId ||
        b.sourceDid != expected.sourceDid ||
        b.targetDid != expected.targetDid ||
        b.sourceEpoch != expected.sourceEpoch ||
        expected.expectedRevision <= 0
    ) {
        return null
    }
    if (current.phase == phase &&
        current.revision == expected.expectedRevision &&
        account.identityKind == AccountIdentityKind.HOSTED &&
        account.did == b.sourceDid &&
        account.identityEpoch == b.sourceEpoch
    ) {
        return current
    }
    if (replayCommit &&
        phase == IdentityAdoptionPhase.VERIFIED &&
        current.phase == IdentityAdoptionPhase.COMMITTED &&
        b.sourceEpoch < Long.MAX_VALUE &&
        expected.expectedRevision < Long.MAX_VALUE &&
        current.revision == expected.expectedRevision + 1 &&
        account.identityKind == AccountIdentityKind.EXTERNAL &&
        account.did == b.targetDid &&
        account.identityEpoch == b.sourceEpoch + 1
    ) {
        // Only the commit store's persisted exact-proof replay can authorize this historical revision.
        return current.copy(revision = expected.expectedRevision, phase = IdentityAdoptionPhase.VERIFIED)
    }
    return null
}

internal fun finalizationChallenge(
    value: IdentityAdoptionActionChallengeView,
    action: IdentityAdoptionAction,
    signature: String,
): IdentityAdoptionActionChallenge? {
    val id = value.id.takeIf { it.length == 36 }?.let(Uuid::parseOrNull) ?: return null
    if (value.action.name != action.name ||
        !value.nonce.matches(Regex("[A-Za-z0-9_-]{43}")) ||
        !value.bodyDigest.matches(Regex("[0-9a-f]{64}")) ||
        !signature.matches(Regex("[A-Za-z0-9_-]{86}"))
    ) {
        return null
    }
    return IdentityAdoptionActionChallenge(id, value.nonce, action, value.bodyDigest)
}

internal fun finalizationManifest(value: String): ByteArray? {
    if (value.length !in 2..21846 || !value.matches(Regex("[A-Za-z0-9_-]+"))) return null
    return try {
        Base64.getUrlDecoder().decode(value).takeIf {
            it.size in 1..16384 && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
