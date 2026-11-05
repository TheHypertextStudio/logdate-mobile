package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Staging records proposed dispositions; final verification additionally requires the actual repository roles. */
internal object IdentityAdoptionMappingPolicy {
    fun staging(
        request: IdentityAdoptionMappingRequest,
        item: IdentityAdoptionSourceItem,
        targets: List<IdentityAdoptionStagedRecord>,
        hasReferences: Boolean,
        retired: Boolean = false,
    ) {
        validate(request, item, targets, hasReferences, null, retired)
    }

    fun final(
        request: IdentityAdoptionMappingRequest,
        item: IdentityAdoptionSourceItem,
        targets: List<IdentityAdoptionStagedRecord>,
        hasReferences: Boolean,
        roles: Set<IdentityAdoptionRepositoryRole>?,
        retired: Boolean = false,
    ) {
        if (request.source.kind == IdentityAdoptionSourceKind.REPO_BLOCK) check(roles != null)
        validate(request, item, targets, hasReferences, roles, retired)
    }

    private fun validate(
        request: IdentityAdoptionMappingRequest,
        item: IdentityAdoptionSourceItem,
        targets: List<IdentityAdoptionStagedRecord>,
        hasReferences: Boolean,
        roles: Set<IdentityAdoptionRepositoryRole>?,
        retired: Boolean,
    ) {
        val kind = request.source.kind
        val deleted = (item.fields["deleted"] as? JsonPrimitive)?.booleanOrNull == true
        val permitted =
            if (retired) {
                setOf(IdentityAdoptionDisposition.RETAINED_HISTORY)
            } else {
                when (kind) {
                    IdentityAdoptionSourceKind.SPACE_STATE, IdentityAdoptionSourceKind.COLLECTION_STATE,
                    IdentityAdoptionSourceKind.REPO_HEAD, IdentityAdoptionSourceKind.REPO_COMMIT,
                    IdentityAdoptionSourceKind.LOCATION_VERSION, IdentityAdoptionSourceKind.STORAGE_AUTHORITY,
                    -> setOf(IdentityAdoptionDisposition.STRUCTURAL)
                    IdentityAdoptionSourceKind.BACKUP, IdentityAdoptionSourceKind.SYNC_BACKUP -> {
                        setOf(IdentityAdoptionDisposition.RETAINED_HISTORY)
                    }
                    IdentityAdoptionSourceKind.SPACE_REFERENCE -> setOf(IdentityAdoptionDisposition.REFERENCES)
                    IdentityAdoptionSourceKind.SPACE_BLOB, IdentityAdoptionSourceKind.SYNC_MEDIA,
                    IdentityAdoptionSourceKind.MEDIA, IdentityAdoptionSourceKind.ATPROTO_BLOB,
                    -> {
                        if (deleted || (kind == IdentityAdoptionSourceKind.SPACE_BLOB && !hasReferences)) {
                            setOf(IdentityAdoptionDisposition.MEDIA, IdentityAdoptionDisposition.RETAINED_HISTORY)
                        } else {
                            setOf(IdentityAdoptionDisposition.MEDIA)
                        }
                    }
                    IdentityAdoptionSourceKind.REPO_BLOCK -> repositoryDispositions(roles)
                    else -> setOf(IdentityAdoptionDisposition.CONVERTED)
                }
            }
        requireMapping(request.disposition in permitted)
        when (request.disposition) {
            IdentityAdoptionDisposition.CONVERTED -> {
                requireMapping(targets.isNotEmpty() && targets.all { (it.record == null) == deleted })
                requireMapping(targets.flatMap { it.blobs }.containsAll(request.media))
            }
            IdentityAdoptionDisposition.STRUCTURAL, IdentityAdoptionDisposition.RETAINED_HISTORY -> {
                requireMapping(targets.isEmpty() && request.media.isEmpty())
            }
            IdentityAdoptionDisposition.MEDIA -> requireMapping(targets.isEmpty() && request.media.isNotEmpty())
            IdentityAdoptionDisposition.REFERENCES -> {
                requireMapping(targets.isNotEmpty() && targets.all { it.record != null } && request.media.isNotEmpty())
                requireMapping(targets.flatMap { it.blobs }.containsAll(request.media))
            }
        }
    }

    private fun repositoryDispositions(roles: Set<IdentityAdoptionRepositoryRole>?): Set<IdentityAdoptionDisposition> {
        if (roles == null) {
            return setOf(
                IdentityAdoptionDisposition.CONVERTED,
                IdentityAdoptionDisposition.STRUCTURAL,
                IdentityAdoptionDisposition.RETAINED_HISTORY,
            )
        }
        if (IdentityAdoptionRepositoryRole.CURRENT_RECORD in roles) return setOf(IdentityAdoptionDisposition.CONVERTED)
        if (IdentityAdoptionRepositoryRole.HISTORICAL_RECORD in roles ||
            IdentityAdoptionRepositoryRole.UNREFERENCED in roles ||
            IdentityAdoptionRepositoryRole.RAW in roles
        ) {
            return setOf(IdentityAdoptionDisposition.RETAINED_HISTORY)
        }
        return setOf(IdentityAdoptionDisposition.STRUCTURAL)
    }

    private fun requireMapping(value: Boolean) {
        if (!value) throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
    }
}
