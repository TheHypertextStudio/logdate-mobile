package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
enum class IdentityAdoptionSourceKindView {
    SPACE_STATE,
    SPACE_RECORD,
    SPACE_REFERENCE,
    SPACE_BLOB,
    COLLECTION_STATE,
    COLLECTION_RECORD,
    REPO_HEAD,
    REPO_COMMIT,
    REPO_BLOCK,
    SYNC_CONTENT,
    SYNC_JOURNAL,
    SYNC_ASSOCIATION,
    SYNC_MEDIA,
    SYNC_BACKUP,
    MEDIA,
    BACKUP,
    ATPROTO_BLOB,
    LOCATION,
    LOCATION_VERSION,
    STORAGE_AUTHORITY,
}

@Serializable
data class IdentityAdoptionSourceQueryView(
    val kind: IdentityAdoptionSourceKindView,
    val after: List<String>? = null,
    val limit: Int = 32,
) {
    override fun toString(): String = "IdentityAdoptionSourceQueryView()"
}

@Serializable
data class IdentityAdoptionSourceStartRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val query: IdentityAdoptionSourceQueryView,
) {
    override fun toString(): String = "IdentityAdoptionSourceStartRequest()"
}

@Serializable
data class IdentityAdoptionSourceReadRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val query: IdentityAdoptionSourceQueryView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
) {
    override fun toString(): String = "IdentityAdoptionSourceReadRequest()"
}

@Serializable
data class IdentityAdoptionSourceItemView(
    val evidence: String,
    val binary: String? = null,
) {
    override fun toString(): String = "IdentityAdoptionSourceItemView()"
}

@Serializable
data class IdentityAdoptionSourcePageView(
    val generation: Long,
    val fenced: Boolean,
    val kind: IdentityAdoptionSourceKindView,
    val present: Boolean,
    val count: Long,
    val items: List<IdentityAdoptionSourceItemView>,
    val next: List<String>?,
) {
    override fun toString(): String = "IdentityAdoptionSourcePageView()"
}

@Serializable
data class IdentityAdoptionSourceData(
    val operation: IdentityAdoptionOperationView,
    val page: IdentityAdoptionSourcePageView,
) {
    override fun toString(): String = "IdentityAdoptionSourceData()"
}

@Serializable
data class IdentityAdoptionSourceResponse(
    val success: Boolean,
    val data: IdentityAdoptionSourceData,
) {
    override fun toString(): String = "IdentityAdoptionSourceResponse()"
}
