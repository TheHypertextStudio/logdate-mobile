package app.logdate.client.domain.restore.archive3

import app.logdate.client.domain.export.archive.ArchiveContainer
import app.logdate.client.domain.export.archive.ArchivePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Buffer
import okio.HashingSink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * Lossless, verified native wire content. Owner/key authorization and atomic database application are
 * separate requirements. Public export/restore still do not admit this format.
 */
class NativeArchive3Bundle private constructor(
    private val resources: Map<String, Resource>,
    internal val journals: List<NativeArchive3JournalRecord>,
    val notes: JsonArray,
    val drafts: JsonArray,
    val places: JsonArray,
    val semanticPlaces: JsonArray,
    val profile: JsonObject?,
    val locationSamples: List<JsonObject>,
    val manifestSha256: String,
    val checksumsSha256: String,
) {
    internal data class Resource(
        val bytes: Long,
        val sha256: String,
    )

    /** The caller owns a private staging container and publishes it only after this method returns. */
    fun write(
        open: (String) -> Source,
        container: ArchiveContainer,
    ) {
        // Check the entire source before emitting even the first staged destination entry.
        resources.forEach { (path, expected) ->
            require(scan(open(path), expected.bytes).first == expected) { "Native archive source changed" }
        }
        resources.entries.sortedBy { it.key }.forEach { (path, expected) ->
            container.entry(ArchivePath.of(path), compress = !path.startsWith("media/")) { sink ->
                val hashing = HashingSink.sha256(sink)
                open(path).use { source ->
                    val buffer = Buffer()
                    var count = 0L
                    while (true) {
                        val read = source.read(buffer, 8192)
                        if (read == -1L) break
                        require(read > 0 && read <= expected.bytes - count) { "Native archive source grew" }
                        hashing.write(buffer, read)
                        count += read
                    }
                    require(count == expected.bytes && hashing.hash.hex() == expected.sha256) { "Native archive source changed" }
                    hashing.flush()
                }
            }
        }
    }

    companion object {
        private const val METADATA_LIMIT = 16L * 1024 * 1024
        private const val RESOURCE_LIMIT = 100_000
        private const val TOTAL_LIMIT = 64L * 1024 * 1024 * 1024
        private const val TOTAL_METADATA_LIMIT = 64L * 1024 * 1024
        private val fixed =
            setOf(
                "README.txt",
                "manifest.json",
                "SHA256SUMS",
                "data/journals.json",
                "data/notes.json",
                "data/drafts.json",
                "data/media.json",
            )
        private val schemas =
            NativeArchive3Schemas.documents.keys
                .map { "schema/$it.schema.json" }
                .toSet()
        private val optional = setOf("data/places.json", "data/profile.json", "data/location-history.jsonl")

        fun read(
            names: Iterable<String>,
            open: (String) -> Source,
            availableBytes: Long = Long.MAX_VALUE,
        ): NativeArchive3Bundle {
            require(availableBytes >= 0)
            val paths = collectPaths(names)
            val capacity = minOf(availableBytes, TOTAL_LIMIT)
            val checksumData = scan(open("SHA256SUMS"), METADATA_LIMIT, collect = true)
            val ledger = readChecksumLedger(checksumData.second, paths)
            val (resources, documents) = readResources(ledger, checksumData.first, capacity, open)
            val manifest = NativeArchive3SchemaValidation.document("manifest", documents.getValue("manifest.json").strictText())
            validateManifest(manifest)
            val journals = NativeArchive3JournalCodec.decode(documents.getValue("data/journals.json").strictText())
            val notes = decodeArray("notes", "notes", documents)
            val drafts = decodeArray("drafts", "drafts", documents)
            val media = decodeArray("media", "files", documents)
            val (places, semanticPlaces) = readPlaces(documents)
            val profile =
                documents["data/profile.json"]?.let {
                    NativeArchive3SchemaValidation.document("profile", it.strictText()).getValue("profile").jsonObject
                }
            val locationSamples =
                documents["data/location-history.jsonl"]?.strictText()?.let { text ->
                    require(text.endsWith("\n"))
                    text.dropLast(1).split('\n').map { NativeArchive3SchemaValidation.document("location-sample", it) }
                } ?: emptyList()
            validateRecords(journals, notes, drafts, media, resources)
            validateCounts(
                manifest,
                journals.size,
                notes.size,
                drafts.size,
                media.size,
                places.size,
                semanticPlaces.size,
                locationSamples.size,
                profile != null,
            )
            validateDeclaredResources(
                paths,
                media,
                places.isNotEmpty() || semanticPlaces.isNotEmpty(),
                profile != null,
                locationSamples.isNotEmpty(),
                documents,
            )
            return NativeArchive3Bundle(
                resources,
                journals,
                notes,
                drafts,
                places,
                semanticPlaces,
                profile,
                locationSamples,
                resources.getValue("manifest.json").sha256,
                checksumData.first.sha256,
            )
        }

        private fun readPlaces(documents: Map<String, ByteArray>): Pair<JsonArray, JsonArray> {
            val placesDocument =
                documents["data/places.json"]?.let {
                    NativeArchive3SchemaValidation
                        .document(
                            "places",
                            it.strictText(),
                        )
                }
            val places = placesDocument?.getValue("places")?.jsonArray ?: JsonArray(emptyList())
            val semanticPlaces = placesDocument?.get("nativeAndroidSemanticPlaces")?.jsonArray ?: JsonArray(emptyList())
            return places to semanticPlaces
        }

        private fun validateDeclaredResources(
            paths: Set<String>,
            media: JsonArray,
            hasPlaces: Boolean,
            hasProfile: Boolean,
            hasLocationSamples: Boolean,
            documents: Map<String, ByteArray>,
        ) {
            val mediaPaths = media.map { it.jsonObject.getValue("path").string() }.toSet()
            val declaredOptional =
                buildSet {
                    if (hasPlaces) add("data/places.json")
                    if (hasProfile) add("data/profile.json")
                    if (hasLocationSamples) add("data/location-history.jsonl")
                }
            require(paths.toSet() == fixed + schemas + mediaPaths + declaredOptional) { "Undeclared native archive resource" }
            schemas.forEach { path ->
                // Descriptive schemas are data only, but must be well-formed JSON objects.
                require(Json.parseToJsonElement(documents.getValue(path).strictText()) is JsonObject)
            }
        }

        private fun collectPaths(names: Iterable<String>): Set<String> {
            val paths = linkedSetOf<String>()
            for (path in names) {
                require(paths.size < RESOURCE_LIMIT) { "Too many native resources" }
                require(paths.add(path)) { "Repeated native resource" }
            }
            paths.forEach { requireNotNull(ArchivePath.parse(it)) { "Unsafe native archive path" } }
            require(paths.containsAll(fixed + schemas)) { "Incomplete native archive" }
            require(paths.all { it in fixed || it in schemas || it in optional || it.startsWith("media/") }) { "Unknown native resource" }
            return paths
        }

        private fun readChecksumLedger(
            bytes: ByteArray,
            paths: Set<String>,
        ): Map<String, String> {
            val ledger = linkedMapOf<String, String>()
            bytes.strictText().lineSequence().filter { it.isNotEmpty() }.forEach { line ->
                require(line.length > 66 && line.substring(64, 66) == "  ")
                val hash = line.take(64)
                val path = line.substring(66)
                require(hash.matches(Regex("[0-9a-f]{64}")) && ArchivePath.parse(path) != null && ledger.put(path, hash) == null)
            }
            require(ledger.keys == paths - "SHA256SUMS") { "Native archive checksum coverage mismatch" }
            return ledger
        }

        private fun readResources(
            ledger: Map<String, String>,
            checksumResource: Resource,
            capacity: Long,
            open: (String) -> Source,
        ): Pair<Map<String, Resource>, Map<String, ByteArray>> {
            val resources = linkedMapOf("SHA256SUMS" to checksumResource)
            val documents = linkedMapOf<String, ByteArray>()
            var total = checksumResource.bytes
            var metadataTotal = total
            require(total <= capacity)
            for (path in ledger.keys) {
                val collect = !path.startsWith("media/")
                val limit =
                    if (path == "manifest.json") {
                        1_048_576L
                    } else if (collect) {
                        METADATA_LIMIT
                    } else {
                        capacity - total
                    }
                require(limit >= 0)
                val (resource, bytes) = scan(open(path), minOf(limit, capacity - total), collect)
                require(resource.sha256 == ledger[path]) { "Native archive checksum mismatch" }
                total += resource.bytes
                require(total <= capacity) { "Insufficient native archive capacity" }
                resources[path] = resource
                if (collect) {
                    metadataTotal += resource.bytes
                    require(metadataTotal <= TOTAL_METADATA_LIMIT)
                    documents[path] = bytes
                }
            }
            return resources to documents
        }

        private fun validateManifest(manifest: JsonObject) {
            val scope = manifest.getValue("scope").jsonObject
            require(scope["complete"] == JsonPrimitive(true) && scope["omitted"] == JsonArray(emptyList()) && "dateRange" !in scope)
            require(
                manifest
                    .getValue("generator")
                    .jsonObject.values
                    .all { it.string().isNotEmpty() },
            )
            validateContents(manifest)
        }

        private fun decodeArray(
            name: String,
            key: String,
            documents: Map<String, ByteArray>,
        ): JsonArray =
            NativeArchive3SchemaValidation.document(name, documents.getValue("data/$name.json").strictText()).getValue(key).jsonArray

        private fun validateCounts(
            manifest: JsonObject,
            journals: Int,
            notes: Int,
            drafts: Int,
            media: Int,
            places: Int,
            semanticPlaces: Int,
            locationSamples: Int,
            hasProfile: Boolean,
        ) {
            val counts = manifest.getValue("counts").jsonObject
            mapOf(
                "journals" to journals,
                "notes" to notes,
                "drafts" to drafts,
                "media" to media,
                "places" to places,
                "locationSamples" to locationSamples,
            ).forEach { (key, size) ->
                require(counts.getValue(key).jsonPrimitive.longOrNull == size.toLong()) { "Native archive record count mismatch" }
            }
            require(counts["hasProfile"] == JsonPrimitive(hasProfile))
            require((counts["semanticPlaces"]?.jsonPrimitive?.longOrNull ?: 0L) == semanticPlaces.toLong())
        }

        private fun validateContents(manifest: JsonObject) {
            val contents = manifest.getValue("contents").jsonArray.map { it.jsonObject }
            val expected =
                buildMap {
                    putAll(
                        mapOf(
                            "readme" to "README.txt",
                            "journals" to "data/journals.json",
                            "notes" to "data/notes.json",
                            "drafts" to "data/drafts.json",
                            "mediaInventory" to "data/media.json",
                            "checksums" to "SHA256SUMS",
                        ),
                    )
                    if (manifest
                            .getValue("counts")
                            .jsonObject
                            .getValue("places")
                            .jsonPrimitive.longOrNull != 0L ||
                        (
                            manifest
                                .getValue("counts")
                                .jsonObject["semanticPlaces"]
                                ?.jsonPrimitive
                                ?.longOrNull ?: 0L
                        ) != 0L
                    ) {
                        put("places", "data/places.json")
                    }
                    if (manifest.getValue("counts").jsonObject.getValue("hasProfile") ==
                        JsonPrimitive(true)
                    ) {
                        put("profile", "data/profile.json")
                    }
                    if (manifest
                            .getValue("counts")
                            .jsonObject
                            .getValue("locationSamples")
                            .jsonPrimitive.longOrNull !=
                        0L
                    ) {
                        put("locationHistory", "data/location-history.jsonl")
                    }
                }
            require(contents.size == expected.size && contents.map { it.getValue("role").string() }.toSet() == expected.keys)
            contents.forEach { item ->
                val path = item.getValue("path").string()
                require(expected[item.getValue("role").string()] == path)
                if (path.startsWith("data/")) {
                    val schema = if (path.endsWith(".jsonl")) "location-sample" else path.substringAfterLast('/').substringBefore('.')
                    require(item["schema"]?.string() == "schema/$schema.schema.json")
                }
            }
        }

        private fun scan(
            source: Source,
            maximum: Long,
            collect: Boolean = false,
        ): Pair<Resource, ByteArray> {
            require(maximum >= 0)
            source.use {
                val hashing = HashingSink.sha256(blackholeSink())
                val collected = Buffer()
                val buffer = Buffer()
                var count = 0L
                while (true) {
                    val read = it.read(buffer, 8192)
                    if (read == -1L) break
                    require(read > 0 && read <= maximum - count) { "Native archive resource exceeds capacity" }
                    count += read
                    if (collect) buffer.copyTo(collected)
                    hashing.write(buffer, read)
                }
                return Resource(count, hashing.hash.hex()) to collected.readByteArray()
            }
        }
    }
}

private fun ByteArray.strictText(): String = decodeToString(throwOnInvalidSequence = true)
