package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionCopyCoverageRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
) {
    override fun toString(): String = "IdentityAdoptionCopyCoverageRequest()"
}

@Serializable
data class IdentityAdoptionSourceSummaryView(
    val kind: IdentityAdoptionSourceKindView,
    val present: Boolean,
    val count: Long,
    val digest: String,
) {
    override fun toString(): String = "IdentityAdoptionSourceSummaryView()"
}

@Serializable
data class IdentityAdoptionCopyCoverageView(
    val generation: Long,
    val sourceSequence: Long,
    val sourceDigest: String,
    val sources: List<IdentityAdoptionSourceSummaryView>,
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
    override fun toString(): String = "IdentityAdoptionCopyCoverageView()"
}

@Serializable
data class IdentityAdoptionCopyCoverageData(
    val operation: IdentityAdoptionOperationView,
    val coverage: IdentityAdoptionCopyCoverageView,
) {
    override fun toString(): String = "IdentityAdoptionCopyCoverageData()"
}

@Serializable
data class IdentityAdoptionCopyCoverageResponse(
    val success: Boolean,
    val data: IdentityAdoptionCopyCoverageData,
) {
    override fun toString(): String = "IdentityAdoptionCopyCoverageResponse()"
}
