package app.logdate.client.media.storage

import kotlin.io.encoding.Base64

/** A retained attachment whose original bytes have not been recovered. */
object UnavailableMediaReferences {
    private const val PREFIX = "logdate-unavailable://archive/"

    private const val NATIVE_PREFIX = "logdate-unavailable://native/"

    fun isUnavailable(reference: String): Boolean = reference.startsWith(PREFIX) || reference.startsWith(NATIVE_PREFIX)

    fun nativeRestore(reference: String): String =
        if (isUnavailable(reference)) reference else NATIVE_PREFIX + Base64.UrlSafe.encode(reference.encodeToByteArray())

    fun nativeSource(reference: String): String? =
        reference.takeIf { it.startsWith(NATIVE_PREFIX) }?.removePrefix(NATIVE_PREFIX)?.let {
            runCatching { Base64.UrlSafe.decode(it).decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        }

    /** External identities belong to the source device, even if they resolve on this one. */
    fun requiresNativeQuarantine(reference: String): Boolean =
        reference.startsWith("content:", ignoreCase = true) ||
            (
                reference.isNotBlank() &&
                    reference.all(Char::isDigit)
            ) ||
            (MediaReference.parse(reference) as? MediaReference.LocalFile)?.path?.let {
                it.startsWith("/storage/") || it.startsWith("/sdcard/") || it.startsWith("/mnt/media_rw/")
            } == true

    fun archive(
        entityId: String,
        blockId: String? = null,
        role: String = "original",
        generation: String? = null,
    ): String = PREFIX + listOfNotNull(generation, entityId, blockId, role).joinToString("/")
}
