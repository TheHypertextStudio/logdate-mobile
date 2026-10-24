package app.logdate.client.e2e

import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.UnavailableMediaReferences

/** Compares source identities even when the deliberately omitted original cannot resolve. */
internal suspend fun partialNativeMediaSettled(
    references: List<String>,
    missingReference: String,
    resolver: MediaFileResolver,
    exists: suspend (String) -> Boolean,
): Boolean {
    fun sourceIdentity(reference: String): String {
        val source = UnavailableMediaReferences.nativeSource(reference) ?: reference
        return when (val parsed = MediaReference.parse(source)) {
            is MediaReference.Owned -> parsed.ref.toString()
            is MediaReference.LocalFile -> resolver.refFor(parsed.path)?.toString() ?: source
            is MediaReference.External -> source
        }
    }

    val missingIdentity = sourceIdentity(missingReference)
    return references.any { sourceIdentity(it) == missingIdentity } &&
        references.all { reference ->
            if (sourceIdentity(reference) == missingIdentity) {
                UnavailableMediaReferences.isUnavailable(reference)
            } else {
                !UnavailableMediaReferences.isUnavailable(reference) && exists(reference)
            }
        }
}
