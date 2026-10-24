package app.logdate.client.domain.restore.archive3

import app.logdate.client.domain.export.archive.ArchivePath
import app.logdate.client.domain.export.archive.ArchiveWriter
import app.logdate.client.domain.export.archive.ChecksumFile
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Source
import okio.use

internal data class NativeArchive3MediaSource(
    val path: ArchivePath,
    val mediaType: String,
    val bytes: Long,
    val sha256: String,
    val open: () -> Source,
)

internal data class NativeArchive3ExportSource(
    val exportedAt: String,
    val journals: List<NativeArchive3JournalRecord>,
    val notes: JsonArray,
    val drafts: JsonArray,
    val media: List<NativeArchive3MediaSource>,
    val places: JsonArray = JsonArray(emptyList()),
    val semanticPlaces: JsonArray = JsonArray(emptyList()),
    val profile: JsonObject? = null,
    val locationSamples: List<JsonObject> = emptyList(),
    val unsupportedPopulatedCategories: Set<String> = emptySet(),
    val validateContext: () -> Unit = {},
)

internal object NativeArchive3Writer {
    fun write(
        source: NativeArchive3ExportSource,
        container: StagingDirectoryArchiveContainer,
    ): NativeArchive3Bundle {
        try {
            requireSourceInstant(source.exportedAt)
            require(source.unsupportedPopulatedCategories.isEmpty()) {
                "Native transfer cannot retain: ${source.unsupportedPopulatedCategories.sorted().joinToString()}"
            }
            source.validateContext()
            val writer = ArchiveWriter(container)
            val media = writeMedia(source, writer)
            writeDocuments(source, writer, media)
            val manifest = manifest(source, media.size)
            writer.text(path("manifest.json"), manifest.toString())
            writer.text(path("SHA256SUMS"), ChecksumFile.render(writer.ledger.entries))
            source.validateContext()
            val names = writer.ledger.entries.map { it.path.value }
            val admitted =
                NativeArchive3Bundle.read(
                    names,
                    { name -> container.fileSystem.source(container.root / name) },
                )
            source.validateContext()
            return admitted
        } catch (failure: Throwable) {
            runCatching { container.discard() }
            throw failure
        }
    }

    private fun writeMedia(
        source: NativeArchive3ExportSource,
        writer: ArchiveWriter,
    ): List<NativeArchive3MediaSource> {
        val media = source.media.sortedBy { it.path.value }
        require(media.map { it.path }.distinct().size == media.size)
        for (item in media) {
            require(item.path.value.startsWith("media/") && item.mediaType.isNotBlank() && item.bytes > 0)
            require(item.sha256.matches(Regex("[0-9a-f]{64}")))
            source.validateContext()
            item.open().use { writer.media(item.path, it, job = null) }
            val recorded = requireNotNull(writer.ledger[item.path])
            require(recorded.bytes == item.bytes && recorded.sha256 == item.sha256) { "Native original changed" }
        }
        return media
    }

    private fun writeDocuments(
        source: NativeArchive3ExportSource,
        writer: ArchiveWriter,
        media: List<NativeArchive3MediaSource>,
    ) {
        writer.text(path("data/journals.json"), NativeArchive3JournalCodec.encode(source.journals))
        writer.text(path("data/notes.json"), buildJsonObject { put("notes", source.notes) }.toString())
        writer.text(path("data/drafts.json"), buildJsonObject { put("drafts", source.drafts) }.toString())
        if (source.places.isNotEmpty() || source.semanticPlaces.isNotEmpty()) {
            writer.text(
                path("data/places.json"),
                buildJsonObject {
                    put("places", source.places)
                    if (source.semanticPlaces.isNotEmpty()) put("nativeAndroidSemanticPlaces", source.semanticPlaces)
                }.toString(),
            )
        }
        source.profile?.let { writer.text(path("data/profile.json"), buildJsonObject { put("profile", it) }.toString()) }
        if (source.locationSamples.isNotEmpty()) {
            writer.text(path("data/location-history.jsonl"), source.locationSamples.joinToString("\n", postfix = "\n"))
        }
        val inventory =
            media.map { item ->
                val recorded = requireNotNull(writer.ledger[item.path])
                buildJsonObject {
                    put("path", item.path.value)
                    put("mediaType", item.mediaType)
                    put("bytes", recorded.bytes)
                    put("sha256", recorded.sha256)
                }
            }
        writer.text(path("data/media.json"), buildJsonObject { put("files", JsonArray(inventory)) }.toString())
        writer.text(path("README.txt"), "LogDate native archive 3.0. No credentials are included.\n")
        NativeArchive3Schemas.documents.forEach { (name, schema) ->
            writer.text(path("schema/$name.schema.json"), schema)
        }
    }

    private fun manifest(
        source: NativeArchive3ExportSource,
        mediaCount: Int,
    ): JsonObject {
        val contents =
            buildList {
                addAll(
                    listOf(
                        content("readme", "README.txt", null),
                        content("journals", "data/journals.json", "journals"),
                        content("notes", "data/notes.json", "notes"),
                        content("drafts", "data/drafts.json", "drafts"),
                        content("mediaInventory", "data/media.json", "media"),
                        content("checksums", "SHA256SUMS", null),
                    ),
                )
                if (source.places.isNotEmpty() || source.semanticPlaces.isNotEmpty()) add(content("places", "data/places.json", "places"))
                if (source.profile != null) add(content("profile", "data/profile.json", "profile"))
                if (source.locationSamples.isNotEmpty()) {
                    add(
                        content("locationHistory", "data/location-history.jsonl", "location-sample"),
                    )
                }
            }
        return buildJsonObject {
            put("format", "logdate-export")
            put("schemaVersion", "3.0")
            put("exportedAt", source.exportedAt)
            put("exportTimeZone", "UTC")
            put(
                "generator",
                buildJsonObject {
                    put("name", "LogDate native archive writer")
                    put("version", "3.0")
                },
            )
            put("owner", buildJsonObject {})
            put(
                "scope",
                buildJsonObject {
                    put("complete", true)
                    put("omitted", JsonArray(emptyList()))
                },
            )
            put(
                "counts",
                buildJsonObject {
                    put("journals", source.journals.size)
                    put("notes", source.notes.size)
                    put("drafts", source.drafts.size)
                    put("media", mediaCount)
                    put("places", source.places.size)
                    put("semanticPlaces", source.semanticPlaces.size)
                    put("locationSamples", source.locationSamples.size)
                    put("hasProfile", source.profile != null)
                },
            )
            put("contents", JsonArray(contents))
        }
    }

    private fun path(value: String) = ArchivePath.of(value)

    private fun content(
        role: String,
        value: String,
        schema: String?,
    ) = buildJsonObject {
        put("role", role)
        put("path", value)
        put(
            "mediaType",
            if (schema ==
                null
            ) {
                "text/plain"
            } else if (role == "locationHistory") {
                "text/plain"
            } else {
                "application/json"
            },
        )
        if (schema != null) put("schema", "schema/$schema.schema.json")
    }
}
