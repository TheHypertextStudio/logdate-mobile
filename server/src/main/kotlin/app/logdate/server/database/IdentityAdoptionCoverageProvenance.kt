package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.space.SpaceRef
import java.sql.Connection

/** Personal-space candidates retain the original staging adapter's exact source and media contract. */
internal fun Connection.requireIdentityAdoptionPersonalProvenance(
    operation: IdentityAdoptionOperation,
    reference: IdentityAdoptionSourceReference,
    targets: List<IdentityAdoptionStagedRecord>,
) {
    if (reference.kind != IdentityAdoptionSourceKind.SPACE_RECORD) return
    val personal = SpaceRef.personal(operation.binding.sourceDid).uri
    stagingCheck(
        reference.key.size == 3 && reference.key[0] == personal && reference.key[1] == operation.binding.sourceDid,
        IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
    )
    stagingCheck(targets.size == 1, IdentityAdoptionStagingFailure.CONFLICT)
    val target = targets.single()
    val key = reference.key[2]
    val sameSource = target.sourceCollection == "app.logdate.crypto.sealed" && target.sourceRkey == key && target.rkey == key
    stagingCheck(sameSource, IdentityAdoptionStagingFailure.CONFLICT)
    val request = IdentityAdoptionStagingRequest(key, target.sourceVersion, target.sourceDigest, target.record, target.blobs)
    identityAdoptionSourceRecord(operation, prepareIdentityAdoptionRecord(request))
}
