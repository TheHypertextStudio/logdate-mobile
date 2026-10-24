package app.logdate.client.domain.restore.archive3

import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.PhotoPresentation
import app.logdate.shared.model.Place
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NativeArchive3SnapshotWriterTest {
    @Test
    fun `semantic place rows survive without a saved place`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-semantic-place").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val place =
                    Json
                        .parseToJsonElement(
                            """{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"Trailhead","latitude":36.1,"longitude":-115.1,"radius":30.0,"units":"m","created":1700000000123,"last_updated":1700000000456,"sync_version":7,"deleted_at":1700000000999}""",
                        ).jsonObject
                NativeArchive3SnapshotWriter.write(
                    NativeArchive3SnapshotInput(
                        exportedAt = "2026-10-23T00:00:00Z",
                        journals = emptyList(),
                        notes = emptyList(),
                        journalIdsByNote = emptyMap(),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        semanticPlaces = kotlinx.serialization.json.JsonArray(listOf(place)),
                        locationHistory = emptyList(),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    ),
                    StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root),
                )
                val places = Json.parseToJsonElement(parent.resolve("archive/data/places.json").readText()).jsonObject
                assertEquals(listOf(place), places.getValue("nativeAndroidSemanticPlaces").jsonArray.toList())
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `saved places without source timestamps are refused`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-place-times").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val place =
                    Place.UserDefined(
                        id = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        displayName = "Home",
                        lat = 36.1699,
                        lng = -115.1398,
                        radiusMeters = 120.0,
                    )
                val source =
                    NativeArchive3SnapshotInput(
                        exportedAt = "2026-10-23T00:00:00Z",
                        journals = emptyList(),
                        notes = emptyList(),
                        journalIdsByNote = emptyMap(),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = listOf(place),
                        locationHistory = emptyList(),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    )
                assertFailsWith<IllegalArgumentException> {
                    NativeArchive3SnapshotWriter.write(source, StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root))
                }
                assertFalse(FileSystem.SYSTEM.exists(root))
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `frozen journal and text note retain membership and original values`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-snapshot").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val journalId = Uuid.parse("11111111-1111-1111-1111-111111111111")
                val noteId = Uuid.parse("22222222-2222-2222-2222-222222222222")
                val timestamp = Instant.parse("2026-10-23T00:00:00Z")
                val source =
                    NativeArchive3SnapshotInput(
                        exportedAt = timestamp.toString(),
                        journals =
                            listOf(
                                Journal(
                                    id = journalId,
                                    title = "October",
                                    description = "A journal",
                                    created = timestamp,
                                    lastUpdated = timestamp,
                                ),
                            ),
                        notes =
                            listOf(
                                JournalNote.Text(uid = noteId, creationTimestamp = timestamp, lastUpdated = timestamp, content = "Hello"),
                            ),
                        journalIdsByNote = mapOf(noteId to listOf(journalId)),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        locationHistory = emptyList(),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    )
                NativeArchive3SnapshotWriter.write(source, StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root))
                val bundle =
                    NativeArchive3Bundle.read(
                        Files.walk(parent.resolve("archive").toPath()).use { stream ->
                            stream
                                .filter(
                                    Files::isRegularFile,
                                ).map { it.toFile().relativeTo(parent.resolve("archive")).invariantSeparatorsPath }
                                .toList()
                        },
                        { FileSystem.SYSTEM.source(root / it) },
                    )
                assertEquals(1, bundle.journals.size)
                assertEquals(1, bundle.notes.size)
                assertEquals(
                    "Hello",
                    bundle.notes
                        .single()
                        .let {
                            it as kotlinx.serialization.json.JsonObject
                        }["text"]
                        ?.let { it as kotlinx.serialization.json.JsonPrimitive }
                        ?.content,
                )
                val orphaned = parent.resolve("orphaned-location").absolutePath.toPath()
                assertFailsWith<IllegalArgumentException> {
                    NativeArchive3SnapshotWriter.write(
                        source.copy(noteSourceLocationMetadata = mapOf(Uuid.random() to "{}")),
                        StagingDirectoryArchiveContainer(FileSystem.SYSTEM, orphaned),
                    )
                }
                assertFalse(FileSystem.SYSTEM.exists(orphaned))
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `unsupported source category is refused before an archive appears`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-unsupported").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val source =
                    NativeArchive3SnapshotInput(
                        exportedAt = "2026-10-23T00:00:00Z",
                        journals = emptyList(),
                        notes = emptyList(),
                        journalIdsByNote = emptyMap(),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        locationHistory = emptyList(),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = setOf("audioTags"),
                        validateContext = {},
                    )
                assertFailsWith<IllegalArgumentException> {
                    NativeArchive3SnapshotWriter.write(source, StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root))
                }
                assertFalse(FileSystem.SYSTEM.exists(root))
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `location history retains provenance and activity fields`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-history").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val timestamp = Instant.parse("2026-10-23T00:00:00Z")
                val sample =
                    LocationHistoryItem(
                        sampleId = "33333333-3333-3333-3333-333333333333",
                        userId = "person-1",
                        deviceId = "phone-1",
                        timestamp = timestamp,
                        location = Location(36.1, -115.1, LocationAltitude(620.0, AltitudeUnit.METERS)),
                        confidence = 1f,
                        isGenuine = true,
                        activityType = "walking",
                        timeZoneId = "America/Los_Angeles",
                    )
                val input =
                    NativeArchive3SnapshotInput(
                        exportedAt = timestamp.toString(),
                        journals = emptyList(),
                        notes = emptyList(),
                        journalIdsByNote = emptyMap(),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        locationHistory = listOf(sample),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    )
                NativeArchive3SnapshotWriter.write(input, StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root))
                val bundle =
                    NativeArchive3Bundle.read(
                        Files.walk(parent.resolve("archive").toPath()).use { stream ->
                            stream
                                .filter(
                                    Files::isRegularFile,
                                ).map { it.toFile().relativeTo(parent.resolve("archive")).invariantSeparatorsPath }
                                .toList()
                        },
                        { FileSystem.SYSTEM.source(root / it) },
                    )
                val retained = bundle.locationSamples.single()
                assertEquals(sample.sampleId, retained.getValue("sampleId").jsonPrimitive.content)
                assertEquals(sample.userId, retained.getValue("userId").jsonPrimitive.content)
                assertEquals(sample.deviceId, retained.getValue("deviceId").jsonPrimitive.content)
                assertEquals(sample.activityType, retained.getValue("activityType").jsonPrimitive.content)
                assertEquals(sample.timeZoneId, retained.getValue("timeZoneId").jsonPrimitive.content)
            } finally {
                parent.deleteRecursively()
            }
        }

    @Test
    fun `image draft preserves presentation and original through receiver admission`() =
        runBlocking {
            val parent = Files.createTempDirectory("logdate-archive3-image-draft").toFile()
            try {
                val root = parent.resolve("archive").absolutePath.toPath()
                val timestamp = Instant.parse("2026-10-23T00:00:00Z")
                val reference = "file:///private/draft-photo.png"
                val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
                val source =
                    NativeArchive3SnapshotInput(
                        exportedAt = timestamp.toString(),
                        journals = emptyList(),
                        notes = emptyList(),
                        journalIdsByNote = emptyMap(),
                        drafts =
                            listOf(
                                EditorDraft(
                                    id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
                                    blocks =
                                        listOf(
                                            SerializableImageBlock(
                                                id = Uuid.parse("44444444-4444-4444-4444-444444444444"),
                                                timestamp = timestamp,
                                                uri = reference,
                                                caption = "A photo",
                                                presentation = PhotoPresentation.Framed,
                                            ),
                                        ),
                                    createdAt = timestamp,
                                    lastModifiedAt = timestamp,
                                ),
                            ),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        locationHistory = emptyList(),
                        originals =
                            listOf(
                                NativeArchive3Original(reference, bytes.size.toLong(), bytes.toByteString().sha256().hex()) {
                                    Buffer().write(bytes)
                                },
                            ),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    )
                NativeArchive3SnapshotWriter.write(source, StagingDirectoryArchiveContainer(FileSystem.SYSTEM, root))
                val bundle =
                    NativeArchive3Bundle.read(
                        Files.walk(parent.resolve("archive").toPath()).use { stream ->
                            stream
                                .filter(
                                    Files::isRegularFile,
                                ).map { it.toFile().relativeTo(parent.resolve("archive")).invariantSeparatorsPath }
                                .toList()
                        },
                        { FileSystem.SYSTEM.source(root / it) },
                    )
                val block =
                    bundle.drafts
                        .single()
                        .jsonObject
                        .getValue("blocks")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("Framed", block.getValue("photoPresentation").jsonPrimitive.content)
                assertEquals("A photo", block.getValue("caption").jsonPrimitive.content)
                val mediaPath =
                    block
                        .getValue("media")
                        .jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
                assertEquals(bytes.toList(), FileSystem.SYSTEM.read(root / mediaPath) { readByteArray().toList() })
            } finally {
                parent.deleteRecursively()
            }
        }
}
