package app.logdate.client.media.storage

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Turns stored media strings into files on this device, and files back into the strings to store.
 *
 * Every reader of stored media calls [filePath] before opening anything, and every repository
 * that persists a media reference calls [storedReference] first. See
 * `docs/reference/media-references.md`.
 */
class MediaFileResolver(
    private val directories: MediaDirectories,
    private val fileExists: (String) -> Boolean = { path -> SystemFileSystem.exists(Path(path)) },
) : StoredMediaReferences {
    /**
     * The absolute path [reference] names on this device, or `null` when it names no local file
     * (a Photos asset, a content provider, a remote URL, a malformed reference).
     *
     * A file path from an earlier install resolves to the same file in this install when it is
     * there. Otherwise it resolves as written, so a missing file is still reported as missing.
     */
    fun filePath(reference: String): String? =
        when (val parsed = MediaReference.parse(reference)) {
            is MediaReference.Owned -> filePath(parsed.ref)
            is MediaReference.LocalFile -> existingLocation(parsed) ?: parsed.path
            is MediaReference.External -> null
        }

    /**
     * How many bytes the file [reference] names takes on this device, or `null` when it names no
     * local file or the file is not here.
     */
    fun fileSize(reference: String): Long? =
        filePath(reference)?.let { path -> SystemFileSystem.metadataOrNull(Path(path))?.takeIf { it.isRegularFile }?.size }

    /** The absolute path of [ref] in this install. */
    fun filePath(ref: LocalMediaRef): String = "${directories.directory(ref.collection)}/${ref.path}"

    /** The directory holding [collection] in this install. */
    fun directory(collection: MediaCollection): String = directories.directory(collection)

    /**
     * The string to store for [reference]: its `logdate-media://` reference when it names a file in
     * one of this install's collections, and [reference] unchanged otherwise.
     *
     * Two spellings of the same file always give the same result, so compare stored references,
     * never raw strings. A file path whose file is missing keeps its original spelling rather than
     * being pointed somewhere new.
     */
    override fun storedReference(reference: String): String =
        when (val parsed = MediaReference.parse(reference)) {
            is MediaReference.Owned -> parsed.ref.toString()
            is MediaReference.LocalFile -> existingLocation(parsed)?.let(::refFor)?.toString() ?: reference
            is MediaReference.External -> reference
        }

    /** The reference for the file at the absolute [path], or `null` when it is in no collection. */
    fun refFor(path: String): LocalMediaRef? {
        val canonical = directories.canonicalPath(path)
        val collection =
            MediaCollection.entries.firstOrNull { canonical.startsWith("${directories.directory(it)}/") }
                ?: return null
        val relativePath = canonical.substring(directories.directory(collection).length + 1)
        return runCatching { LocalMediaRef(collection, relativePath) }.getOrNull()
    }

    /** Where the file [file] names exists in this install, trying each spelling and earlier installs. */
    private fun existingLocation(file: MediaReference.LocalFile): String? {
        val spellings = listOfNotNull(file.path, file.alternatePath)
        spellings.firstOrNull(fileExists)?.let { return it }
        return spellings.firstNotNullOfOrNull { spelling ->
            directories.pathInCurrentInstall(spelling)?.takeIf(fileExists)
        }
    }
}
