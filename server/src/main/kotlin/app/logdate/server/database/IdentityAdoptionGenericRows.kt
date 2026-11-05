@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedOrigin
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.Instant

internal class IdentityAdoptionGenericRows(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    val generation: Long,
) {
    fun target(rkey: String): IdentityAdoptionStagedRecord? =
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                    "AND hold_generation = ? AND rkey = ?",
            ).use {
                it.bind()
                it.setString(4, rkey)
                it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionStagedRecord() else null }
            }

    fun origin(
        source: IdentityAdoptionSourceReference,
        logical: IdentityAdoptionSourceRecordKey,
        targetRkey: String,
    ): IdentityAdoptionStagedOrigin? =
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation = ? AND source_kind = ? AND source_key_hash = ? " +
                    "AND source_collection = ? AND source_rkey = ? " +
                    "AND (source_kind <> 'REPO_BLOCK' OR target_rkey = ?)",
            ).use {
                it.bind()
                it.setString(4, source.kind.name)
                it.setString(5, stagingHash(sourceMappingKey(source)))
                it.setString(6, logical.collection)
                it.setString(7, logical.rkey)
                it.setString(8, targetRkey)
                it.executeQuery().use { rows -> if (rows.next()) rows.originView() else null }
            }

    fun earlier(
        source: IdentityAdoptionSourceReference,
        logical: IdentityAdoptionSourceRecordKey,
        targetRkey: String,
    ): IdentityAdoptionGenericRows? =
        connection
            .prepareStatement(
                "SELECT copy_generation FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation < ? AND source_kind = ? AND source_key_hash = ? " +
                    "AND source_collection = ? AND source_rkey = ? " +
                    "AND (source_kind <> 'REPO_BLOCK' OR target_rkey = ?) ORDER BY copy_generation DESC LIMIT 1",
            ).use {
                it.bind()
                it.setString(4, source.kind.name)
                it.setString(5, stagingHash(sourceMappingKey(source)))
                it.setString(6, logical.collection)
                it.setString(7, logical.rkey)
                it.setString(8, targetRkey)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    IdentityAdoptionGenericRows(connection, operation, rows.getLong(1))
                }
            }

    fun originCount(rkey: String): Long =
        connection
            .prepareStatement(
                "SELECT count(*) FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation = ? AND target_rkey = ?",
            ).use {
                it.bind()
                it.setString(4, rkey)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }

    fun deleteRecord(rkey: String) {
        connection
            .prepareStatement(
                "DELETE FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                    "AND hold_generation = ? AND rkey = ?",
            ).use {
                it.bind()
                it.setString(4, rkey)
                check(it.executeUpdate() == 1)
            }
    }

    fun deleteOrigin(value: IdentityAdoptionStagedOrigin) {
        connection
            .prepareStatement(
                "DELETE FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation = ? AND target_rkey = ? AND body_digest = ?",
            ).use {
                it.bind()
                it.setString(4, value.targetRkey)
                it.setString(5, value.bodyDigest)
                check(it.executeUpdate() == 1)
            }
    }

    fun sequence(): Long {
        val sourceMaximum =
            connection.prepareStatement("SELECT COALESCE(MAX(last_seq),0) FROM space_states WHERE account_id = ?").use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        val stagedMaximum =
            connection
                .prepareStatement(
                    "SELECT COALESCE(MAX(COALESCE(target_sequence,source_version)),0) " +
                        "FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? AND hold_generation = ?",
                ).use {
                    it.bind()
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
        val maximum = maxOf(sourceMaximum, stagedMaximum)
        stagingCheck(maximum in 0 until Long.MAX_VALUE, IdentityAdoptionStagingFailure.CONFLICT)
        return maximum + 1
    }

    fun insertRecord(
        record: IdentityAdoptionStagedRecord,
        bytes: ByteArray,
        blobs: ByteArray,
        now: Instant,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO identity_adoption_staged_records(operation_id,account_id,hold_generation,rkey," +
                    "source_collection,source_rkey,source_version,source_digest,target_digest,target_cid," +
                    "target_record,blob_cids,deleted,created_at," +
                    "target_sequence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use {
                it.bind()
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
                it.setLong(15, requireNotNull(record.targetSequence))
                check(it.executeUpdate() == 1)
            }
    }

    fun insertOrigin(prepared: PreparedIdentityAdoptionOrigin) {
        val value = prepared.value
        connection
            .prepareStatement(
                "INSERT INTO identity_adoption_staged_origins(operation_id,account_id,copy_generation,target_rkey," +
                    "source_kind,source_key_hash,source_collection,source_rkey,body_digest,body_bytes) VALUES (?,?,?,?,?,?,?,?,?,?)",
            ).use {
                it.bind()
                it.setString(4, value.targetRkey)
                it.setString(5, value.source.kind.name)
                it.setString(6, stagingHash(sourceMappingKey(value.source)))
                it.setString(7, value.sourceRecord.collection)
                it.setString(8, value.sourceRecord.rkey)
                it.setString(9, value.bodyDigest)
                it.setBytes(10, prepared.bytes)
                check(it.executeUpdate() == 1)
            }
    }

    private fun PreparedStatement.bind() {
        setString(1, operation.id)
        setObject(2, operation.binding.accountId.toJavaUUID())
        setLong(3, generation)
    }
}

internal fun ResultSet.originView(): IdentityAdoptionStagedOrigin {
    val prepared = decodeIdentityAdoptionOrigin(getBytes("body_bytes"))
    val value = prepared.value
    val valid =
        value.bodyDigest == getString("body_digest") &&
            value.targetRkey == getString("target_rkey") &&
            value.source.kind.name == getString("source_kind") &&
            stagingHash(sourceMappingKey(value.source)) == getString("source_key_hash") &&
            value.sourceRecord.collection == getString("source_collection") &&
            value.sourceRecord.rkey == getString("source_rkey")
    stagingCheck(valid, IdentityAdoptionStagingFailure.CONFLICT)
    return value
}
