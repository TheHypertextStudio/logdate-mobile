@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionPrivateMediaReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.space.SpaceRef
import java.sql.Connection
import java.sql.ResultSet
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Instant

internal data class IdentityAdoptionMediaUploadRow(
    val upload: IdentityAdoptionMediaUpload,
    val operationId: String,
    val accountId: String,
    val epoch: Long,
    val blobId: String,
    val ownerToken: String,
    val path: String,
    val phase: String,
) {
    fun admission(
        operation: IdentityAdoptionOperation,
        generation: Long,
    ) = IdentityAdoptionBlobAdmission(
        upload.uploadId,
        operation.id,
        generation,
        SpaceRef.personal(operation.binding.sourceDid).uri,
        upload.cid,
        path,
        upload.size,
        "application/octet-stream",
    )

    fun receipt(generation: Long) = IdentityAdoptionPrivateMediaReceipt(upload.uploadId, generation, upload.cid, upload.size, phase)
}

internal fun Connection.mediaUpload(
    operation: IdentityAdoptionOperation,
    id: String,
): IdentityAdoptionMediaUploadRow? =
    prepareStatement(
        "SELECT * FROM identity_adoption_media_uploads WHERE id=? AND operation_id=? AND account_id=? AND identity_epoch=? FOR UPDATE",
    ).use {
        it.setString(1, id)
        it.setString(2, operation.id)
        it.setObject(3, operation.binding.accountId.toJavaUUID())
        it.setLong(4, operation.binding.sourceEpoch)
        it.executeQuery().use { rows -> if (rows.next()) rows.mediaUploadRow() else null }
    }

internal fun ResultSet.mediaUploadRow() =
    IdentityAdoptionMediaUploadRow(
        IdentityAdoptionMediaUpload(
            getString("id"),
            IdentityAdoptionSourceReference(
                IdentityAdoptionSourceKind.valueOf(getString("source_kind")),
                listOf(getString("source_key")),
            ),
            getString("source_digest"),
            getString("cid"),
            getLong("size_bytes"),
        ),
        getString("operation_id"),
        getString("account_id"),
        getLong("identity_epoch"),
        getString("blob_id"),
        getString("owner_token"),
        getString("storage_path"),
        getString("phase"),
    )

internal fun Connection.requireMediaUploadSource(
    source: DataSource,
    operation: IdentityAdoptionOperation,
    upload: IdentityAdoptionMediaUpload,
    now: Instant,
) {
    val item = PostgreSQLIdentityAdoptionSourceInventory(source).itemLocked(this, operation, upload.source.kind, upload.source.key, now)
    stagingCheck(item != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    val digest = HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(upload.source.kind, requireNotNull(item)))
    stagingCheck(digest == upload.sourceDigest, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
}

internal fun Connection.mediaUploadGeneration(operation: IdentityAdoptionOperation): Long {
    if (operation.phase == IdentityAdoptionPhase.PREPARING) return operation.revision
    return prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id=? AND account_id=?").use {
        it.setString(1, operation.id)
        it.setObject(2, operation.binding.accountId.toJavaUUID())
        it.executeQuery().use { rows ->
            check(rows.next())
            rows.getLong(1).also { generation -> stagingCheck(generation > 0, IdentityAdoptionStagingFailure.SOURCE_CHANGED) }
        }
    }
}

internal fun Connection.mediaUploadPhase(
    id: String,
    from: String,
    to: String,
    now: Instant,
): Boolean =
    prepareStatement("UPDATE identity_adoption_media_uploads SET phase=?,settled_at=? WHERE id=? AND phase=?").use {
        it.setString(1, to)
        it.setTimestamp(2, if (to == "WRITING") null else now.timestamp())
        it.setString(3, id)
        it.setString(4, from)
        it.executeUpdate() == 1
    }

/** Only a privately owned upload with matching source evidence can substitute for a SPACE_BLOB pin. */
internal fun Connection.identityAdoptionUploadedBlob(
    source: DataSource,
    operation: IdentityAdoptionOperation,
    admission: IdentityAdoptionBlobAdmission,
    now: Instant,
): Boolean {
    val row = mediaUpload(operation, admission.id) ?: return false
    if (row.phase !in setOf("VERIFIED", "PROMOTED") || row.admission(operation, admission.generation) != admission) return false
    requireMediaUploadSource(source, operation, row.upload, now)
    return true
}

internal fun Connection.requireMediaUploadMapping(
    operation: IdentityAdoptionOperation,
    request: app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest,
) {
    if (request.source.kind !in
        setOf(
            IdentityAdoptionSourceKind.MEDIA,
            IdentityAdoptionSourceKind.SYNC_MEDIA,
            IdentityAdoptionSourceKind.ATPROTO_BLOB,
        )
    ) {
        return
    }
    for (cid in request.media) {
        val bound =
            prepareStatement(
                "SELECT 1 FROM identity_adoption_media_uploads WHERE operation_id=? AND account_id=? " +
                    "AND source_kind=? AND source_key=? AND source_digest=? AND cid=? AND phase='VERIFIED'",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                it.setString(3, request.source.kind.name)
                it.setString(4, request.source.key.single())
                it.setString(5, request.sourceDigest)
                it.setString(6, cid)
                it.executeQuery().use { rows -> rows.next() }
            }
        stagingCheck(bound, IdentityAdoptionStagingFailure.CONFLICT)
    }
}

/** A fresh device proof renews the private attempt, without extending the source write hold. */
internal fun Connection.touchMediaUpload(
    id: String,
    now: Instant,
) {
    prepareStatement("UPDATE identity_adoption_media_uploads SET last_used_at=GREATEST(last_used_at,?) WHERE id=?").use {
        it.setTimestamp(1, now.timestamp())
        it.setString(2, id)
        check(it.executeUpdate() == 1)
    }
}
