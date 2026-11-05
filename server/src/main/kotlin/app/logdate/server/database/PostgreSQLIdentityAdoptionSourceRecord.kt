@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import java.sql.ResultSet

/** This adapter covers personal spaces. Legacy sources require their own complete raw inventory adapter. */
internal fun Connection.identityAdoptionSourceRecord(
    operation: IdentityAdoptionOperation,
    request: PreparedIdentityAdoptionRecord,
): ByteArray {
    val b = operation.binding
    val mode =
        prepareStatement("SELECT storage_mode FROM accounts WHERE id = ?").use {
            it.setObject(1, b.accountId.toJavaUUID())
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    stagingCheck(mode == AccountStorageMode.PERSONAL_SPACE_V1.wireValue, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    val space = SpaceRef.personal(b.sourceDid).uri
    val bytes =
        prepareStatement(
            "SELECT * FROM space_records WHERE account_id = ? AND space_uri = ? AND author_did = ? AND rkey = ?",
        ).use {
            it.setObject(1, b.accountId.toJavaUUID())
            it.setString(2, space)
            it.setString(3, b.sourceDid)
            it.setString(4, request.rkey)
            it.executeQuery().use { rows ->
                stagingCheck(rows.next(), IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                stagingCheck(
                    rows.getLong("seq") == request.sourceVersion && rows.getBoolean("deleted") == (request.targetBytes == null),
                    IdentityAdoptionStagingFailure.SOURCE_CHANGED,
                )
                rows.sourceBytes()
            }
        }
    stagingCheck(stagingHash(bytes) == request.sourceDigest, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    stagingCheck(sourceBlobCids(operation, space, request.rkey) == request.blobCids, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    return bytes
}

private fun ResultSet.sourceBytes(): ByteArray {
    if (getBoolean("deleted")) {
        stagingCheck(
            getBytes("record") == null && getString("cid") == null && getInt("size_bytes") == 0 && getTimestamp("deleted_at") != null,
            IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
        )
        return DagCborCodec.encode(
            buildJsonObject {
                put("deleted", true)
                put("rkey", getString("rkey"))
                put("seq", getLong("seq"))
                put("deletedAt", getTimestamp("deleted_at").connectionInstant().toString())
            },
        )
    }
    val bytes = getBytes("record")
    stagingCheck(bytes != null, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    val raw = requireNotNull(bytes)
    val decoded = decodeRecord(raw)
    stagingEnvelope(decoded, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
    val (cid, encoded) = recordCid(decoded)
    stagingCheck(
        raw.contentEquals(encoded) && cid == getString("cid") && raw.size == getInt("size_bytes"),
        IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
    )
    return raw
}

private fun Connection.sourceBlobCids(
    operation: IdentityAdoptionOperation,
    space: String,
    key: String,
): List<String> =
    prepareStatement(
        "SELECT r.cid, b.account_id, b.purge_operation_id FROM space_record_blobs r LEFT JOIN space_blobs b " +
            "ON b.space_uri = r.space_uri AND b.cid = r.cid WHERE r.space_uri = ? AND r.author_did = ? AND r.rkey = ? " +
            "ORDER BY r.cid COLLATE \"C\"",
    ).use {
        it.setString(1, space)
        it.setString(2, operation.binding.sourceDid)
        it.setString(3, key)
        it.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    stagingCheck(
                        rows.getObject("account_id") == operation.binding.accountId.toJavaUUID() &&
                            rows.getString("purge_operation_id") == null,
                        IdentityAdoptionStagingFailure.SOURCE_CHANGED,
                    )
                    add(rows.getString("cid"))
                }
            }
        }
    }
