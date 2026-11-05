package app.logdate.server.database

import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_EMPTY_BODY_DIGEST
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionLifecycle
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import javax.sql.DataSource
import kotlin.time.Clock

internal class PostgreSQLIdentityAdoptionLifecycle(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
) : IdentityAdoptionLifecycle {
    override suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
    ): IdentityAdoptionActionChallenge? {
        if (action !in ACTIONS) return null
        return PostgreSQLIdentityAdoptionActions(source, clock).issue(operation, action, IDENTITY_ADOPTION_EMPTY_BODY_DIGEST, clock.now())
    }

    override suspend fun apply(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> {
        if (challenge.action !in ACTIONS || challenge.bodyDigest != IDENTITY_ADOPTION_EMPTY_BODY_DIGEST) {
            return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
        }
        return PostgreSQLIdentityAdoptionRepository(source, clock).transitionWithProof(operation, challenge, signature, clock.now())
    }

    override suspend fun abandon(operation: IdentityAdoptionOperation): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        PostgreSQLIdentityAdoptionRepository(source, clock).abandonUnconfirmed(operation, clock.now())

    private companion object {
        val ACTIONS = setOf(IdentityAdoptionAction.HOLD, IdentityAdoptionAction.RENEW, IdentityAdoptionAction.CANCEL)
    }
}
