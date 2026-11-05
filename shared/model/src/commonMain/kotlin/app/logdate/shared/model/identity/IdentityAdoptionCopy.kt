package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionLogicalRecordView(
    val collection: String,
    val rkey: String,
) {
    override fun toString(): String = "IdentityAdoptionLogicalRecordView()"
}

@Serializable
data class IdentityAdoptionSourceReferenceView(
    val kind: IdentityAdoptionSourceKindView,
    val key: List<String>,
) {
    override fun toString(): String = "IdentityAdoptionSourceReferenceView()"
}

@Serializable
data class IdentityAdoptionSealedCopyView(
    val version: Long,
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
) {
    override fun toString(): String = "IdentityAdoptionSealedCopyView()"
}

@Serializable
data class IdentityAdoptionCopyRecordView(
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
    val rkey: String,
    val record: IdentityAdoptionSealedCopyView?,
    val blobs: List<String>,
    val repositoryRecord: IdentityAdoptionLogicalRecordView? = null,
) {
    override fun toString(): String = "IdentityAdoptionCopyRecordView()"
}

@Serializable
data class IdentityAdoptionCopyStartRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val action: IdentityAdoptionActionView,
    val bodyDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionCopyStartRequest()"
}

@Serializable
data class IdentityAdoptionCopyRecordRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val copy: IdentityAdoptionCopyRecordView,
) {
    override fun toString(): String = "IdentityAdoptionCopyRecordRequest()"
}

@Serializable
data class IdentityAdoptionCopyReadRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
    val rkey: String,
    val repositoryRecord: IdentityAdoptionLogicalRecordView? = null,
) {
    override fun toString(): String = "IdentityAdoptionCopyReadRequest()"
}
