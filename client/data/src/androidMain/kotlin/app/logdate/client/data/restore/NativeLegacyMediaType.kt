package app.logdate.client.data.restore

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

/** A restored original's bounded header takes precedence over a missing or misleading legacy suffix. */
internal fun nativeLegacyMediaMimeType(
    file: File,
    originalName: String,
): String? {
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
    val header =
        runCatching {
            val bytes = ByteArray(4096)
            val buffer = ByteBuffer.wrap(bytes)
            Files
                .newByteChannel(
                    file.toPath(),
                    setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ).use { channel ->
                    while (buffer.hasRemaining()) {
                        val read = channel.read(buffer)
                        if (read <= 0) break
                    }
                }
            bytes.copyOf(buffer.position())
        }.getOrNull() ?: return null
    val nameHint =
        when (originalName.substringAfterLast('.', "").lowercase()) {
            "ogv" -> "video/ogg"
            "weba" -> "audio/webm"
            "m4a" -> "audio/mp4"
            else -> null
        }
    return nativeLegacyMediaHeaderMimeType(header, nameHint)
}

private fun nativeLegacyMediaHeaderMimeType(
    bytes: ByteArray,
    nameHint: String?,
): String? {
    fun matches(vararg signature: Int): Boolean =
        bytes.size >= signature.size &&
            signature.indices.all { index -> (bytes[index].toInt() and 0xff) == signature[index] }

    fun ascii(
        offset: Int,
        value: String,
    ): Boolean =
        bytes.size >= offset + value.length &&
            value.indices.all { index -> bytes[offset + index].toInt() == value[index].code }

    if (matches(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return "image/png"
    if (matches(0xff, 0xd8, 0xff)) return "image/jpeg"
    if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return "image/gif"
    if (ascii(0, "BM")) return "image/bmp"
    if (ascii(0, "RIFF") && ascii(8, "WEBP")) return "image/webp"
    if (ascii(0, "RIFF") && ascii(8, "WAVE")) return "audio/wav"
    if (ascii(0, "RIFF") && ascii(8, "AVI ")) return "video/x-msvideo"
    if (ascii(0, "fLaC")) return "audio/flac"
    if (ascii(0, "MThd")) return "audio/midi"
    if (ascii(0, "#!AMR\n")) return "audio/amr"
    if (ascii(0, "OggS")) {
        val header = bytes.toString(Charsets.ISO_8859_1)
        return when {
            "OpusHead" in header -> "audio/opus"
            "theora" in header -> "video/ogg"
            else -> if (nameHint == "video/ogg") "video/ogg" else "audio/ogg"
        }
    }
    if (ascii(0, "ID3")) return "audio/mpeg"
    if (matches(0x1a, 0x45, 0xdf, 0xa3)) {
        val header = bytes.toString(Charsets.ISO_8859_1)
        if ("webm" in header) return if (nameHint == "audio/webm") "audio/webm" else "video/webm"
        if ("matroska" in header) return "video/x-matroska"
    }
    if (ascii(4, "ftyp") && bytes.size >= 12) {
        val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        return when {
            brand in setOf("M4A ", "M4B ") ||
                bytes.copyOfRange(12, minOf(bytes.size, 64)).toString(Charsets.ISO_8859_1).contains("M4A ") -> "audio/mp4"

            brand in setOf("avif", "avis") -> "image/avif"

            brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> "image/heif"

            brand == "qt  " -> "video/quicktime"

            brand.startsWith("3gp") -> "video/3gpp"

            brand.startsWith("3g2") -> "video/3gpp2"

            else -> if (nameHint == "audio/mp4") "audio/mp4" else "video/mp4"
        }
    }
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff) {
        val second = bytes[1].toInt() and 0xff
        if (second == 0xf1 || second == 0xf9) return "audio/aac"
        if ((second and 0xe0) == 0xe0 && (second and 0x06) != 0) return "audio/mpeg"
    }
    return null
}
