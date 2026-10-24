package app.logdate.client.data.restore

import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.UnavailableMediaReferences
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Resolves only media bytes included in one verified retained source generation. */
internal class RetainedManagedMediaSource(
    private val files: Map<String, File>,
    private val additionalSources: List<Map<String, File>> = emptyList(),
) {
    data class Asset(
        val file: File,
        val fileName: String,
        val mimeType: String,
        val sourcePath: String = "",
    )

    fun resolve(reference: String): Asset? {
        val relative =
            when (val parsed = MediaReference.parse(UnavailableMediaReferences.nativeSource(reference) ?: reference)) {
                is MediaReference.Owned -> {
                    collectionPath(parsed.ref.collection, parsed.ref.path)
                }

                is MediaReference.LocalFile -> {
                    retainedPrivateMediaPath(parsed.path)
                        ?: parsed.alternatePath?.let(::retainedPrivateMediaPath)
                }

                is MediaReference.External -> {
                    null
                }
            } ?: return null
        val key = "files/$relative"
        val digest = retainedCanonicalMediaDigest(reference)
        val candidates =
            sequence {
                files[key]?.let { yield(it) }
                if (digest != null) additionalSources.forEach { it[key]?.let { file -> yield(file) } }
            }
        for (file in candidates) {
            val asset = resolveFile(file, key, relative, digest)
            if (asset != null) return asset
        }
        return null
    }

    private fun resolveFile(
        file: File,
        sourcePath: String,
        relative: String,
        digest: String?,
    ): Asset? {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val parent = runCatching { file.parentFile?.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != parent || canonical.name != file.name) return null
        if (digest != null && !matchesDigest(canonical, digest)) return null
        val originalName = relative.substringAfterLast('/')
        val mime = retainedMediaMimeType(originalName) ?: return null
        return Asset(canonical, originalName, mime, sourcePath)
    }

    private fun matchesDigest(
        file: File,
        expected: String,
    ): Boolean =
        runCatching {
            if (file.length() <= 0) return@runCatching false
            val hashing = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) return@runCatching false
                    hashing.update(buffer, 0, count)
                }
            }
            hashing.digest().joinToString("") { "%02x".format(it) } == expected
        }.getOrDefault(false)

    private fun collectionPath(
        collection: MediaCollection,
        path: String,
    ): String =
        when (collection) {
            MediaCollection.Library -> "media/$path"
            MediaCollection.Recordings -> "audio_notes/$path"
        }
}

/** Cross-generation recovery needs a content digest; legacy path equality conveys no authority. */
internal fun retainedCanonicalMediaDigest(reference: String): String? {
    val original = UnavailableMediaReferences.nativeSource(reference) ?: reference
    val path =
        when (val parsed = MediaReference.parse(original)) {
            is MediaReference.Owned -> {
                parsed.ref.path.takeIf { parsed.ref.collection == MediaCollection.Library }
            }

            is MediaReference.LocalFile -> {
                retainedPrivateMediaPath(parsed.path)?.takeIf { it.startsWith("media/") }?.removePrefix("media/")
            }

            is MediaReference.External -> {
                null
            }
        } ?: return null
    val matched = RETAINED_CANONICAL_OBJECT.matchEntire(path) ?: return null
    val digest = matched.groupValues[2].lowercase()
    return digest.takeIf { it.startsWith(matched.groupValues[1], ignoreCase = true) }
}

internal suspend fun verifiedCanonicalMediaReference(
    reference: String,
    verify: suspend (String) -> Boolean,
): String? {
    if (retainedCanonicalMediaDigest(reference) == null) return null
    val original = UnavailableMediaReferences.nativeSource(reference) ?: reference
    val canonical =
        when (val parsed = MediaReference.parse(original)) {
            is MediaReference.LocalFile -> {
                "logdate-media://library/" + checkNotNull(retainedPrivateMediaPath(parsed.path)).removePrefix("media/")
            }

            else -> {
                original
            }
        }
    return original.takeIf { verify(canonical) }
}

/** Match only LogDate's Android private collections, including the development application ID. */
private fun retainedPrivateMediaPath(path: String): String? {
    val relative = RETAINED_PRIVATE_MEDIA.matchEntire(path)?.groupValues?.get(1) ?: return null
    return relative.takeUnless { value ->
        value.split('/').any { it.isEmpty() || it == "." || it == ".." || '\\' in it || it.any { char -> char.code < 32 } }
    }
}

private val RETAINED_PRIVATE_MEDIA =
    Regex(
        "^/data/(?:data|user/\\d+|user_de/\\d+)/" +
            "(?:studio\\.hypertext\\.logdate(?:\\.debug)?|co\\.reasonabletech\\.logdate)/files/((?:media|audio_notes)/.+)$",
    )

private val RETAINED_CANONICAL_OBJECT =
    Regex(
        "objects/sha256/([a-f0-9]{2})/([a-f0-9]{64})(?:\\.retained-[1-9][0-9]*)?\\.[^/.]+",
        RegexOption.IGNORE_CASE,
    )

internal fun retainedMediaMimeType(name: String): String? =
    when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"

        "png" -> "image/png"

        "heic" -> "image/heic"

        "heics" -> "image/heic-sequence"

        "heif" -> "image/heif"

        "heifs" -> "image/heif-sequence"

        "avif" -> "image/avif"

        "bmp" -> "image/bmp"

        "webp" -> "image/webp"

        "gif" -> "image/gif"

        "mp4" -> "video/mp4"

        "mpg" -> "video/mpeg"

        "ogv" -> "video/ogg"

        "mov" -> "video/quicktime"

        "webm" -> "video/webm"

        // The canonical file extension is shared by audio and video. Either media type
        // preserves the content-addressed path; the original bytes remain unchanged.
        "3gp" -> "video/3gpp"

        "3g2" -> "video/3gpp2"

        "mkv" -> "video/x-matroska"

        "avi" -> "video/x-msvideo"

        "wmv" -> "video/x-ms-wmv"

        "m4a" -> "audio/mp4"

        "mp3" -> "audio/mpeg"

        "wav" -> "audio/wav"

        "ogg" -> "audio/ogg"

        "opus" -> "audio/opus"

        "amr" -> "audio/amr"

        "mid" -> "audio/midi"

        "weba" -> "audio/webm"

        "flac" -> "audio/flac"

        "aac" -> "audio/aac"

        else -> null
    }
