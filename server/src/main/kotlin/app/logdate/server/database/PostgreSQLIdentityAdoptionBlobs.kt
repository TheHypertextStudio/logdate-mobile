@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_MAX_BLOB_BYTES
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission
import app.logdate.server.identity.adoption.IdentityAdoptionBlobRepository
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.space.SpaceBlob
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Reuse retains an already charged immutable object, without exposing a canonical target alias. */
internal class PostgreSQLIdentityAdoptionBlobs(
    private val source: DataSource,
    private val clock: Clock? = null,
) : IdentityAdoptionBlobRepository {
    override suspend fun admit(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        cid: String,
        now: Instant,
    ): IdentityAdoptionBlobAdmission? {
        if (!IdentityAdoptionVerifiedBlob.acceptsCid(cid)) return null
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("cid", cid)
                },
            )
        if (challenge.action != IdentityAdoptionAction.BEGIN_BLOB || challenge.bodyDigest != stagingHash(body)) return null
        return PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, now) {
            val blob = sourceBlob(operation, cid)
            stagingCheck(blob != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val original = requireNotNull(blob)
            val generation = generation(operation)
            existing(operation.id, generation, cid)?.let {
                stagingCheck(it.matches(original, operation), IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                return@consume it
            }
            val admission =
                IdentityAdoptionBlobAdmission(
                    Uuid.random().toString(),
                    operation.id,
                    generation,
                    SpaceRef.personal(operation.binding.sourceDid).uri,
                    original.cid,
                    original.storagePath,
                    original.size,
                    original.mimeType,
                )
            insert(operation, admission, now)
            admission
        }
    }

    suspend fun reuse(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        sourceGeneration: Long,
        cid: String,
        now: Instant,
    ): IdentityAdoptionBlobAdmission? {
        if (operation.phase != IdentityAdoptionPhase.FENCED ||
            !IdentityAdoptionVerifiedBlob.acceptsCid(cid) ||
            sourceGeneration <= 0
        ) {
            return null
        }
        val body =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put("sourceGeneration", sourceGeneration)
                    put("cid", cid)
                },
            )
        if (challenge.action != IdentityAdoptionAction.BEGIN_BLOB || challenge.bodyDigest != stagingHash(body)) return null
        return PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, now) {
            val generation = generation(operation)
            stagingCheck(sourceGeneration < generation, IdentityAdoptionStagingFailure.INVALID_REQUEST)
            val original = sourceBlob(operation, cid)
            stagingCheck(original != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val previous = existing(operation.id, generation, cid) ?: existing(operation.id, sourceGeneration, cid)
            stagingCheck(
                previous != null && previous.matches(requireNotNull(original), operation),
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            val candidate = requireNotNull(previous)
            val times = verificationTimes(candidate)
            stagingCheck(times != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            if (candidate.generation == generation) return@consume candidate
            prepareStatement("DELETE FROM identity_adoption_staged_blobs WHERE id = ? AND account_id = ?").use {
                it.setString(1, candidate.id)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                check(it.executeUpdate() == 1)
            }
            val promoted = candidate.copy(id = Uuid.random().toString(), generation = generation)
            val (createdAt, verifiedAt) = requireNotNull(times)
            insert(operation, promoted, createdAt, verifiedAt)
            promoted
        }
    }

    /** The input can only be constructed by actual server readback, never by a request body. */
    override suspend fun complete(
        operation: IdentityAdoptionOperation,
        verified: IdentityAdoptionVerifiedBlob,
        now: Instant,
    ): Boolean {
        val admission = verified.admission
        if (admission.operationId != operation.id) return false
        val latest = PostgreSQLIdentityAdoptionRepository(source, clock).get(operation.binding.accountId, operation.id) ?: return false
        if (latest.binding != operation.binding ||
            latest.phase !in setOf(IdentityAdoptionPhase.PREPARING, IdentityAdoptionPhase.FENCED)
        ) {
            return false
        }
        return withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val actions = PostgreSQLIdentityAdoptionActions(source, clock)
                if (with(actions) { currentKey(latest, now) } == null) return@blueskyTransaction false
                if (generation(latest) != admission.generation) return@blueskyTransaction false
                val retained = existing(latest.id, admission.generation, admission.cid) ?: return@blueskyTransaction false
                if (retained != admission) return@blueskyTransaction false
                val blob = sourceBlob(latest, admission.cid) ?: return@blueskyTransaction false
                if (!admission.matches(blob, latest)) return@blueskyTransaction false
                prepareStatement(
                    "UPDATE identity_adoption_staged_blobs SET verified_at = COALESCE(verified_at, ?) " +
                        "WHERE id = ? AND account_id = ? AND operation_id = ? AND hold_generation = ?",
                ).use {
                    it.setTimestamp(1, now.timestamp())
                    it.setString(2, admission.id)
                    it.setObject(3, latest.binding.accountId.toJavaUUID())
                    it.setString(4, latest.id)
                    it.setLong(5, admission.generation)
                    it.executeUpdate() == 1
                }
            }
        }
    }

    internal fun inspectLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        generation: Long,
        cid: String,
        now: Instant,
    ): IdentityAdoptionBlobAdmission? =
        with(connection) {
            check(RawJdbcTransactionContext.isActive())
            val admission = existing(operation.id, generation, cid) ?: return null
            if (verificationTimes(admission) == null) return null
            if (identityAdoptionUploadedBlob(source, operation, admission, now)) return admission
            val original = sourceBlob(operation, cid) ?: return null
            admission.takeIf { it.matches(original, operation) }
        }

    private fun Connection.sourceBlob(
        operation: IdentityAdoptionOperation,
        cid: String,
    ): SpaceBlob? {
        val space = SpaceRef.personal(operation.binding.sourceDid).uri
        val blob =
            prepareStatement(
                "SELECT b.storage_path, b.size_bytes, b.mime_type, a.storage_mode FROM space_blobs b " +
                    "JOIN accounts a ON a.id = b.account_id WHERE b.account_id = ? AND b.space_uri = ? AND b.cid = ? " +
                    "AND b.purge_operation_id IS NULL FOR UPDATE OF b",
            ).use {
                it.setObject(1, operation.binding.accountId.toJavaUUID())
                it.setString(2, space)
                it.setString(3, cid)
                it.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    stagingCheck(
                        rows.getString("storage_mode") == AccountStorageMode.PERSONAL_SPACE_V1.wireValue,
                        IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
                    )
                    val size = rows.getLong("size_bytes")
                    stagingCheck(size in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                    SpaceBlob(cid, rows.getString("mime_type"), size, rows.getString("storage_path"))
                }
            }
        lockObjectPath(blob.storagePath)
        val retired =
            prepareStatement("SELECT 1 FROM legacy_object_deletions WHERE storage_path = ?").use {
                it.setString(1, blob.storagePath)
                it.executeQuery().use { rows -> rows.next() }
            }
        stagingCheck(!retired, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
        return blob
    }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long {
        if (operation.phase == IdentityAdoptionPhase.PREPARING) return operation.revision
        return prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1).also { value ->
                    stagingCheck(value > 0 && value <= operation.revision, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                }
            }
        }
    }

    private fun Connection.verificationTimes(admission: IdentityAdoptionBlobAdmission): Pair<Instant, Instant>? =
        prepareStatement(
            "SELECT created_at, verified_at FROM identity_adoption_staged_blobs WHERE id = ? AND operation_id = ? AND hold_generation = ?",
        ).use {
            it.setString(1, admission.id)
            it.setString(2, admission.operationId)
            it.setLong(3, admission.generation)
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    rows.getTimestamp("verified_at")?.let { verified ->
                        rows.getTimestamp("created_at").connectionInstant() to
                            verified.connectionInstant()
                    }
                } else {
                    null
                }
            }
        }

    private fun Connection.existing(
        operation: String,
        generation: Long,
        cid: String,
    ): IdentityAdoptionBlobAdmission? =
        prepareStatement(
            "SELECT * FROM identity_adoption_staged_blobs WHERE operation_id = ? AND hold_generation = ? AND cid = ? FOR UPDATE",
        ).use {
            it.setString(1, operation)
            it.setLong(2, generation)
            it.setString(3, cid)
            it.executeQuery().use { rows -> if (rows.next()) rows.view() else null }
        }

    private fun Connection.insert(
        operation: IdentityAdoptionOperation,
        admission: IdentityAdoptionBlobAdmission,
        now: Instant,
        verifiedAt: Instant? = null,
    ) {
        prepareStatement(
            "INSERT INTO identity_adoption_staged_blobs(id, operation_id, account_id, hold_generation, source_space, " +
                "cid, storage_path, size_bytes, mime_type, created_at, verified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, admission.id)
            it.setString(2, operation.id)
            it.setObject(3, operation.binding.accountId.toJavaUUID())
            it.setLong(4, admission.generation)
            it.setString(5, admission.sourceSpace)
            it.setString(6, admission.cid)
            it.setString(7, admission.storagePath)
            it.setLong(8, admission.size)
            it.setString(9, admission.mimeType)
            it.setTimestamp(10, now.timestamp())
            it.setTimestamp(11, verifiedAt?.timestamp())
            check(it.executeUpdate() == 1)
        }
    }

    private fun ResultSet.view() =
        IdentityAdoptionBlobAdmission(
            getString("id"),
            getString("operation_id"),
            getLong("hold_generation"),
            getString("source_space"),
            getString("cid"),
            getString("storage_path"),
            getLong("size_bytes"),
            getString("mime_type"),
        )

    private fun IdentityAdoptionBlobAdmission.matches(
        blob: SpaceBlob,
        operation: IdentityAdoptionOperation,
    ): Boolean =
        operationId == operation.id &&
            sourceSpace == SpaceRef.personal(operation.binding.sourceDid).uri &&
            cid == blob.cid &&
            storagePath == blob.storagePath &&
            size == blob.size &&
            mimeType == blob.mimeType
}

internal fun Connection.identityAdoptionRetainsObject(path: String): Boolean {
    val exists = metaData.getTables(null, "public", "identity_adoption_staged_blobs", arrayOf("TABLE")).use { it.next() }
    if (!exists) return false
    return prepareStatement("SELECT 1 FROM identity_adoption_staged_blobs WHERE storage_path = ? LIMIT 1").use {
        it.setString(1, path)
        it.executeQuery().use { rows -> rows.next() }
    }
}
