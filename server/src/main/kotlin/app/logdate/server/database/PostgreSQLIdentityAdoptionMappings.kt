@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingPolicy
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

internal class PostgreSQLIdentityAdoptionMappings(
    private val source: DataSource,
    private val clock: Clock? = null,
) {
    suspend fun put(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionMappingRequest,
        limitBytes: Long,
        now: Instant,
    ): IdentityAdoptionSourceMapping? {
        val prepared = prepareIdentityAdoptionMapping(request)
        stagingCheck(limitBytes >= 0, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        if (challenge.action != IdentityAdoptionAction.STAGE_RECORDS || challenge.bodyDigest != prepared.bodyDigest) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return actions.consume(operation, challenge, signature, now) {
            val value = prepared.request
            val inventory = PostgreSQLIdentityAdoptionSourceInventory(source)
            val item = inventory.itemLocked(this, operation, value.source.kind, value.source.key, now)
            stagingCheck(item != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val digest = HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(value.source.kind, requireNotNull(item)))
            stagingCheck(digest == value.sourceDigest, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val generation = generation(operation)
            val records =
                value.targets.map { target ->
                    val record = target(operation, generation, target.rkey)
                    stagingCheck(record != null && record.targetDigest == target.digest, IdentityAdoptionStagingFailure.CONFLICT)
                    requireNotNull(record)
                }
            for (cid in value.media) requireMedia(operation, generation, cid)
            requireMediaUploadMapping(operation, value)
            val authority = identityAdoptionStorageAuthority(source, operation, now)
            var retired = authority.retainsSource(value.source.kind, item)
            if (value.source.kind == IdentityAdoptionSourceKind.REPO_BLOCK && authority.retiresLegacyJournal) {
                val repository = PostgreSQLIdentityAdoptionRepositoryInventory(source).readLocked(this, operation, now)
                stagingCheck(repository != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                retired = authority.retainsRepositoryBlock(value.source.key.single(), requireNotNull(repository).inventory)
            }
            IdentityAdoptionMappingPolicy.staging(value, item, records, hasReferences(value), retired)
            val result = persist(operation, generation, prepared, limitBytes, now)
            stagingCheck(
                with(actions) { currentKey(operation, clock?.now() ?: now) } != null,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            result
        }
    }

    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: IdentityAdoptionSourceReference,
        now: Instant,
    ): IdentityAdoptionSourceMapping? {
        val frozen = reference.copy(key = reference.key.toList())
        val key = sourceMappingKey(frozen)
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("purpose", "app.logdate.identity-adoption.source-mapping-read")
                    put("kind", frozen.kind.name)
                    put("key", JsonArray(frozen.key.map(::JsonPrimitive)))
                },
            )
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != stagingHash(body)) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return actions.consume(operation, challenge, signature, now) {
            val result = existing(operation, generation(operation), frozen, stagingHash(key))
            stagingCheck(
                with(actions) { currentKey(operation, clock?.now() ?: now) } != null,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            result
        }
    }

    private fun Connection.persist(
        operation: IdentityAdoptionOperation,
        generation: Long,
        prepared: PreparedIdentityAdoptionMapping,
        limit: Long,
        now: Instant,
    ): IdentityAdoptionSourceMapping {
        val previous = existing(operation, generation, prepared.request.source, prepared.keyHash)
        if (previous != null) {
            if (previous.bodyDigest == prepared.bodyDigest) return previous
            stagingCheck(
                operation.phase == IdentityAdoptionPhase.PREPARING && previous.request.sourceDigest != prepared.request.sourceDigest,
                IdentityAdoptionStagingFailure.CONFLICT,
            )
            delete(operation, previous)
        } else {
            earlier(operation, generation, prepared)?.let { delete(operation, it) }
        }
        requireIdentityAdoptionStagingRoom(operation, prepared.storageBytes, limit, now)
        prepareStatement(
            "INSERT INTO identity_adoption_source_mappings(operation_id,account_id,copy_generation,source_kind," +
                "source_key_hash,source_key,source_digest,disposition,body_digest,body_bytes,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, generation)
            it.setString(4, prepared.request.source.kind.name)
            it.setString(5, prepared.keyHash)
            it.setBytes(6, prepared.key)
            it.setString(7, prepared.request.sourceDigest)
            it.setString(8, prepared.request.disposition.name)
            it.setString(9, prepared.bodyDigest)
            it.setBytes(10, prepared.body)
            it.setTimestamp(11, now.timestamp())
            check(it.executeUpdate() == 1)
        }
        return IdentityAdoptionSourceMapping(generation, prepared.request, prepared.bodyDigest, prepared.storageBytes)
    }

    private fun Connection.target(
        operation: IdentityAdoptionOperation,
        generation: Long,
        rkey: String,
    ) = prepareStatement(
        "SELECT * FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
            "AND hold_generation = ? AND rkey = ?",
    ).use {
        it.setString(1, operation.id)
        it.setObject(2, operation.binding.accountId.toJavaUUID())
        it.setLong(3, generation)
        it.setString(4, rkey)
        it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionStagedRecord() else null }
    }

    private fun Connection.requireMedia(
        operation: IdentityAdoptionOperation,
        generation: Long,
        cid: String,
    ) {
        val exists =
            prepareStatement(
                "SELECT 1 FROM identity_adoption_staged_blobs WHERE operation_id = ? AND account_id = ? " +
                    "AND hold_generation = ? AND cid = ? AND verified_at IS NOT NULL",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                it.setLong(3, generation)
                it.setString(4, cid)
                it.executeQuery().use { rows -> rows.next() }
            }
        stagingCheck(exists, IdentityAdoptionStagingFailure.CONFLICT)
    }

    private fun Connection.hasReferences(request: IdentityAdoptionMappingRequest): Boolean {
        val retained = request.disposition == IdentityAdoptionDisposition.RETAINED_HISTORY
        if (request.source.kind != IdentityAdoptionSourceKind.SPACE_BLOB || !retained) return true
        return prepareStatement("SELECT 1 FROM space_record_blobs WHERE space_uri = ? AND cid = ? LIMIT 1").use {
            it.setString(1, request.source.key[0])
            it.setString(2, request.source.key[1])
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long {
        if (operation.phase == IdentityAdoptionPhase.PREPARING) return operation.revision
        return prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1).also { check(it > 0 && it <= operation.revision) }
            }
        }
    }

    private fun Connection.existing(
        operation: IdentityAdoptionOperation,
        generation: Long,
        reference: IdentityAdoptionSourceReference,
        keyHash: String,
    ): IdentityAdoptionSourceMapping? =
        prepareStatement(
            "SELECT * FROM identity_adoption_source_mappings WHERE operation_id = ? AND account_id = ? " +
                "AND copy_generation = ? AND source_kind = ? AND source_key_hash = ? FOR UPDATE",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, generation)
            it.setString(4, reference.kind.name)
            it.setString(5, keyHash)
            it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionSourceMapping(reference) else null }
        }

    private fun Connection.earlier(
        operation: IdentityAdoptionOperation,
        generation: Long,
        prepared: PreparedIdentityAdoptionMapping,
    ): IdentityAdoptionSourceMapping? =
        prepareStatement(
            "SELECT * FROM identity_adoption_source_mappings WHERE operation_id = ? AND account_id = ? " +
                "AND copy_generation < ? AND source_kind = ? AND source_key_hash = ? AND body_digest = ? " +
                "ORDER BY copy_generation DESC LIMIT 1 FOR UPDATE",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, generation)
            it.setString(4, prepared.request.source.kind.name)
            it.setString(5, prepared.keyHash)
            it.setString(6, prepared.bodyDigest)
            it.executeQuery().use { rows -> if (rows.next()) rows.identityAdoptionSourceMapping(prepared.request.source) else null }
        }

    private fun Connection.delete(
        operation: IdentityAdoptionOperation,
        value: IdentityAdoptionSourceMapping,
    ) {
        val reference = value.request.source
        prepareStatement(
            "DELETE FROM identity_adoption_source_mappings WHERE operation_id = ? AND account_id = ? " +
                "AND copy_generation = ? AND source_kind = ? AND source_key_hash = ?",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, value.generation)
            it.setString(4, reference.kind.name)
            it.setString(5, stagingHash(sourceMappingKey(reference)))
            check(it.executeUpdate() == 1)
        }
    }
}
