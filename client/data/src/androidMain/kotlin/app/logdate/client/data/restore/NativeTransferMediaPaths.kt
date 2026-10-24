package app.logdate.client.data.restore

import androidx.datastore.preferences.core.Preferences
import app.logdate.client.data.journals.ArchiveDraftStorageState
import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.shared.model.EditorDraft
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

internal fun nativeTransferMediaPaths(
    references: Iterable<String>,
    packageName: String,
    filesDir: File,
): Set<String> =
    buildSet {
        references.forEach { reference ->
            val original = UnavailableMediaReferences.nativeSource(reference) ?: reference
            when (val parsed = MediaReference.parse(original)) {
                is MediaReference.Owned -> {
                    val root = if (parsed.ref.collection == MediaCollection.Library) "media" else "audio_notes"
                    val relative = "$root/${parsed.ref.path}"
                    if (safeNativeMediaRelative(relative)) add("files/$relative")
                }

                is MediaReference.LocalFile -> {
                    val paths =
                        listOfNotNull(parsed.path, parsed.alternatePath)
                            .mapNotNull { path ->
                                val match = APP_PRIVATE_MEDIA.matchEntire(path) ?: return@mapNotNull null
                                if (match.groupValues[1] !in setOf(packageName, "studio.hypertext.logdate", "co.reasonabletech.logdate")) {
                                    return@mapNotNull null
                                }
                                match.groupValues[2].takeIf(::safeNativeMediaRelative)
                            }
                    val selected = paths.firstOrNull { File(filesDir, it).exists() } ?: paths.firstOrNull()
                    selected?.let { add("files/$it") }
                }

                is MediaReference.External -> {
                    Unit
                }
            }
        }
    }

internal fun nativeTransferDraftMediaReferences(
    preferences: Preferences,
    entryDrafts: List<EntryDraft>,
): Set<String> =
    buildSet {
        val json = Json { ignoreUnknownKeys = true }
        preferences.asMap().forEach { (key, value) ->
            if (value !is String) return@forEach
            if (isRetainedDraftStorageKey(key.name)) {
                ArchiveDraftStorageState.decode(json, value).drafts.forEach { addAll(it.lateMediaReferences()) }
            } else if (key.name == "entry_drafts") {
                json.decodeFromString<List<EditorDraft>>(value).forEach { addAll(it.lateMediaReferences()) }
            } else if (key.name.startsWith("editor_drafts_native_unparsed_")) {
                app.logdate.client.data.journals.PartialDraftStorageRecovery.recover(json, value).drafts.forEach {
                    addAll(it.lateMediaReferences())
                }
            }
        }
        entryDrafts.forEach { addAll(it.lateMediaReferences()) }
    }

private fun safeNativeMediaRelative(relative: String): Boolean =
    relative.length <= 1024 &&
        relative.split('/').let { parts ->
            parts.size >= 2 &&
                parts.first() in setOf("media", "user_media", "audio_notes", "camera-captures") &&
                parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || it.any { char -> char.code < 32 } }
        }

private val APP_PRIVATE_MEDIA =
    Regex("^/data/(?:data|user/\\d+|user_de/\\d+)/([^/]+)/files/((?:media|user_media|audio_notes|camera-captures)/.+)$")
