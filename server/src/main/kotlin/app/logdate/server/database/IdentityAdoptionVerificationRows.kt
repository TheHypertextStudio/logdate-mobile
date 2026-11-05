@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerificationRequest
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedCopy
import java.sql.Connection
import kotlin.time.Instant

internal class IdentityAdoptionVerificationRows(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
) {
    fun insert(
        prepared: PreparedIdentityAdoptionVerification,
        generation: Long,
        challenge: IdentityAdoptionActionChallenge,
        now: Instant,
    ): IdentityAdoptionVerifiedCopy {
        stagingCheck(operation.revision < Long.MAX_VALUE, IdentityAdoptionStagingFailure.CONFLICT)
        val next = operation.copy(revision = operation.revision + 1, phase = IdentityAdoptionPhase.VERIFIED)
        val time =
            connection
                .prepareStatement(
                    "INSERT INTO identity_adoption_verifications(operation_id,account_id,source_revision," +
                        "verified_revision,hold_generation,challenge_id,manifest_digest,coverage_digest,body_digest," +
                        "source_sequence,manifest_bytes,verified_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING verified_at",
                ).use {
                    it.setString(1, operation.id)
                    it.setObject(2, operation.binding.accountId.toJavaUUID())
                    it.setLong(3, operation.revision)
                    it.setLong(4, next.revision)
                    it.setLong(5, generation)
                    it.setObject(6, challenge.id.toJavaUUID())
                    it.setString(7, prepared.manifestDigest)
                    it.setString(8, prepared.coverageDigest)
                    it.setString(9, prepared.bodyDigest)
                    it.setLong(10, prepared.manifest.binding.sourceSequence)
                    it.setBytes(11, prepared.bytes)
                    it.setTimestamp(12, now.timestamp())
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getTimestamp(1).connectionInstant()
                    }
                }
        connection
            .prepareStatement(
                "UPDATE identity_adoption_operations SET phase = 'VERIFIED', revision = ?, updated_at = ? " +
                    "WHERE id = ? AND account_id = ? AND revision = ? AND phase = 'FENCED' AND lease_expires_at > ?",
            ).use {
                it.setLong(1, next.revision)
                it.setTimestamp(2, now.timestamp())
                it.setString(3, operation.id)
                it.setObject(4, operation.binding.accountId.toJavaUUID())
                it.setLong(5, operation.revision)
                it.setTimestamp(6, now.timestamp())
                stagingCheck(it.executeUpdate() == 1, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            }
        return IdentityAdoptionVerifiedCopy(
            next,
            operation.revision,
            generation,
            prepared.manifestDigest,
            prepared.coverageDigest,
            prepared.manifest.binding.sourceSequence,
            time,
        )
    }

    fun read(origin: String): IdentityAdoptionVerifiedCopy? =
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_verifications WHERE operation_id = ? AND account_id = ? AND verified_revision = ?",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                it.setLong(3, operation.revision)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    val prepared =
                        prepareIdentityAdoptionVerification(
                            IdentityAdoptionVerificationRequest(
                                rows.getBytes("manifest_bytes"),
                                rows.getString("coverage_digest"),
                            ),
                        )
                    requireIdentityAdoptionManifestBinding(operation, origin, prepared.manifest)
                    val sourceRevision = rows.getLong("source_revision")
                    val generation = rows.getLong("hold_generation")
                    stagingCheck(
                        sourceRevision == operation.revision - 1 &&
                            generation in 1..sourceRevision &&
                            prepared.manifestDigest == rows.getString("manifest_digest") &&
                            prepared.bodyDigest == rows.getString("body_digest") &&
                            prepared.manifest.binding.sourceSequence == rows.getLong("source_sequence"),
                        IdentityAdoptionStagingFailure.CONFLICT,
                    )
                    IdentityAdoptionVerifiedCopy(
                        operation,
                        sourceRevision,
                        generation,
                        prepared.manifestDigest,
                        prepared.coverageDigest,
                        prepared.manifest.binding.sourceSequence,
                        rows.getTimestamp("verified_at").connectionInstant(),
                    )
                }
            }
}
