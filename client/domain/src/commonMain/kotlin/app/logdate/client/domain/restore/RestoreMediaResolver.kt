package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportMediaFile

internal class RestoreMediaResolver(
    private val manifest: Map<String, ExportMediaFile>?,
    private val importer: MediaImporter?,
    private val includeMedia: Boolean,
) {
    suspend fun resolve(
        source: String?,
        fallback: String? = null,
        allowEmptyFallback: Boolean = false,
    ): MediaResolution {
        if ((allowEmptyFallback || !source.isNullOrBlank()) &&
            !fallback.isNullOrBlank() &&
            (source.isNullOrBlank() || !includeMedia) &&
            importer?.isAvailable(fallback) == true
        ) {
            return MediaResolution(fallback, false, false)
        }
        if (source.isNullOrBlank()) return MediaResolution(null, false, false)
        val entry = manifest?.get(source)
        val path = entry?.exportPath ?: source
        if (includeMedia && (manifest == null || entry != null)) {
            importer?.importMedia(path)?.let { return MediaResolution(it, true, false) }
        }
        if (importer?.isAvailable(source) == true) return MediaResolution(source, false, false)
        if (fallback != null && importer?.isAvailable(fallback) == true) return MediaResolution(fallback, false, false)
        val reason =
            when {
                !includeMedia -> UnavailableMediaReason.NOT_SELECTED

                source.startsWith(
                    "logdate-unavailable://archive/",
                ) ||
                    (
                        manifest != null &&
                            entry == null
                    ) -> UnavailableMediaReason.NOT_IN_ARCHIVE

                importer?.containsMedia(path) == false -> UnavailableMediaReason.NOT_IN_ARCHIVE

                importer != null -> UnavailableMediaReason.IMPORT_FAILED

                else -> null
            }
        return MediaResolution(source, false, reason == null, path, reason)
    }
}
