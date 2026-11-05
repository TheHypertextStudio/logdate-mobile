package app.logdate.server.identity.adoption

/** Private byte-level coverage; authenticated conversion and atomic authority change remain separate. */
internal data class IdentityAdoptionCoverage(
    val source: IdentityAdoptionSourceCensus,
    val mappingCount: Long,
    val mappingsDigest: String,
    val targetRecords: Long,
    val targetMedia: Long,
    val mediaDigest: String,
    val originCount: Long,
    val originsDigest: String,
    val targetInventoryDigest: String,
    val digest: String,
) {
    override fun toString(): String = "IdentityAdoptionCoverage()"
}
