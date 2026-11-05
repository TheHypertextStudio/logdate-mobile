package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionCopyCoverage
import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.shared.model.identity.IdentityAdoptionCopyCoverageView
import app.logdate.shared.model.identity.IdentityAdoptionCopyDispositionView
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingView
import app.logdate.shared.model.identity.IdentityAdoptionCopyTargetView
import app.logdate.shared.model.identity.IdentityAdoptionSavedMappingView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourceSummaryView

internal fun IdentityAdoptionSourceMapping.wire() =
    IdentityAdoptionSavedMappingView(
        generation,
        IdentityAdoptionCopyMappingView(
            request.source.wire(),
            request.sourceDigest,
            IdentityAdoptionCopyDispositionView.valueOf(request.disposition.name),
            request.targets.map { IdentityAdoptionCopyTargetView(it.rkey, it.digest) },
            request.media,
        ),
        bodyDigest,
        storageBytes,
    )

internal fun IdentityAdoptionCopyCoverage.wire(): IdentityAdoptionCopyCoverageView {
    val value = coverage
    return IdentityAdoptionCopyCoverageView(
        value.source.generation,
        sourceSequence,
        value.source.digest,
        value.source.sources.map {
            IdentityAdoptionSourceSummaryView(
                IdentityAdoptionSourceKindView.valueOf(it.kind.name),
                it.present,
                it.count,
                it.digest,
            )
        },
        value.mappingCount,
        value.mappingsDigest,
        value.targetRecords,
        value.targetMedia,
        value.mediaDigest,
        value.originCount,
        value.originsDigest,
        value.targetInventoryDigest,
        value.digest,
    )
}
