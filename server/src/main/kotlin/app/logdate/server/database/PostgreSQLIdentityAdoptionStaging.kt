@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.server.space.decodeRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Instant

/** Private candidates never enter SpaceStore before verification and atomic promotion. */
internal class PostgreSQLIdentityAdoptionStaging(
    private val source: DataSource,
) {
    suspend fun put(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionStagingRequest,
        limitBytes: Long,
        now: Instant,
    ): IdentityAdoptionStagedRecord? {
        val prepared = prepareIdentityAdoptionRecord(request)
        if (challenge.action != IdentityAdoptionAction.STAGE_RECORDS || challenge.bodyDigest != prepared.bodyDigest) return null
        stagingCheck(limitBytes >= 0, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        return PostgreSQLIdentityAdoptionActions(source).consume(operation, challenge, signature, now) {
            val original = identityAdoptionSourceRecord(operation, prepared)
            val bytes = prepared.targetBytes ?: original
            val generation = generation(operation)
            val expected =
                IdentityAdoptionStagedRecord(
                    generation,
                    prepared.rkey,
                    SEALED_COLLECTION,
                    prepared.rkey,
                    prepared.sourceVersion,
                    prepared.sourceDigest,
                    stagingHash(bytes),
                    prepared.targetCid,
                    prepared.targetBytes?.let(::decodeRecord),
                    prepared.blobCids,
                    tombstone = if (prepared.targetBytes == null) decodeRecord(original) else null,
                )
            existing(operation, generation, prepared.rkey)?.let {
                if (it == expected) return@consume it
                stagingCheck(
                    operation.phase == IdentityAdoptionPhase.PREPARING && prepared.sourceVersion > it.sourceVersion,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                delete(operation, it)
            }
            requireIdentityAdoptionStagingRoom(operation, bytes.size.toLong(), limitBytes, now)
            insert(operation, expected, bytes, prepared.blobBytes, now)
            expected
        }
    }

    suspend fun reuse(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        sourceGeneration: Long,
        rkey: String,
        targetDigest: String,
        now: Instant,
    ): IdentityAdoptionStagedRecord? {
        if (operation.phase != IdentityAdoptionPhase.FENCED) return null
        stagingCheck(
            sourceGeneration > 0 &&
                rkey.matches(Regex("[A-Za-z0-9._:~-]{1,512}")) &&
                rkey !in setOf(".", "..") &&
                targetDigest.matches(Regex("[0-9a-f]{64}")),
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("sourceGeneration", sourceGeneration)
                    put("rkey", rkey)
                    put("targetDigest", targetDigest)
                },
            )
        if (challenge.action != IdentityAdoptionAction.STAGE_RECORDS || challenge.bodyDigest != stagingHash(body)) return null
        return PostgreSQLIdentityAdoptionActions(source).consume(operation, challenge, signature, now) {
            val generation = generation(operation)
            stagingCheck(sourceGeneration < generation, IdentityAdoptionStagingFailure.INVALID_REQUEST)
            val previous = existing(operation, generation, rkey) ?: existing(operation, sourceGeneration, rkey)
            stagingCheck(previous != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val candidate = requireNotNull(previous)
            stagingCheck(
                candidate.targetDigest == targetDigest &&
                    candidate.sourceCollection == SEALED_COLLECTION &&
                    candidate.sourceRkey == rkey,
                IdentityAdoptionStagingFailure.CONFLICT,
            )
            val prepared =
                prepareIdentityAdoptionRecord(
                    IdentityAdoptionStagingRequest(
                        rkey,
                        candidate.sourceVersion,
                        candidate.sourceDigest,
                        candidate.record,
                        candidate.blobs,
                    ),
                )
            val sourceBytes = identityAdoptionSourceRecord(operation, prepared)
            val bytes = prepared.targetBytes ?: sourceBytes
            stagingCheck(stagingHash(bytes) == targetDigest, IdentityAdoptionStagingFailure.CONFLICT)
            if (candidate.generation == generation) return@consume candidate
            delete(operation, candidate)
            val promoted = candidate.copy(generation = generation)
            insert(operation, promoted, bytes, prepared.blobBytes, now)
            promoted
        }
    }

    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        rkey: String,
        now: Instant,
    ): IdentityAdoptionStagedRecord? {
        stagingCheck(
            rkey.matches(Regex("[A-Za-z0-9._:~-]{1,512}")) && rkey !in setOf(".", ".."),
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("rkey", rkey)
                },
            )
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != stagingHash(body)) return null
        return PostgreSQLIdentityAdoptionActions(source).consume(operation, challenge, signature, now) {
            existing(operation, generation(operation), rkey)
        }
    }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long {
        if (operation.phase == IdentityAdoptionPhase.PREPARING) return operation.revision
        return prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                stagingCheck(rows.next(), IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                rows.getLong(1).also { value ->
                    stagingCheck(value > 0 && value <= operation.revision, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                }
            }
        }
    }

    private fun Connection.delete(
        operation: IdentityAdoptionOperation,
        record: IdentityAdoptionStagedRecord,
    ) {
        prepareStatement(
            "DELETE FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                "AND hold_generation = ? AND rkey = ?",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, record.generation)
            it.setString(4, record.rkey)
            check(it.executeUpdate() == 1)
        }
    }

    private fun Connection.existing(
        operation: IdentityAdoptionOperation,
        generation: Long,
        key: String,
    ): IdentityAdoptionStagedRecord? =
        prepareStatement(
            "SELECT * FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                "AND hold_generation = ? AND rkey = ? FOR UPDATE",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, generation)
            it.setString(4, key)
            it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionStagedRecord() else null }
        }

    private fun Connection.insert(
        operation: IdentityAdoptionOperation,
        record: IdentityAdoptionStagedRecord,
        bytes: ByteArray,
        blobs: ByteArray,
        now: Instant,
    ) {
        prepareStatement(
            "INSERT INTO identity_adoption_staged_records(operation_id, account_id, hold_generation, rkey, " +
                "source_collection, source_rkey, source_version, source_digest, target_digest, target_cid, " +
                "target_record, blob_cids, deleted, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, record.generation)
            it.setString(4, record.rkey)
            it.setString(5, record.sourceCollection)
            it.setString(6, record.sourceRkey)
            it.setLong(7, record.sourceVersion)
            it.setString(8, record.sourceDigest)
            it.setString(9, record.targetDigest)
            it.setString(10, record.cid)
            it.setBytes(11, bytes)
            it.setBytes(12, blobs)
            it.setBoolean(13, record.record == null)
            it.setTimestamp(14, now.timestamp())
            check(it.executeUpdate() == 1)
        }
    }

    private companion object {
        const val SEALED_COLLECTION = "app.logdate.crypto.sealed"
    }
}
