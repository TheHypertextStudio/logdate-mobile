package app.logdate.server.identity.adoption

internal data class IdentityAdoptionSourceSummary(
    val kind: IdentityAdoptionSourceKind,
    val present: Boolean,
    val count: Long,
    val digest: String,
)

internal data class IdentityAdoptionSourceCensus(
    val operationId: String,
    val revision: Long,
    val generation: Long,
    val fenced: Boolean,
    val sources: List<IdentityAdoptionSourceSummary>,
    val repository: IdentityAdoptionRepositoryInventory,
    val digest: String,
)
