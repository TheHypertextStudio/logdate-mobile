package app.logdate.client.domain.export.archive

import okio.ByteString.Companion.toByteString

/** Hash exported contents, excluding documents whose only change may be the export time. */
internal fun archiveSourceFingerprint(
    options: ArchiveExportOptions,
    entries: List<HashLedger.Entry>,
): String {
    val content =
        buildString {
            append("archive-content-v2\n")
            append(options)
            append('\n')
            entries
                .filterNot { it.path in setOf(ArchiveLayout.README, ArchiveLayout.MANIFEST, ArchiveLayout.CHECKSUMS) }
                .sortedBy { it.path.value }
                .forEach {
                    append(it.path.value)
                    append(':')
                    append(it.bytes)
                    append(':')
                    append(it.sha256)
                    append('\n')
                }
        }
    return content
        .encodeToByteArray()
        .toByteString()
        .sha256()
        .hex()
}
