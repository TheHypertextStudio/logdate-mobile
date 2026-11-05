@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import java.sql.Connection
import kotlin.time.Instant

internal fun Connection.lockMediaUploadOwner(operation: IdentityAdoptionOperation) {
    prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE").use {
        it.setObject(1, operation.binding.accountId.toJavaUUID())
        it.executeQuery().close()
    }
}

internal fun Connection.insertMediaUpload(
    operation: IdentityAdoptionOperation,
    upload: IdentityAdoptionMediaUpload,
    blobId: String,
    ownerToken: String,
    path: String,
    now: Instant,
) {
    prepareStatement(
        "INSERT INTO identity_adoption_media_uploads(id,operation_id,account_id,identity_epoch,source_kind,source_key," +
            "source_digest,cid,size_bytes,blob_id,owner_token,storage_path,phase,created_at,last_used_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'ADMITTED',?,?)",
    ).use {
        it.setString(1, upload.uploadId)
        it.setString(2, operation.id)
        it.setObject(3, operation.binding.accountId.toJavaUUID())
        it.setLong(4, operation.binding.sourceEpoch)
        it.setString(5, upload.source.kind.name)
        it.setString(6, upload.source.key.single())
        it.setString(7, upload.sourceDigest)
        it.setString(8, upload.cid)
        it.setLong(9, upload.size)
        it.setString(10, blobId)
        it.setString(11, ownerToken)
        it.setString(12, path)
        it.setTimestamp(13, now.timestamp())
        it.setTimestamp(14, now.timestamp())
        check(it.executeUpdate() == 1)
    }
}

internal fun Connection.pinMediaUpload(
    operation: IdentityAdoptionOperation,
    admission: IdentityAdoptionBlobAdmission,
    now: Instant,
    keepExisting: Boolean = false,
) {
    val conflict =
        prepareStatement(
            "SELECT storage_path FROM identity_adoption_staged_blobs WHERE operation_id=? AND hold_generation=? AND cid=? FOR UPDATE",
        ).use {
            it.setString(1, operation.id)
            it.setLong(2, admission.generation)
            it.setString(3, admission.cid)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    if (conflict != null && (keepExisting || conflict == admission.storagePath)) return
    if (conflict != null) {
        prepareStatement(
            "DELETE FROM identity_adoption_staged_blobs WHERE operation_id=? AND account_id=? AND hold_generation=? AND cid=?",
        ).use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.setLong(3, admission.generation)
            it.setString(4, admission.cid)
            check(it.executeUpdate() == 1)
        }
    }
    prepareStatement("DELETE FROM identity_adoption_staged_blobs WHERE operation_id=? AND account_id=? AND id=?").use {
        it.setString(1, operation.id)
        it.setObject(2, operation.binding.accountId.toJavaUUID())
        it.setString(3, admission.id)
        it.executeUpdate()
    }
    prepareStatement(
        "INSERT INTO identity_adoption_staged_blobs(id,operation_id,account_id,hold_generation,source_space,cid,storage_path,size_bytes," +
            "mime_type,created_at,verified_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
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
        it.setTimestamp(11, now.timestamp())
        check(it.executeUpdate() == 1)
    }
}
