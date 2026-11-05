package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedCopy
import kotlinx.coroutines.withContext
import javax.sql.DataSource
import kotlin.time.Clock

/** VERIFY is private copy authority only; canonical identity promotion is a separate transaction. */
internal class PostgreSQLIdentityAdoptionVerification(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
) {
    suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionVerificationRequest,
        limitBytes: Long,
    ): IdentityAdoptionVerifiedCopy? {
        val prepared = prepareIdentityAdoptionVerification(request)
        stagingCheck(limitBytes >= 0, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        if (challenge.action != IdentityAdoptionAction.VERIFY || challenge.bodyDigest != prepared.bodyDigest) return null
        val now = clock.now()
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return actions.consume(operation, challenge, signature, now) {
            val key = with(actions) { currentKey(operation, now) }
            stagingCheck(key != null && operation.phase == IdentityAdoptionPhase.FENCED, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            requireIdentityAdoptionManifestBinding(operation, requireNotNull(key).origin, prepared.manifest)
            val coverage = PostgreSQLIdentityAdoptionCoverage(source).readLocked(this, operation, now)
            stagingCheck(coverage != null && coverage.digest == prepared.coverageDigest, IdentityAdoptionStagingFailure.CONFLICT)
            val inventory = IdentityAdoptionManifestInventory(this, operation, requireNotNull(coverage).source.generation)
            stagingCheck(
                prepared.manifest.binding.sourceSequence == inventory.sourceSequence(),
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            stagingCheck(
                sameIdentityAdoptionRecords(prepared.manifest.records, inventory.records()),
                IdentityAdoptionStagingFailure.CONFLICT,
            )
            stagingCheck(sameIdentityAdoptionBlobs(prepared.manifest.blobs, inventory.blobs()), IdentityAdoptionStagingFailure.CONFLICT)
            val finished = clock.now()
            stagingCheck(with(actions) { currentKey(operation, finished) } != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            requireIdentityAdoptionStagingRoom(operation, prepared.bytes.size.toLong(), limitBytes, finished)
            IdentityAdoptionVerificationRows(this, operation).insert(prepared, coverage.source.generation, challenge, finished)
        }
    }

    suspend fun get(operation: IdentityAdoptionOperation): IdentityAdoptionVerifiedCopy? {
        if (operation.phase != IdentityAdoptionPhase.VERIFIED) return null
        return withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val key =
                    with(PostgreSQLIdentityAdoptionActions(source, clock)) { currentKey(operation, clock.now()) }
                        ?: return@blueskyTransaction null
                IdentityAdoptionVerificationRows(this, operation).read(key.origin)
            }
        }
    }
}
