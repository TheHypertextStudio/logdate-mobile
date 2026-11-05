package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionCoverage
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec

/** Rebind hashes only; every database read must have used the actual current VERIFIED operation. */
internal fun rebindIdentityAdoptionCoverage(
    current: IdentityAdoptionCoverage,
    operation: IdentityAdoptionOperation,
    sourceRevision: Long,
): IdentityAdoptionCoverage {
    val valid =
        operation.phase == IdentityAdoptionPhase.VERIFIED &&
            sourceRevision > 0 &&
            sourceRevision == operation.revision - 1 &&
            current.source.operationId == operation.id &&
            current.source.revision == operation.revision &&
            current.source.fenced &&
            current.source.generation in 1..sourceRevision
    stagingCheck(valid, IdentityAdoptionStagingFailure.CONFLICT)
    val original = operation.copy(revision = sourceRevision, phase = IdentityAdoptionPhase.FENCED)
    val digest =
        IdentityAdoptionSourceEvidence.census(
            original,
            current.source.generation,
            true,
            current.source.sources,
            current.source.repository,
        )
    val rebound = current.copy(source = current.source.copy(revision = sourceRevision, digest = digest))
    return rebound.copy(digest = identityAdoptionCoverageDigest(rebound))
}

internal fun identityAdoptionCoverageDigest(value: IdentityAdoptionCoverage): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.coverage")
                put("sourceCensus", value.source.digest)
                put("mappingCount", value.mappingCount)
                put("mappingsDigest", value.mappingsDigest)
                put("targetRecords", value.targetRecords)
                put("targetInventoryDigest", value.targetInventoryDigest)
                put("originCount", value.originCount)
                put("originsDigest", value.originsDigest)
                put("targetMedia", value.targetMedia)
                put("mediaDigest", value.mediaDigest)
            },
        ),
    )
