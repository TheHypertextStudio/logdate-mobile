package app.logdate.client.data.restore

import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.EntryDraftRecoveryRepository
import app.logdate.client.repository.journals.PendingMediaType
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Admission of one verified pending camera draft into an occupied installation. */
internal class RetainedPendingCameraDraftAcceptance(
    private val drafts: EntryDraftRecoveryRepository,
) {
    suspend fun accept(
        generation: String,
        source: EntryDraft,
        sourcePath: String,
        recoveredReference: String,
    ): Uuid? = accept(generation, source, mapOf(sourcePath to recoveredReference), { true }) { commit -> commit() }

    suspend fun accept(
        generation: String,
        source: EntryDraft,
        recoveredReferences: Map<String, String>,
        canCommit: () -> Boolean,
        scopeFence: suspend (commit: suspend () -> Boolean) -> Boolean,
    ): Uuid? {
        val cameraRecords =
            source.pendingMedia.filter {
                it.mediaType == PendingMediaType.CAMERA_PHOTO || it.mediaType == PendingMediaType.CAMERA_VIDEO
            }
        if (recoveredReferences.keys.any { sourcePath ->
                cameraRecords.none { it.filePath == sourcePath || it.fallbackFilePath == sourcePath }
            }
        ) {
            return null
        }

        val expectedImported = sourceWithCameraReferences(source, recoveredReferences)
        val importedId = stableRecoveredDraftId(generation, source.id, recoveredReferences.keys.sorted())
        val imported =
            expectedImported.copy(
                id = importedId,
            )
        if (!canCommit()) {
            return null
        }
        val transferMarker = importedId.toString()
        if (drafts.isNativeTransferDraftAdmitted(importedId, transferMarker)) return importedId
        val existing = drafts.getDraftSnapshotsForRecovery().firstOrNull { it.id == importedId }
        if (existing != null) return importedId.takeIf { existing == imported }
        val inserted = drafts.insertNativeTransferDraftIfAbsent(imported, transferMarker, canCommit, scopeFence)
        return if (inserted) importedId else null
    }
}

private fun sourceWithCameraReferences(
    source: EntryDraft,
    recoveredReferences: Map<String, String>,
): EntryDraft = source.withLateMedia(recoveredReferences)

private fun stableRecoveredDraftId(
    generation: String,
    sourceId: Uuid,
    sourcePaths: List<String>,
): Uuid {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$generation\u0000$sourceId\u0000${sourcePaths.joinToString("\u0000")}".encodeToByteArray())
            .take(16)
            .toByteArray()
    val hex = digest.joinToString("") { "%02x".format(it) }.toCharArray()
    hex[12] = '5'
    hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
    val value = String(hex)
    return Uuid.parse(
        "${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-${value.substring(16, 20)}-${value.substring(20)}",
    )
}
