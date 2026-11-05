package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
enum class IdentityAdoptionCopyDispositionView { CONVERTED, STRUCTURAL, RETAINED_HISTORY, MEDIA, REFERENCES }

@Serializable
data class IdentityAdoptionCopyTargetView(
    val rkey: String,
    val digest: String,
) {
    override fun toString(): String = "IdentityAdoptionCopyTargetView()"
}

@Serializable
data class IdentityAdoptionCopyMappingView(
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
    val disposition: IdentityAdoptionCopyDispositionView,
    val targets: List<IdentityAdoptionCopyTargetView>,
    val media: List<String>,
) {
    override fun toString(): String = "IdentityAdoptionCopyMappingView()"
}

@Serializable
data class IdentityAdoptionCopyMappingRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val mapping: IdentityAdoptionCopyMappingView,
) {
    override fun toString(): String = "IdentityAdoptionCopyMappingRequest()"
}

@Serializable
data class IdentityAdoptionMappingReadRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val source: IdentityAdoptionSourceReferenceView,
) {
    override fun toString(): String = "IdentityAdoptionMappingReadRequest()"
}

@Serializable
data class IdentityAdoptionSavedMappingView(
    val generation: Long,
    val mapping: IdentityAdoptionCopyMappingView,
    val bodyDigest: String,
    val storageBytes: Long,
) {
    override fun toString(): String = "IdentityAdoptionSavedMappingView()"
}

@Serializable
data class IdentityAdoptionCopyMappingData(
    val operation: IdentityAdoptionOperationView,
    val mapping: IdentityAdoptionSavedMappingView,
) {
    override fun toString(): String = "IdentityAdoptionCopyMappingData()"
}

@Serializable
data class IdentityAdoptionCopyMappingResponse(
    val success: Boolean,
    val data: IdentityAdoptionCopyMappingData,
) {
    override fun toString(): String = "IdentityAdoptionCopyMappingResponse()"
}
