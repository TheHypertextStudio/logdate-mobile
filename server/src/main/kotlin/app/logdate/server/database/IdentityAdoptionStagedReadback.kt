package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.ResultSet

internal fun ResultSet.identityAdoptionStagedRecord(): IdentityAdoptionStagedRecord {
    val bytes = getBytes("target_record")
    stagingCheck(stagingHash(bytes) == getString("target_digest"), IdentityAdoptionStagingFailure.CONFLICT)
    val record =
        if (getBoolean("deleted")) {
            null
        } else {
            decodeRecord(bytes).also {
                stagingEnvelope(it, IdentityAdoptionStagingFailure.CONFLICT)
                val (cid, canonical) = recordCid(it)
                stagingCheck(
                    cid == getString("target_cid") && bytes.contentEquals(canonical),
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
            }
        }
    return IdentityAdoptionStagedRecord(
        getLong("hold_generation"),
        getString("rkey"),
        getString("source_collection"),
        getString("source_rkey"),
        getLong("source_version"),
        getString("source_digest"),
        getString("target_digest"),
        getString("target_cid"),
        record,
        (DagCborCodec.decode(getBytes("blob_cids")) as JsonArray).map { it.jsonPrimitive.content },
        tombstone = if (getBoolean("deleted")) decodeRecord(bytes) else null,
        targetSequence = getLong("target_sequence").let { if (wasNull()) null else it },
    )
}
