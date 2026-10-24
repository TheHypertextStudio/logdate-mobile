package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportDraft
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlin.uuid.Uuid

internal class RestoreDraftDecoder(
    private val resolveMediaReference: suspend (String?, String?, Boolean) -> MediaResolution,
    private val recoveryId: String,
) {
    private val unavailable = mutableListOf<UnavailableMediaAsset>()
    private var imported = 0

    suspend fun decode(
        draft: ExportDraft,
        existing: EditorDraft? = null,
    ): DecodedArchiveDraft {
        unavailable.clear()
        imported = 0
        val blocks =
            if (draft.blocks.isNotEmpty()) {
                draft.blocks.map { block ->
                    restoreBlock(
                        draft.id,
                        block,
                        existing?.blocks?.firstOrNull {
                            it.id == block.id &&
                                it::class == block::class
                        },
                    )
                }
            } else {
                buildList {
                    if (draft.content.isNotBlank()) {
                        add(
                            SerializableTextBlock(
                                Uuid.random(),
                                draft.createdAt,
                                locationLat = draft.location?.latitude,
                                locationLng = draft.location?.longitude,
                                content = draft.content,
                            ),
                        )
                    }
                    draft.mediaReferences.forEach { reference ->
                        val id = Uuid.random()
                        add(SerializableCameraBlock(id, draft.updatedAt, uri = resolve(draft.id, id, reference, kind = "camera")))
                    }
                }
            }
        val restored =
            EditorDraft(
                id = Uuid.parse(draft.id),
                blocks = blocks,
                selectedJournalIds = draft.parseSelectedJournalIds(),
                createdAt = draft.createdAt,
                lastModifiedAt = draft.updatedAt,
            )
        return DecodedArchiveDraft(restored, imported, unavailable.toList())
    }

    private suspend fun restoreBlock(
        draftId: String,
        block: SerializableEntryBlock,
        existing: SerializableEntryBlock?,
    ): SerializableEntryBlock =
        when (block) {
            is app.logdate.shared.model.SerializableHandwritingBlock, is SerializableTextBlock -> {
                block
            }

            is SerializableImageBlock -> {
                block.copy(
                    uri = resolve(draftId, block.id, block.uri, fallback = (existing as? SerializableImageBlock)?.uri, kind = "image"),
                )
            }

            is SerializableAudioBlock -> {
                block.copy(
                    uri = resolve(draftId, block.id, block.uri, fallback = (existing as? SerializableAudioBlock)?.uri, kind = "audio"),
                )
            }

            is SerializableCameraBlock -> {
                block.copy(
                    uri = resolve(draftId, block.id, block.uri, fallback = (existing as? SerializableCameraBlock)?.uri, kind = "camera"),
                )
            }

            is SerializableVideoBlock -> {
                val previous = existing as? SerializableVideoBlock
                val uri = resolve(draftId, block.id, block.uri, fallback = previous?.uri, kind = "video")
                block.copy(
                    uri = uri,
                    thumbnailUri =
                        resolve(
                            draftId,
                            block.id,
                            block.thumbnailUri,
                            RecoveryMediaRole.THUMBNAIL,
                            fallback = previous?.thumbnailUri?.takeIf { uri != null && uri == previous.uri },
                            allowEmptyFallback = uri != null && uri == previous?.uri,
                            kind = "video",
                        ),
                )
            }
        }

    private suspend fun resolve(
        draftId: String,
        blockId: Uuid,
        reference: String?,
        role: RecoveryMediaRole = RecoveryMediaRole.ORIGINAL,
        fallback: String? = null,
        allowEmptyFallback: Boolean = false,
        kind: String? = null,
    ): String? {
        val resolution = resolveMediaReference(reference, fallback, allowEmptyFallback)
        if (resolution.imported) imported++
        resolution.unavailableAsset(draftId, blockId.toString(), role, recoveryId, kind)?.let(unavailable::add)
        return resolution.retainedReference(draftId, blockId.toString(), role, recoveryId)
    }

    private fun ExportDraft.parseSelectedJournalIds(): List<Uuid> =
        if (journalIds.isNotEmpty()) {
            journalIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
        } else {
            journalId?.let { runCatching { listOf(Uuid.parse(it)) }.getOrNull() }.orEmpty()
        }
}

internal data class DecodedArchiveDraft(
    val draft: EditorDraft,
    val mediaImported: Int,
    val unavailableMedia: List<UnavailableMediaAsset>,
)
