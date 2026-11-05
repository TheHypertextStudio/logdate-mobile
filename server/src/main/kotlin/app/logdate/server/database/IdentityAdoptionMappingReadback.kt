package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import java.sql.ResultSet

internal fun ResultSet.identityAdoptionSourceMapping(reference: IdentityAdoptionSourceReference? = null): IdentityAdoptionSourceMapping {
    val prepared = decodeIdentityAdoptionMapping(getBytes("body_bytes"))
    stagingCheck(
        (reference == null || prepared.request.source == reference) &&
            prepared.bodyDigest == getString("body_digest") &&
            prepared.keyHash == getString("source_key_hash") &&
            prepared.key.contentEquals(getBytes("source_key")) &&
            prepared.request.sourceDigest == getString("source_digest") &&
            prepared.request.disposition.name == getString("disposition"),
        IdentityAdoptionStagingFailure.CONFLICT,
    )
    return IdentityAdoptionSourceMapping(getLong("copy_generation"), prepared.request, prepared.bodyDigest, prepared.storageBytes)
}
