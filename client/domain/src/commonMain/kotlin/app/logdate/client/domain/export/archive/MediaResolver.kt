package app.logdate.client.domain.export.archive

import app.logdate.client.sync.deviceBlockReason
import io.github.aakira.napier.Napier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import okio.Buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** A media file an entry refers to, and the time and zone to name it by. */
data class MediaRequest(
    val reference: String,
    val kind: MediaKind,
    val capturedAt: Instant,
    val zone: TimeZone,
)

sealed interface ResolvedMedia {
    data class Included(
        val path: ArchivePath,
        val type: MediaType,
    ) : ResolvedMedia

    data class Omitted(
        val reason: ArchiveOmissionReason,
    ) : ResolvedMedia
}

/** What every media reference became, and the files to copy, in the order they were first seen. */
class MediaResolution(
    private val byReference: Map<String, ResolvedMedia>,
    val files: List<IncludedFile>,
) {
    class IncludedFile(
        val reference: String,
        val path: ArchivePath,
        val type: MediaType,
    )

    operator fun get(reference: String): ResolvedMedia? = byReference[reference]

    /** True when at least one file the entries refer to could not be included. */
    val hasOmittedFiles: Boolean get() = byReference.values.any { it is ResolvedMedia.Omitted }

    /** This resolution with [references] omitted as unreadable, for files that could not be opened when they were copied. */
    fun withUnreadable(references: Set<String>): MediaResolution {
        if (references.isEmpty()) return this
        val unreadable = ResolvedMedia.Omitted(ArchiveOmissionReason.UNREADABLE)
        return MediaResolution(
            byReference.mapValues { (reference, resolved) -> if (reference in references) unreadable else resolved },
            files.filter { it.reference !in references },
        )
    }
}

/**
 * Decides, before anything is written, which media files the archive will contain and what each is
 * called.
 *
 * Resolving first means the manifest, the README and every entry can state exactly what is included
 * and what is omitted, so nothing in the archive points at a file that is not there.
 */
class MediaResolver(
    private val opener: MediaSourceOpener,
    private val namer: MediaFileNamer,
) {
    suspend fun resolve(requests: List<MediaRequest>): MediaResolution {
        val resolved = linkedMapOf<String, ResolvedMedia>()
        val files = mutableListOf<MediaResolution.IncludedFile>()
        for (request in requests) {
            currentCoroutineContext().ensureActive()
            if (request.reference in resolved) continue
            val outcome = resolveOne(request)
            resolved[request.reference] = outcome
            if (outcome is ResolvedMedia.Included) files += MediaResolution.IncludedFile(request.reference, outcome.path, outcome.type)
        }
        return MediaResolution(resolved, files)
    }

    private suspend fun resolveOne(request: MediaRequest): ResolvedMedia {
        val header = readHeader(request.reference) ?: return ResolvedMedia.Omitted(ArchiveOmissionReason.UNREADABLE)
        val type = MediaTypeSniffer.sniff(header, extensionOf(request.reference), request.kind)
        val path = namer.nameFor(kindOf(type) ?: request.kind, request.capturedAt, request.zone, type.extension)
        return ResolvedMedia.Included(path, type)
    }

    private suspend fun readHeader(reference: String): ByteArray? {
        val source = opener.openOrNull(reference) ?: return null

        return try {
            source.use {
                val header = Buffer()
                while (header.size < MediaTypeSniffer.HEADER_SIZE) {
                    if (it.read(header, MediaTypeSniffer.HEADER_SIZE - header.size) == -1L) break
                }
                header.readByteArray()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException || deviceBlockReason(failure) != null) throw failure
            Napier.w("Media file could not be read for export", failure)
            null
        }
    }

    /** The folder follows what the bytes are, so a camera capture that turned out to be a video is filed as one. */
    private fun kindOf(type: MediaType): MediaKind? =
        when {
            type.mimeType.startsWith("image/") -> MediaKind.PHOTO
            type.mimeType.startsWith("video/") -> MediaKind.VIDEO
            type.mimeType.startsWith("audio/") -> MediaKind.AUDIO
            else -> null
        }

    private fun extensionOf(reference: String): String? {
        val name = reference.substringAfterLast('/').substringBefore('?')
        return name.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() && it.length <= 5 }
    }
}
