package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRole
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Instant

internal data class IdentityAdoptionGenericSource(
    val record: IdentityAdoptionSourceRecordKey,
    val version: Long,
    val deleted: Boolean,
    val deletedAt: String?,
)

internal fun Connection.identityAdoptionGenericSource(
    source: DataSource,
    operation: IdentityAdoptionOperation,
    request: IdentityAdoptionGenericRequest,
    now: Instant,
): IdentityAdoptionGenericSource {
    val reader = PostgreSQLIdentityAdoptionSourceInventory(source)
    val item = reader.itemLocked(this, operation, request.source.kind, request.source.key, now)
    stagingCheck(item != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    val original = requireNotNull(item)
    val hash = HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(request.source.kind, original))
    stagingCheck(hash == request.sourceDigest, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    val kind = request.source.kind
    val logical =
        if (kind == IdentityAdoptionSourceKind.REPO_BLOCK) {
            repositorySource(source, operation, request, now)
        } else {
            stagingCheck(request.repositoryRecord == null, IdentityAdoptionStagingFailure.INVALID_REQUEST)
            if (kind == IdentityAdoptionSourceKind.SPACE_RECORD) {
                val personal = SpaceRef.personal(operation.binding.sourceDid).uri
                stagingCheck(
                    request.source.key.take(2) == listOf(personal, operation.binding.sourceDid),
                    IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
                )
                IdentityAdoptionSourceRecordKey("app.logdate.crypto.sealed", request.source.key[2])
            } else {
                genericLogicalKey(request)
            }
        }
    val version =
        when (kind) {
            IdentityAdoptionSourceKind.SPACE_RECORD -> original.version("seq")
            IdentityAdoptionSourceKind.COLLECTION_RECORD, IdentityAdoptionSourceKind.SYNC_CONTENT,
            IdentityAdoptionSourceKind.SYNC_JOURNAL, IdentityAdoptionSourceKind.SYNC_ASSOCIATION,
            IdentityAdoptionSourceKind.LOCATION,
            -> original.version("server_version")
            IdentityAdoptionSourceKind.REPO_BLOCK -> repositoryVersion(operation)
            else -> throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
        }
    val deleted = (original.fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true
    val validDeletion = deleted == (request.record == null) && (!deleted || request.blobs.isEmpty())
    stagingCheck(validDeletion, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    return IdentityAdoptionGenericSource(logical, version, deleted, if (deleted) original.deletionTime() else null)
}

private fun Connection.repositorySource(
    source: DataSource,
    operation: IdentityAdoptionOperation,
    request: IdentityAdoptionGenericRequest,
    now: Instant,
): IdentityAdoptionSourceRecordKey {
    val proposed = request.repositoryRecord
    stagingCheck(proposed != null, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    val logical = requireNotNull(proposed)
    val inventory = PostgreSQLIdentityAdoptionRepositoryInventory(source).readLocked(this, operation, now)
    stagingCheck(inventory != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    val view = requireNotNull(inventory).inventory
    val cid = request.source.key.single()
    val record = view.records.any { it.cid == cid && it.collection == logical.collection && it.rkey == logical.rkey }
    val current =
        view.blocks
            .singleOrNull { it.cid == cid }
            ?.roles
            ?.contains(IdentityAdoptionRepositoryRole.CURRENT_RECORD) == true
    stagingCheck(record && current, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    return requireNotNull(logical)
}

private fun Connection.repositoryVersion(operation: IdentityAdoptionOperation): Long =
    prepareStatement("SELECT revision FROM atproto_repo_heads WHERE repo_did = ?").use {
        it.setString(1, operation.binding.sourceDid)
        it.executeQuery().use { rows ->
            stagingCheck(rows.next(), IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            rows.getLong(1).also { value -> stagingCheck(value >= 0, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE) }
        }
    }

private fun IdentityAdoptionSourceItem.version(key: String): Long {
    val primitive = fields[key] as? JsonPrimitive
    val value = primitive?.takeUnless { it.isString }?.longOrNull
    stagingCheck(value != null && value >= 0, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    return requireNotNull(value)
}

private fun IdentityAdoptionSourceItem.deletionTime(): String? {
    val value = fields["deleted_at"]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive
    val time =
        if (primitive?.isString == true) {
            runCatching { Instant.parse(primitive.content) }.getOrNull()
        } else {
            primitive?.longOrNull?.let(Instant::fromEpochMilliseconds)
        }
    stagingCheck(time != null, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    return requireNotNull(time).toString()
}
