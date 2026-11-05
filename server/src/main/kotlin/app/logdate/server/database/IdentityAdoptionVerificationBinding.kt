@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.space.SpaceRef
import app.logdate.space.adoption.IdentityAdoptionBlobSummary
import app.logdate.space.adoption.IdentityAdoptionManifest
import app.logdate.space.adoption.IdentityAdoptionRecordSummary

internal fun requireIdentityAdoptionManifestBinding(
    operation: IdentityAdoptionOperation,
    origin: String,
    manifest: IdentityAdoptionManifest,
) {
    val value = manifest.binding
    val binding = operation.binding
    val same =
        value.operationId.toString() == operation.id &&
            value.accountId == binding.accountId &&
            value.backendOrigin == origin &&
            value.deviceId == binding.deviceId &&
            value.sourceEpoch == binding.sourceEpoch &&
            value.source == SpaceRef.personal(binding.sourceDid) &&
            value.target == SpaceRef.personal(binding.targetDid)
    stagingCheck(same, IdentityAdoptionStagingFailure.CONFLICT)
}

internal fun sameIdentityAdoptionRecords(
    a: IdentityAdoptionRecordSummary,
    b: IdentityAdoptionRecordSummary,
): Boolean =
    a.count == b.count && a.live == b.live && a.tombstones == b.tombstones && a.digest.toByteArray().contentEquals(b.digest.toByteArray())

internal fun sameIdentityAdoptionBlobs(
    a: IdentityAdoptionBlobSummary,
    b: IdentityAdoptionBlobSummary,
): Boolean = a.count == b.count && a.bytes == b.bytes && a.digest.toByteArray().contentEquals(b.digest.toByteArray())
