package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.archive.ArchiveDraftFile
import app.logdate.client.domain.export.archive.ArchiveJson
import app.logdate.client.domain.export.archive.ArchiveManifest
import app.logdate.client.domain.export.archive.ArchiveMediaFile
import app.logdate.client.domain.export.archive.ArchiveMediaRef
import app.logdate.client.domain.export.archive.ArchiveMediaStatus
import app.logdate.client.domain.export.archive.ArchiveNoteFile
import app.logdate.client.domain.export.archive.ArchiveRole
import app.logdate.client.domain.export.archive.ChecksumFile
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

/** Checks original archive document bytes before publishing any destination records. */
internal object ArchiveIntegrityAdmission {
    fun admit(
        manifestBytes: ByteArray,
        manifest: ArchiveManifest,
        contents: Map<ArchiveRole, ByteArray>,
    ): Map<String, ArchiveMediaIntegrity> {
        require(manifest.format == ArchiveManifest.FORMAT) { "Unsupported archive format" }
        if (manifest.schemaVersion.major != ExportSchemaVersion.V2_0.major) throw UnsupportedExportVersionException(manifest.schemaVersion)
        requireUniqueContents(manifest)
        val checksumContent =
            requireNotNull(manifest.contents.singleOrNull { it.role == ArchiveRole.CHECKSUMS }) {
                "V2 archive has no checksum manifest"
            }
        val checksums =
            requireNotNull(ChecksumFile.parse(requireNotNull(contents[ArchiveRole.CHECKSUMS]).archiveText())) {
                "Invalid archive checksum manifest"
            }
        require(checksumContent.path.value !in checksums) { "Checksum manifest cannot cover itself" }
        require(checksums["manifest.json"] == manifestBytes.sha256()) { "Archive manifest failed its integrity check" }
        for (content in manifest.contents) {
            if (content.role == ArchiveRole.CHECKSUMS) continue
            require(checksums[content.path.value] == requireNotNull(contents[content.role]).sha256()) {
                "Archive metadata failed its integrity check"
            }
        }
        for ((role, bytes) in contents) {
            when (role) {
                ArchiveRole.README, ArchiveRole.CHECKSUMS, ArchiveRole.READABLE_INDEX -> {
                    Unit
                }

                ArchiveRole.LOCATION_HISTORY -> {
                    bytes
                        .archiveText()
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .forEach { ArchiveJsonKeyValidation.requireUnique(it) }
                }

                else -> {
                    ArchiveJsonKeyValidation.requireUnique(bytes.archiveText())
                }
            }
        }
        val inventory =
            contents[ArchiveRole.MEDIA_INVENTORY]
                ?.let {
                    ArchiveJson.document.decodeFromString(ArchiveMediaFile.serializer(), it.archiveText()).files
                }.orEmpty()
        require(inventory.map { it.path }.distinct().size == inventory.size) { "Repeated archive inventory path" }
        val integrity =
            inventory.associate { item ->
                require(checksums[item.path.value] == item.sha256) { "Archive inventory and checksum manifest disagree" }
                item.path.value to ArchiveMediaIntegrity(item.bytes, item.sha256)
            }
        requireIncludedMediaCoverage(contents, integrity.keys)
        return integrity
    }

    private fun requireUniqueContents(manifest: ArchiveManifest) {
        require(
            manifest.contents
                .map { it.role }
                .distinct()
                .size == manifest.contents.size,
        ) { "Repeated archive content role" }
        require(
            manifest.contents
                .map { it.path }
                .distinct()
                .size == manifest.contents.size,
        ) { "Repeated archive content path" }
    }

    private fun requireIncludedMediaCoverage(
        contents: Map<ArchiveRole, ByteArray>,
        inventory: Set<String>,
    ) {
        fun requireCovered(media: ArchiveMediaRef?) {
            if (media?.status != ArchiveMediaStatus.INCLUDED) return
            val path = requireNotNull(media.path) { "Included archive media has no path" }
            require(path.value in inventory) { "Included archive media is not covered by its inventory" }
        }
        contents[ArchiveRole.NOTES]?.let {
            ArchiveJson.document.decodeFromString(ArchiveNoteFile.serializer(), it.archiveText()).notes.forEach { note ->
                requireCovered(note.media)
            }
        }
        contents[ArchiveRole.DRAFTS]?.let {
            ArchiveJson.document.decodeFromString(ArchiveDraftFile.serializer(), it.archiveText()).drafts.forEach { draft ->
                draft.blocks.forEach { block ->
                    requireCovered(block.media)
                    requireCovered(block.thumbnail)
                }
            }
        }
    }

    private fun ByteArray.sha256(): String {
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        var offset = 0
        while (offset < size) {
            val count = minOf(8_192, size - offset)
            buffer.write(this, offset, count)
            hashing.write(buffer, count.toLong())
            offset += count
        }
        return hashing.hash.hex()
    }
}

/** Decoding failure is invalid input, not a transient storage error that a worker should retry. */
internal fun ByteArray.archiveText(): String =
    try {
        decodeToString(throwOnInvalidSequence = true)
    } catch (failure: Exception) {
        throw IllegalArgumentException("Archive text is not valid UTF-8", failure)
    }
