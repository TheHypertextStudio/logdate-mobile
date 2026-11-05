@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.time.Instant

/** Transfer ciphertext and recovery metadata within the caller's account-locked transaction. */
internal class IdentityAdoptionPromotion(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    private val generation: Long,
) {
    private val target = SpaceRef.personal(operation.binding.targetDid).uri
    private val account = operation.binding.accountId.toJavaUUID()

    fun promote() {
        requireEmptyTarget()
        val bytes = promoteRecords()
        preserveOrigins()
        val blobBytes = promoteBlobs()
        connection
            .prepareStatement(
                "UPDATE identity_adoption_media_uploads u SET phase='PROMOTED' WHERE u.operation_id=? AND u.account_id=? " +
                    "AND u.phase='VERIFIED' AND EXISTS (SELECT 1 FROM space_blobs b WHERE b.account_id=u.account_id " +
                    "AND b.space_uri=? AND b.storage_path=u.storage_path AND b.cid=u.cid)",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, account)
                it.setString(3, target)
                it.executeUpdate()
            }
        connection.prepareStatement("UPDATE account_storage_usage SET space_bytes = space_bytes + ? WHERE account_id = ?").use {
            it.setLong(1, Math.addExact(bytes, blobBytes))
            it.setObject(2, account)
            check(it.executeUpdate() == 1)
        }
        connection
            .prepareStatement(
                "DELETE FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? AND hold_generation = ?",
            ).use {
                it.bind()
                it.executeUpdate()
            }
        // Committed blob pins retain shared physical paths until explicit source-history cleanup.
    }

    private fun requireEmptyTarget() {
        val occupied =
            connection
                .prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM space_states WHERE space_uri = ? OR author_did = ?) " +
                        "OR EXISTS(SELECT 1 FROM space_records WHERE space_uri = ? OR author_did = ?) " +
                        "OR EXISTS(SELECT 1 FROM space_blobs WHERE space_uri = ?)",
                ).use {
                    it.setString(1, target)
                    it.setString(2, operation.binding.targetDid)
                    it.setString(3, target)
                    it.setString(4, operation.binding.targetDid)
                    it.setString(5, target)
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getBoolean(1)
                    }
                }
        stagingCheck(!occupied, IdentityAdoptionStagingFailure.CONFLICT)
    }

    private fun promoteRecords(): Long {
        var total = 0L
        var sequence = 0L
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                    "AND hold_generation = ? ORDER BY rkey COLLATE \"C\"",
            ).use {
                it.bind()
                it.fetchSize = 1
                it.executeQuery().use { rows ->
                    while (rows.next()) {
                        val record = rows.identityAdoptionStagedRecord()
                        val seq = record.targetSequence ?: record.sourceVersion
                        stagingCheck(seq > 0, IdentityAdoptionStagingFailure.CONFLICT)
                        val bytes = if (record.record == null) null else rows.getBytes("target_record")
                        insertRecord(record, seq, bytes)
                        preserveRecord(record, seq)
                        total = Math.addExact(total, bytes?.size?.toLong() ?: 0L)
                        sequence = maxOf(sequence, seq)
                    }
                }
            }
        connection
            .prepareStatement(
                "INSERT INTO space_states(space_uri,author_did,account_id,last_seq,purged_through_seq) VALUES (?,?,?,?,0)",
            ).use {
                it.setString(1, target)
                it.setString(2, operation.binding.targetDid)
                it.setObject(3, account)
                it.setLong(4, sequence)
                check(it.executeUpdate() == 1)
            }
        return total
    }

    private fun insertRecord(
        record: IdentityAdoptionStagedRecord,
        sequence: Long,
        bytes: ByteArray?,
    ) {
        val deletedAt = if (bytes == null) tombstoneTime(record, sequence) else null
        connection
            .prepareStatement(
                "INSERT INTO space_records(space_uri,author_did,rkey,account_id,seq,cid,record,size_bytes,deleted,deleted_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
            ).use {
                it.setString(1, target)
                it.setString(2, operation.binding.targetDid)
                it.setString(3, record.rkey)
                it.setObject(4, account)
                it.setLong(5, sequence)
                it.setString(6, record.cid)
                it.setBytes(7, bytes)
                it.setInt(8, bytes?.size ?: 0)
                it.setBoolean(9, bytes == null)
                it.setTimestamp(10, deletedAt?.timestamp())
                check(it.executeUpdate() == 1)
            }
        for (cid in record.blobs) {
            stagingCheck(bytes != null, IdentityAdoptionStagingFailure.CONFLICT)
            connection.prepareStatement("INSERT INTO space_record_blobs(space_uri,author_did,rkey,cid) VALUES (?,?,?,?)").use {
                it.setString(1, target)
                it.setString(2, operation.binding.targetDid)
                it.setString(3, record.rkey)
                it.setString(4, cid)
                check(it.executeUpdate() == 1)
            }
        }
    }

    private fun tombstoneTime(
        record: IdentityAdoptionStagedRecord,
        sequence: Long,
    ): Instant {
        val marker = requireNotNull(record.tombstone)
        stagingCheck(
            marker["rkey"]?.jsonPrimitive?.content == record.rkey &&
                marker["seq"]?.jsonPrimitive?.longOrNull == sequence &&
                marker["deleted"]?.jsonPrimitive?.booleanOrNull == true &&
                record.blobs.isEmpty(),
            IdentityAdoptionStagingFailure.CONFLICT,
        )
        val time = (marker["deletedAt"] as? JsonPrimitive)?.content
        val parsed = time?.let { runCatching { Instant.parse(it) }.getOrNull() }
        stagingCheck(parsed != null, IdentityAdoptionStagingFailure.CONFLICT)
        return requireNotNull(parsed)
    }

    private fun preserveRecord(
        record: IdentityAdoptionStagedRecord,
        sequence: Long,
    ) {
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("purpose", "app.logdate.identity-adoption.record-receipt")
                    put("sourceCollection", record.sourceCollection)
                    put("sourceRkey", record.sourceRkey)
                    put("sourceVersion", record.sourceVersion)
                    put("sourceDigest", record.sourceDigest)
                    put("targetRkey", record.rkey)
                    put("targetSequence", sequence)
                    put("targetDigest", record.targetDigest)
                    put("deleted", record.record == null)
                    put("blobs", JsonArray(record.blobs.map(::JsonPrimitive)))
                    record.tombstone?.let { put("tombstone", it) }
                },
            )
        connection
            .prepareStatement(
                "INSERT INTO identity_adoption_record_receipts(operation_id,account_id," +
                    "target_rkey,target_sequence,body_digest,body_bytes) " +
                    "VALUES (?,?,?,?,?,?)",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, account)
                it.setString(3, record.rkey)
                it.setLong(4, sequence)
                it.setString(5, stagingHash(body))
                it.setBytes(6, body)
                check(it.executeUpdate() == 1)
            }
    }

    private fun preserveOrigins() {
        connection
            .prepareStatement(
                "INSERT INTO identity_adoption_origin_receipts(operation_id,account_id,target_rkey,source_kind,source_key_hash," +
                    "source_collection,source_rkey,body_digest,body_bytes) " +
                    "SELECT operation_id,account_id,target_rkey,source_kind,source_key_hash," +
                    "source_collection,source_rkey,body_digest,body_bytes " +
                    "FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? AND copy_generation = ?",
            ).use {
                it.bind()
                it.executeUpdate()
            }
    }

    private fun promoteBlobs(): Long {
        var total = 0L
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_staged_blobs WHERE operation_id = ? AND account_id = ? AND hold_generation = ?",
            ).use {
                it.bind()
                it.fetchSize = 1
                it.executeQuery().use { rows ->
                    while (rows.next()) {
                        stagingCheck(rows.getTimestamp("verified_at") != null, IdentityAdoptionStagingFailure.CONFLICT)
                        connection
                            .prepareStatement(
                                "INSERT INTO space_blobs(space_uri,cid,account_id,mime_type,size_bytes,storage_path,created_at) " +
                                    "VALUES (?,?,?,?,?,?,?)",
                            ).use { insert ->
                                insert.setString(1, target)
                                insert.setString(2, rows.getString("cid"))
                                insert.setObject(3, account)
                                insert.setString(4, rows.getString("mime_type"))
                                insert.setLong(5, rows.getLong("size_bytes"))
                                insert.setString(6, rows.getString("storage_path"))
                                insert.setTimestamp(7, rows.getTimestamp("created_at"))
                                check(insert.executeUpdate() == 1)
                            }
                        total = Math.addExact(total, rows.getLong("size_bytes"))
                    }
                }
            }
        return total
    }

    private fun PreparedStatement.bind() {
        setString(1, operation.id)
        setObject(2, account)
        setLong(3, generation)
    }
}
