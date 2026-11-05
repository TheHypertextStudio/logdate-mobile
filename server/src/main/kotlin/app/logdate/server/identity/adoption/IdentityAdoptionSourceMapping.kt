package app.logdate.server.identity.adoption

internal enum class IdentityAdoptionDisposition { CONVERTED, STRUCTURAL, RETAINED_HISTORY, MEDIA, REFERENCES }

internal data class IdentityAdoptionSourceReference(
    val kind: IdentityAdoptionSourceKind,
    val key: List<String>,
)

internal data class IdentityAdoptionTargetReference(
    val rkey: String,
    val digest: String,
)

internal data class IdentityAdoptionMappingRequest(
    val source: IdentityAdoptionSourceReference,
    val sourceDigest: String,
    val disposition: IdentityAdoptionDisposition,
    val targets: List<IdentityAdoptionTargetReference>,
    val media: List<String>,
) {
    override fun toString(): String = "IdentityAdoptionMappingRequest()"
}

internal data class IdentityAdoptionSourceMapping(
    val generation: Long,
    val request: IdentityAdoptionMappingRequest,
    val bodyDigest: String,
    val storageBytes: Long,
) {
    override fun toString(): String = "IdentityAdoptionSourceMapping()"
}
