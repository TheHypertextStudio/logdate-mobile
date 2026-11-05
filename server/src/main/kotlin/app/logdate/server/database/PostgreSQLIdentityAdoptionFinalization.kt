@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionFinalization
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedCopy
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class PostgreSQLIdentityAdoptionFinalization(
    private val source: DataSource,
    private val quota: suspend (Uuid) -> Long,
    private val clock: Clock = Clock.System,
) : IdentityAdoptionFinalization {
    override suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
        commitments: IdentityAdoptionCommitRequest,
    ): IdentityAdoptionActionChallenge? {
        if (!listOf(commitments.manifestDigest, commitments.coverageDigest).all { it.matches(Regex("[0-9a-f]{64}")) }) return null
        val digest =
            when (action) {
                IdentityAdoptionAction.VERIFY -> {
                    if (operation.phase != IdentityAdoptionPhase.FENCED) return null
                    identityAdoptionVerificationDigest(commitments.manifestDigest, commitments.coverageDigest)
                }
                IdentityAdoptionAction.COMMIT -> {
                    if (operation.phase != IdentityAdoptionPhase.VERIFIED) return null
                    identityAdoptionCommitDigest(commitments)
                }
                else -> return null
            }
        return PostgreSQLIdentityAdoptionActions(source, clock).issue(operation, action, digest, clock.now())
    }

    override suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionVerificationRequest,
    ): IdentityAdoptionVerifiedCopy? =
        PostgreSQLIdentityAdoptionVerification(source, clock).verify(
            operation,
            challenge,
            signature,
            request,
            quota(operation.binding.accountId),
        )

    override suspend fun commit(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionCommitRequest,
    ): IdentityAdoptionReceipt? =
        PostgreSQLIdentityAdoptionCommit(source, clock).commit(
            operation,
            challenge,
            signature,
            request,
            quota(operation.binding.accountId),
        )
}
