package app.logdate.shared.model.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identifier for the originating device of a sync operation.
 * Avoids nullable device IDs; defaults to [UNKNOWN] when a concrete device ID is not available.
 */
@JvmInline
@Serializable
value class DeviceId(
    val value: String,
) {
    companion object {
        val UNKNOWN = DeviceId("unknown")
    }
}

/**
 * Version constraint supplied by the client when updating existing entities.
 * Using a sealed hierarchy eliminates nullable version hints.
 */
@Serializable
sealed class VersionConstraint {
    /**
     * No version constraint provided; server may apply LWW or its own policy.
     */
    @Serializable
    @SerialName("none")
    data object None : VersionConstraint()

    /**
     * Client knows the last server version and wants conflict detection.
     */
    @Serializable
    @SerialName("known")
    data class Known(
        val serverVersion: Long,
    ) : VersionConstraint()
}

/**
 * All timestamps are milliseconds since epoch (UTC).
 */
@Serializable
data class ContentUploadRequest(
    val id: String,
    val type: String, // TEXT, IMAGE, VIDEO, AUDIO, HANDWRITING
    val content: String?,
    val mediaUri: String?,
    val durationMs: Long = 0,
    val createdAt: Long,
    val lastUpdated: Long,
    val syncVersion: Long = 0,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    /**
     * User-authored caption for an image or video.
     *
     * Optional so older clients and non-media entries round-trip unchanged; absent means the
     * entry has no caption, not that the caption is unknown.
     */
    val caption: String? = null,
    val photoPresentation: String? = null,
    /**
     * Encrypted note location, or null when the entry has none.
     *
     * Carried as an opaque ciphertext string for the same reason note text is: the server must
     * not hold a plaintext location history. Clients encrypt with the sync payload cipher before
     * upload and decrypt after download.
     */
    val location: String? = null,
    /** Encrypted structured audio transcript. Omission preserves it while the source media is unchanged. */
    val transcript: String? = null,
    /**
     * The device the entry was captured on, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedOn: String? = null,
    /**
     * What the person was doing at capture, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedDuring: String? = null,
)

@Serializable
data class ContentUploadResponse(
    val id: String,
    val serverVersion: Long,
    val uploadedAt: Long,
)

@Serializable
data class ContentUpdateRequest(
    val content: String? = null,
    val mediaUri: String? = null,
    /** Omission preserves the saved duration; an explicit zero remains a duration change. */
    val durationMs: Long? = null,
    val lastUpdated: Long,
    val syncVersion: Long = 0,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    val versionConstraint: VersionConstraint = VersionConstraint.None,
    /**
     * User-authored caption for an image or video.
     *
     * Optional so older clients and non-media entries round-trip unchanged; absent means the
     * entry has no caption, not that the caption is unknown.
     */
    val caption: String? = null,
    val photoPresentation: String? = null,
    /**
     * Encrypted note location, or null when the entry has none.
     *
     * Carried as an opaque ciphertext string for the same reason note text is: the server must
     * not hold a plaintext location history. Clients encrypt with the sync payload cipher before
     * upload and decrypt after download.
     */
    val location: String? = null,
    /** Encrypted structured audio transcript. Omission preserves it while the source media is unchanged. */
    val transcript: String? = null,
    /**
     * The device the entry was captured on, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedOn: String? = null,
    /**
     * What the person was doing at capture, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedDuring: String? = null,
)

@Serializable
data class ContentUpdateResponse(
    val id: String,
    val serverVersion: Long,
    val updatedAt: Long,
)

@Serializable
data class ContentChangesResponse(
    val changes: List<ContentChange>,
    val deletions: List<ContentDeletion>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)

@Serializable
data class ContentChange(
    val id: String,
    val type: String,
    val content: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0,
    val createdAt: Long,
    val lastUpdated: Long,
    val serverVersion: Long,
    val isDeleted: Boolean = false,
    /** User-authored caption for an image or video; absent for entries without one. */
    val caption: String? = null,
    val photoPresentation: String? = null,
    /** Encrypted note location; absent for entries without one. */
    val location: String? = null,
    /** Encrypted structured audio transcript. Omission preserves an existing transcript. */
    val transcript: String? = null,
    /**
     * The device the entry was captured on, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedOn: String? = null,
    /**
     * What the person was doing at capture, encrypted by the apps, or null when it was not recorded.
     * The server stores and returns it without reading it; an update that omits it leaves it as it is.
     */
    val capturedDuring: String? = null,
)

@Serializable
data class ContentDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

@Serializable
data class JournalUploadRequest(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val lastUpdated: Long,
    val syncVersion: Long = 0,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
)

@Serializable
data class JournalUploadResponse(
    val id: String,
    val serverVersion: Long,
    val uploadedAt: Long,
)

@Serializable
data class JournalUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val lastUpdated: Long,
    val syncVersion: Long = 0,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    val versionConstraint: VersionConstraint = VersionConstraint.None,
)

@Serializable
data class JournalUpdateResponse(
    val id: String,
    val serverVersion: Long,
    val updatedAt: Long,
)

@Serializable
data class JournalChangesResponse(
    val changes: List<JournalChange>,
    val deletions: List<JournalDeletion>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)

@Serializable
data class JournalChange(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val lastUpdated: Long,
    val serverVersion: Long,
    val isDeleted: Boolean = false,
)

@Serializable
data class JournalDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
    val mergedIntoJournalId: String? = null,
)

@Serializable
data class AssociationUploadRequest(
    val associations: List<Association>,
)

@Serializable
data class Association(
    val journalId: String,
    val contentId: String,
    val createdAt: Long,
    val syncVersion: Long = 0,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
) {
    fun key(): Pair<String, String> = journalId to contentId
}

@Serializable
data class AssociationUploadResponse(
    val uploadedCount: Int,
    val uploadedAt: Long,
    /** With `?ifAbsent=true`: links that already existed and were left alone. */
    val existing: List<AssociationDeleteItem> = emptyList(),
    /** With `?ifAbsent=true`: links that were deleted and stay deleted. */
    val deleted: List<AssociationDeletion> = emptyList(),
)

@Serializable
data class AssociationChangesResponse(
    val changes: List<AssociationChange>,
    val deletions: List<AssociationDeletion>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)

@Serializable
data class AssociationChange(
    val journalId: String,
    val contentId: String,
    val createdAt: Long,
    val serverVersion: Long,
    val isDeleted: Boolean = false,
)

@Serializable
data class AssociationDeletion(
    val journalId: String,
    val contentId: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

@Serializable
data class AssociationDeleteRequest(
    val associations: List<AssociationDeleteItem>,
)

@Serializable
data class AssociationDeleteItem(
    val journalId: String,
    val contentId: String,
) {
    fun key(): Pair<String, String> = journalId to contentId
}

@Serializable
data class MediaUploadRequest(
    val contentId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
)

@Serializable
data class MediaUploadResponse(
    val contentId: String,
    val mediaId: String,
    val downloadUrl: String,
    val uploadedAt: Long,
    val uploadIntentId: String? = null,
)

/** A missing receipt is an explicit successful lookup, never a network error. */
@Serializable
data class MediaUploadReceiptResponse(
    val receipt: MediaUploadResponse?,
)

@Serializable
data class MediaMetadataResponse(
    val contentId: String,
    val mediaId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val uploadedAt: Long,
)

@Serializable
data class MediaDownloadResponse(
    val contentId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray,
    val downloadUrl: String,
)

@Serializable
data class BackupUploadRequest(
    val deviceId: String,
    val manifest: String,
    val data: ByteArray,
)

@Serializable
data class BackupUploadResponse(
    val id: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

/**
 * Starts a backup sent in parts, for when one request can't carry the whole archive: a proxy in
 * front of the server may refuse large request bodies.
 */
@Serializable
data class BackupPartUploadStartRequest(
    val sizeBytes: Long,
)

/** Where to send a backup's parts: each is [partSizeBytes] long except the last. */
@Serializable
data class BackupPartUploadStartResponse(
    val uploadId: String,
    val partSizeBytes: Long,
    val partCount: Int,
)

/** Finishes a backup sent in parts, once every part has been accepted. */
@Serializable
data class BackupPartUploadCompleteRequest(
    val deviceId: String,
    val manifest: String,
    val sizeBytes: Long,
)

@Serializable
data class BackupInfoResponse(
    val id: String,
    val deviceId: String,
    val manifest: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val downloadUrl: String,
)

@Serializable
data class BackupListResponse(
    val backups: List<BackupInfoResponse>,
)

// --- Draft sync models (cross-device handoff) ---

@Serializable
data class DraftUploadRequest(
    val id: String,
    val content: String,
    val blockTypes: List<String> = emptyList(),
    val journalIds: List<String> = emptyList(),
    val createdAt: Long,
    val lastUpdated: Long,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    val encryptedBlocksVersion: Int? = null,
    val encryptedBlocks: String? = null,
)

@Serializable
data class DraftUploadResponse(
    val id: String,
    val serverVersion: Long,
    val uploadedAt: Long,
)

@Serializable
data class DraftChangesResponse(
    val drafts: List<DraftChange>,
    val cursor: VersionConstraint? = null,
    val deletions: List<DraftDeletion> = emptyList(),
    val lastTimestamp: Long = 0L,
    val hasMore: Boolean = false,
)

@Serializable
data class DraftDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long,
)

@Serializable
data class DraftChange(
    val id: String,
    val content: String,
    val blockTypes: List<String>,
    val journalIds: List<String>,
    val createdAt: Long,
    val lastUpdated: Long,
    val deviceId: DeviceId,
    val serverVersion: Long,
    val encryptedBlocksVersion: Int? = null,
    val encryptedBlocks: String? = null,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false,
)

// --- Marked moment sync models ---

/**
 * A marked moment: a point in time someone flagged, usually with one tap on the watch, to fill in later.
 * Everything about it except the times is in [payload], encrypted on the device (`LDSE2:`); [schema]
 * versions that payload. All times are milliseconds since epoch (UTC).
 */
@Serializable
data class MomentUploadRequest(
    val id: String,
    val capturedAt: Long,
    val lastUpdated: Long,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    val payload: String,
    val schema: Int = 1,
)

@Serializable
data class MomentUploadResponse(
    val id: String,
    val serverVersion: Long,
    val uploadedAt: Long,
)

@Serializable
data class MomentUpdateRequest(
    val payload: String,
    val schema: Int = 1,
    val capturedAt: Long? = null,
    val lastUpdated: Long,
    val deviceId: DeviceId = DeviceId.UNKNOWN,
    val versionConstraint: VersionConstraint = VersionConstraint.None,
)

@Serializable
data class MomentUpdateResponse(
    val id: String,
    val serverVersion: Long,
    val updatedAt: Long,
)

@Serializable
data class MomentChange(
    val id: String,
    val capturedAt: Long,
    val lastUpdated: Long,
    val deviceId: DeviceId,
    val payload: String,
    val schema: Int,
    val serverVersion: Long,
)

@Serializable
data class MomentDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long,
)

@Serializable
data class MomentChangesResponse(
    val changes: List<MomentChange>,
    val deletions: List<MomentDeletion>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)
