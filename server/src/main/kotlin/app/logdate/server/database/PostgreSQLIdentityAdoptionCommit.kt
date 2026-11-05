@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionActionProof
import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.coroutines.withContext
import javax.sql.DataSource
import kotlin.time.Clock

/** No provider or public-repository writes are part of a private identity cutover. */
internal class PostgreSQLIdentityAdoptionCommit(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
) {
    suspend fun commit(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionCommitRequest,
        limitBytes: Long,
    ): IdentityAdoptionReceipt? {
        val prepared = PreparedIdentityAdoptionCommit(request, signature)
        stagingCheck(limitBytes >= 0, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        if (operation.phase != IdentityAdoptionPhase.VERIFIED ||
            challenge.action != IdentityAdoptionAction.COMMIT ||
            challenge.bodyDigest != prepared.bodyDigest ||
            prepared.signatureDigest == null
        ) {
            return null
        }
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                listOf(operation.binding.sourceDid, operation.binding.targetDid).sorted().forEach { lockIdentityClaim(it) }
                val rows = IdentityAdoptionCommitRows(this, operation)
                if (!rows.lockAccount()) return@blueskyTransaction null
                rows.replay(challenge, prepared)?.let { return@blueskyTransaction it }
                val now = clock.now()
                val key = with(actions) { currentKey(operation, now) } ?: return@blueskyTransaction null
                if (!rows.targetAvailable() || !IdentityAdoptionActionProof.verify(operation, challenge, key, signature)) {
                    return@blueskyTransaction null
                }
                if (!with(actions) { consumeChallenge(operation, challenge, now) }) return@blueskyTransaction null
                val proof = IdentityAdoptionVerificationRows(this, operation).read(key.origin)
                stagingCheck(
                    proof != null &&
                        proof.manifestDigest == request.manifestDigest &&
                        proof.coverageDigest == request.coverageDigest,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                val verified = requireNotNull(proof)
                val coverage = PostgreSQLIdentityAdoptionCoverage(source).readLocked(this, operation, now)
                stagingCheck(coverage != null, IdentityAdoptionStagingFailure.CONFLICT)
                val rebound = rebindIdentityAdoptionCoverage(requireNotNull(coverage), operation, verified.sourceRevision)
                stagingCheck(
                    rebound.digest == verified.coverageDigest && rebound.source.generation == verified.generation,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                stagingCheck(!hasPendingIdentityAdoptionMutation(operation.binding.accountId), IdentityAdoptionStagingFailure.CONFLICT)
                IdentityAdoptionPromotion(this, operation, verified.generation).promote()
                val finished = clock.now()
                stagingCheck(with(actions) { currentKey(operation, finished) } != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                requireIdentityAdoptionStagingRoom(operation, 0, limitBytes, finished)
                rows.finish(challenge, prepared, finished)
            }
        }
    }
}
