@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.HexFormat

internal data class IdentityAdoptionTargetCoverage(
    val count: Long,
    val digest: String,
)

/** Fixed SQL and account/operation/generation predicates for the locked coverage scan. */
internal class IdentityAdoptionCoverageRows(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    private val generation: Long,
) {
    fun mapping(reference: IdentityAdoptionSourceReference): IdentityAdoptionSourceMapping? =
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_source_mappings WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation = ? AND source_kind = ? AND source_key_hash = ?",
            ).use {
                it.bind()
                it.setString(4, reference.kind.name)
                it.setString(5, stagingHash(sourceMappingKey(reference)))
                it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionSourceMapping(reference) else null }
            }

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

    fun count(
        table: String,
        generationColumn: String,
    ): Long {
        check(
            (table to generationColumn) in
                setOf(
                    "identity_adoption_source_mappings" to "copy_generation",
                    "identity_adoption_staged_origins" to "copy_generation",
                    "identity_adoption_staged_blobs" to "hold_generation",
                ),
        )
        return connection
            .prepareStatement(
                "SELECT count(*) FROM $table WHERE operation_id = ? AND account_id = ? " +
                    "AND $generationColumn = ?",
            ).use {
                it.bind()
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
    }

    fun verifyTargets(
        expected: Set<String>,
        media: Set<String>,
    ): IdentityAdoptionTargetCoverage {
        val remaining = expected.toMutableSet()
        val sequences = mutableSetOf<Long>()
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("app.logdate.identity-adoption.target-inventory|1\u0000".encodeToByteArray())
        var count = 0L
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
                        val selected = remaining.remove(record.rkey) && media.containsAll(record.blobs)
                        stagingCheck(selected, IdentityAdoptionStagingFailure.CONFLICT)
                        val sequence = record.targetSequence ?: record.sourceVersion
                        stagingCheck(sequence > 0 && sequences.add(sequence), IdentityAdoptionStagingFailure.CONFLICT)
                        hash.update(targetEvidence(record, sequence))
                        count = Math.addExact(count, 1)
                    }
                }
            }
        stagingCheck(remaining.isEmpty(), IdentityAdoptionStagingFailure.CONFLICT)
        return IdentityAdoptionTargetCoverage(count, HexFormat.of().formatHex(hash.digest()))
    }

    private fun targetEvidence(
        record: IdentityAdoptionStagedRecord,
        sequence: Long,
    ): ByteArray =
        HexFormat.of().parseHex(
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("rkey", record.rkey)
                        put("sequence", sequence)
                        put("sourceCollection", record.sourceCollection)
                        put("sourceRkey", record.sourceRkey)
                        put("sourceVersion", record.sourceVersion)
                        put("sourceDigest", record.sourceDigest)
                        put("targetDigest", record.targetDigest)
                        put("blobs", JsonArray(record.blobs.map(::JsonPrimitive)))
                    },
                ),
            ),
        )

    fun hasReferences(reference: IdentityAdoptionSourceReference): Boolean {
        if (reference.kind != IdentityAdoptionSourceKind.SPACE_BLOB) return true
        return connection.prepareStatement("SELECT 1 FROM space_record_blobs WHERE space_uri = ? AND cid = ? LIMIT 1").use {
            it.setString(1, reference.key[0])
            it.setString(2, reference.key[1])
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun PreparedStatement.bind() {
        setString(1, operation.id)
        setObject(2, operation.binding.accountId.toJavaUUID())
        setLong(3, generation)
    }
}
