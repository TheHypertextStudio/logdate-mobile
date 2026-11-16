package app.logdate.client.domain.restore

import app.logdate.client.domain.LenientJson
import app.logdate.client.domain.export.ExportMediaManifest
import app.logdate.client.domain.export.archive.ArchiveMediaFile

/** Finds originals by entry/block identity when the user supplies a more complete archive. */
class ArchiveRecoveryMediaIndex(
    archive: RestoreArchiveBundle,
) {
    private val paths = mutableMapOf<Triple<String, String?, RecoveryMediaRole>, Pair<String?, String>>()
    private val legacy = mutableMapOf<Pair<String, String>, String>()
    private val checksums =
        (archive as? RestoreArchiveBundle.V2)
            ?.bundle
            ?.mediaInventoryJson
            ?.let {
                LenientJson.decodeFromString<ArchiveMediaFile>(it).files.associate { item ->
                    item.path.value to
                        item.sha256
                }
            }.orEmpty()

    init {
        val bundle =
            when (archive) {
                is RestoreArchiveBundle.V1 -> archive.bundle
                is RestoreArchiveBundle.V2 -> archive.bundle.adaptForRestore("", "").legacy
            }
        val json = LenientJson
        val manifest = bundle.mediaManifestJson?.let { json.decodeFromString<ExportMediaManifest>(it) }
        val media = manifest?.files?.associate { it.sourceUri to it.exportPath }

        fun path(reference: String?): String? = reference?.let { if (media == null) it else media[it] }
        json.decodeFromString<NotesPayload>(bundle.notesJson).notes.forEach { note ->
            path(note.mediaPath)?.let { paths[Triple(note.id, null, RecoveryMediaRole.ORIGINAL)] = note.type.lowercase() to it }
        }
        json.decodeFromString<DraftsPayload>(bundle.draftsJson).drafts.forEach { draft ->
            if (draft.blocks.isEmpty()) {
                draft.mediaReferences.forEach { reference ->
                    path(reference)?.let { legacy[draft.id to reference] = it }
                }
            }
            draft.blocks.forEach { block ->
                RecoveryMediaRole.entries.forEach { role ->
                    path(block.archiveMediaReference(role))?.let {
                        paths[Triple(draft.id, block.id.toString(), role)] =
                            block.archiveMediaKind() to it
                    }
                }
            }
        }
    }

    fun path(asset: UnavailableMediaAsset): String? {
        val indexed = paths[Triple(asset.entityId, asset.blockId, asset.role)]
        if (indexed != null && (asset.kind == null || indexed.first == asset.kind)) return indexed.second
        if (asset.role == RecoveryMediaRole.ORIGINAL && asset.blockId != null && (asset.kind == null || asset.kind == "camera")) {
            return asset.sourceReference?.let { legacy[asset.entityId to it] }
        }
        return null
    }

    fun expectedChecksum(asset: UnavailableMediaAsset): String? = path(asset)?.let(checksums::get)
}
