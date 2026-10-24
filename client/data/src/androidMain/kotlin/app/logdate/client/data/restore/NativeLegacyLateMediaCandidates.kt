package app.logdate.client.data.restore

import app.logdate.client.device.restore.NativeIncomingJournal
import app.logdate.client.device.restore.NativeJournalMediaInventory
import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.UnavailableMediaReferences
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** A sealed callback payload bound to source bytes and an unchanged current legacy reference. */
internal data class NativeLegacyLateMediaCandidate(
    val logicalPath: String,
    val file: File,
    val sha256: String,
    val references: Set<String>,
)

/**
 * The caller must obtain [inventory] from the completed transfer authority and establish the
 * current owner/backend scope. Only the source-declared revision for a referenced path is selected;
 * unrelated and incomplete revisions remain in custody. Import, CAS and acknowledgement must stay
 * under the native delivery gate. This function never selects orphaned bytes.
 */
internal fun selectNativeLegacyLateMediaCandidates(
    payloads: Iterable<NativeIncomingJournal.Payload>,
    currentReferences: Set<String>,
    packageName: String,
    inventory: NativeJournalMediaInventory,
): List<NativeLegacyLateMediaCandidate> {
    val payloadsByPath = payloads.groupBy(NativeIncomingJournal.Payload::path)
    val selected = linkedMapOf<String, NativeLegacyLateMediaCandidate>()
    currentReferences.forEach { reference ->
        nativeLegacyLateMediaPaths(reference, packageName, allowAuthenticatedStaging = true)
            .firstNotNullOfOrNull { path ->
                val expected = inventory.entry(path) ?: return@firstNotNullOfOrNull null
                val verified =
                    payloadsByPath[path].orEmpty().firstOrNull { payload ->
                        payload.complete &&
                            payload.expectedSize == expected.size &&
                            verifiedLegacyPayloadSha256(payload) == expected.sha256
                    } ?: return@firstNotNullOfOrNull null
                path to NativeLegacyLateMediaCandidate(path, verified.file, expected.sha256, setOf(reference))
            }?.let { (path, candidate) ->
                val prior = selected[path]
                selected[path] = candidate.copy(references = prior?.references.orEmpty() + reference)
            }
    }
    return selected.values.toList()
}

/** Maps only LogDate's private legacy roots; no provider or arbitrary file URI is an authority. */
internal fun nativeLegacyLateMediaPath(
    reference: String,
    packageName: String,
): String? = nativeLegacyLateMediaPaths(reference, packageName).firstOrNull()

private fun nativeLegacyLateMediaPaths(
    reference: String,
    packageName: String,
    allowAuthenticatedStaging: Boolean = false,
): List<String> {
    val original = UnavailableMediaReferences.nativeSource(reference) ?: reference
    return when (val parsed = MediaReference.parse(original)) {
        is MediaReference.Owned -> {
            val root = if (parsed.ref.collection == MediaCollection.Recordings) "audio_notes" else "media"
            val relative = "$root/${parsed.ref.path}"
            if (safeLegacyRelative(relative, allowAuthenticatedStaging)) listOf("files/$relative") else emptyList()
        }

        is MediaReference.LocalFile -> {
            listOfNotNull(parsed.path, parsed.alternatePath)
                .mapNotNull { path ->
                    val match = APP_PRIVATE_MEDIA.matchEntire(path) ?: return@mapNotNull null
                    val owner = match.groupValues[1]
                    if (owner !in setOf(packageName, "studio.hypertext.logdate", "co.reasonabletech.logdate")) {
                        return@mapNotNull null
                    }
                    val relative = match.groupValues[2]
                    if (!safeLegacyRelative(relative, allowAuthenticatedStaging)) {
                        return@mapNotNull null
                    }
                    "files/$relative"
                }.distinct()
        }

        is MediaReference.External -> {
            emptyList()
        }
    }
}

private fun safeLegacyRelative(
    relative: String,
    allowAuthenticatedStaging: Boolean,
): Boolean {
    val parts = relative.split('/')
    val eligibleRoot =
        parts.first() in setOf("user_media", "audio_notes") ||
            (parts.first() == "media" && parts.getOrNull(1) == "staging" && allowAuthenticatedStaging)
    return parts.size >= 2 &&
        eligibleRoot &&
        (allowAuthenticatedStaging || !(parts.first() == "audio_notes" && parts.getOrNull(1) == "staging")) &&
        relative.length <= 1024 &&
        parts.none { part ->
            part.isEmpty() || part == "." || part == ".." || '\\' in part || part.any { it.code < 32 }
        }
}

private fun verifiedLegacyPayloadSha256(payload: NativeIncomingJournal.Payload): String? {
    val file = payload.file
    if (payload.expectedSize < 0 ||
        !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(file.toPath()) ||
        file.length() != payload.expectedSize
    ) {
        return null
    }
    return runCatching {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count > 0)
                hash.update(buffer, 0, count)
            }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

private val APP_PRIVATE_MEDIA =
    Regex("^/data/(?:data|user/\\d+|user_de/\\d+)/([^/]+)/files/((?:media|user_media|audio_notes)/.+)$")
