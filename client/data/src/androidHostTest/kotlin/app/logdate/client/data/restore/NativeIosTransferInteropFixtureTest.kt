package app.logdate.client.data.restore

import app.logdate.client.device.crypto.AndroidCryptoManager
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.ExistingCanonicalOwner
import app.logdate.client.device.restore.PrivateOsCustodyRecord
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.domain.restore.archive3.NativeArchive3Bundle
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotInput
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotWriter
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Place
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Emits a source-produced bundle for the native Apple decoder's interoperability test. */
class NativeIosTransferInteropFixtureTest {
    @Test
    fun `android archive and private custody form the exact Apple input tree`() {
        runBlocking {
            val scratch = Files.createTempDirectory("native-ios-interoperability-").toFile()
            try {
                val root = File(scratch, "app/files/logdate-os-transfer")
                val archive = File(root, "archive")
                val journalId = Uuid.parse("11111111-1111-1111-1111-111111111111")
                val noteId = Uuid.parse("22222222-2222-2222-2222-222222222222")
                val draftId = Uuid.parse("33333333-3333-3333-3333-333333333333")
                val blockId = Uuid.parse("44444444-4444-4444-4444-444444444444")
                val ownerId = "55555555-5555-5555-5555-555555555555"
                val placeId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                val timestamp = Instant.parse("2026-10-23T00:00:00Z")
                val rawLocation =
                    """{"coordinates":{"latitude":36.1699,"longitude":-115.1398,"altitude":625.0},"place":{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"Home"},"preserveSourcePlace":true}"""
                NativeArchive3SnapshotWriter.write(
                    NativeArchive3SnapshotInput(
                        exportedAt = timestamp.toString(),
                        journals =
                            listOf(
                                Journal(
                                    id = journalId,
                                    title = "October",
                                    description = "Autumn notes",
                                    isFavorited = true,
                                    created = timestamp,
                                    lastUpdated = timestamp,
                                ),
                            ),
                        notes =
                            listOf(
                                JournalNote.Text(
                                    uid = noteId,
                                    creationTimestamp = timestamp,
                                    lastUpdated = timestamp,
                                    content = "Hello",
                                    location =
                                        NoteLocation(
                                            coordinates = NoteCoordinates(36.1699, -115.1398, altitude = 625.0),
                                            place = NotePlace(placeId, "Home"),
                                        ),
                                ),
                            ),
                        journalIdsByNote = mapOf(noteId to listOf(journalId)),
                        noteSourceLocationMetadata = mapOf(noteId to rawLocation),
                        drafts =
                            listOf(
                                EditorDraft(
                                    id = draftId,
                                    blocks = listOf(SerializableTextBlock(blockId, timestamp, content = "Unfinished")),
                                    selectedJournalIds = listOf(journalId),
                                    createdAt = timestamp,
                                    lastModifiedAt = timestamp,
                                ),
                            ) +
                                NativeArchive3EntryDrafts.convert(
                                    listOf(
                                        EntryDraft(
                                            id = Uuid.parse("77777777-7777-7777-7777-777777777777"),
                                            notes =
                                                listOf(
                                                    JournalNote.Text(
                                                        uid = Uuid.parse("88888888-8888-8888-8888-888888888888"),
                                                        creationTimestamp = timestamp,
                                                        lastUpdated = timestamp,
                                                        content = "Still writing",
                                                        location = NoteLocation(NoteCoordinates(36.1699, -115.1398, altitude = 625.0)),
                                                    ),
                                                ),
                                            createdAt = timestamp,
                                            updatedAt = timestamp,
                                            selectedJournalIds = listOf(journalId),
                                        ),
                                    ),
                                    ownerBound = false,
                                ),
                        profile = LogDateProfile(),
                        places =
                            listOf(
                                Place.UserDefined(
                                    id = placeId,
                                    displayName = "Home",
                                    lat = 36.1699,
                                    lng = -115.1398,
                                    radiusMeters = 120.0,
                                    description = "Our place",
                                ),
                            ),
                        placeSourceTimes = mapOf(placeId to (1_700_000_000_123L to 1_700_000_500_456L)),
                        semanticPlaces =
                            Json
                                .parseToJsonElement(
                                    """[{"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","name":"Trailhead","latitude":36.1,"longitude":-115.1,"radius":30.0,"units":"m","created":1700000000123,"last_updated":1700000500456,"sync_version":7,"deleted_at":1700000600789}]""",
                                ).jsonArray,
                        locationHistory = emptyList(),
                        originals = emptyList(),
                        unsupportedPopulatedCategories = emptySet(),
                        validateContext = {},
                    ),
                    StagingDirectoryArchiveContainer(FileSystem.SYSTEM, archive.path.toPath()),
                )
                val names =
                    archive
                        .walkTopDown()
                        .filter(File::isFile)
                        .map { it.relativeTo(archive).invariantSeparatorsPath }
                        .toList()
                val bundle = NativeArchive3Bundle.read(names, { File(archive, it).source() })
                val journalRoot = Json.parseToJsonElement(File(archive, "data/journals.json").readText()).jsonObject
                val journalArray = journalRoot.getValue("journals").jsonArray
                val journal = journalArray.single().jsonObject
                assertEquals("Autumn notes", journal.getValue("description").jsonPrimitive.content)
                assertEquals("true", journal.getValue("favorite").jsonPrimitive.content)
                assertEquals(1, bundle.notes.size)
                assertEquals(2, bundle.drafts.size)
                assertEquals(1, bundle.semanticPlaces.size)
                assertEquals("46c4f2fb71fb8eccb1e4513bf2f3684bd95d831da447de7908d2f76757334cdc", bundle.checksumsSha256)
                assertEquals("b7d6ad07a9fab11de767769341e9c95b9f99b4ce63cb3ffecc897629747f32fd", bundle.manifestSha256)
                val custody = File(root, ".logdate-os-private/custody-v1.json")
                assertTrue(custody.parentFile!!.mkdirs())
                val emptyStorage =
                    object : SecureStorage {
                        override suspend fun getString(key: String): String? = null

                        override suspend fun putString(
                            key: String,
                            value: String,
                        ): Unit = error("Read-only fixture")

                        override suspend fun remove(key: String): Unit = error("Read-only fixture")

                        override suspend fun clear(): Unit = error("Read-only fixture")

                        override fun observeString(key: String): Flow<String?> = error("Read-only fixture")

                        override fun observeAll(): Flow<Map<String, String>> = error("Read-only fixture")

                        override suspend fun encrypt(data: ByteArray): ByteArray = error("Read-only fixture")

                        override suspend fun decrypt(data: ByteArray): ByteArray? = error("Read-only fixture")
                    }
                val identity = IdentityKeyManager(emptyStorage, AndroidCryptoManager())
                val record =
                    assertNotNull(
                        PrivateOsCustodyRecord.prepareRootlessPlaintext(
                            transportFlags = 9,
                            transferId = Uuid.parse("66666666-6666-6666-6666-666666666666"),
                            sourceGeneration = ByteArray(32) { 7 },
                            owner = ExistingCanonicalOwner(ownerId, false),
                            checksumsFileSha256 = bundle.checksumsSha256,
                            manifestSha256 = bundle.manifestSha256,
                        ) { identity.observeRootlessKeys() },
                    )
                record.withEncodedBytes { custody.writeBytes(it) }
                assertEquals(
                    "c923fc0321a3cc1b3246b61de2f4a90f073f6cd430009d1fbfa1c29298b6c5ff",
                    MessageDigest.getInstance("SHA-256").digest(custody.readBytes()).joinToString("") {
                        "%02x".format(
                            it.toInt() and 0xff,
                        )
                    },
                )
                val emitted = mutableListOf<String>()
                emitNativeIosStagedFiles(root) { emitted += it.relativeTo(scratch).invariantSeparatorsPath }
                assertEquals(
                    names.sorted().map { "app/files/logdate-os-transfer/archive/$it" } +
                        "app/files/logdate-os-transfer/.logdate-os-private/custody-v1.json",
                    emitted,
                )
                System.getenv("LOGDATE_IOS_INTEROP_FIXTURE_OUTPUT")?.let { destination ->
                    val output = File(destination)
                    require(!output.exists()) { "Fixture destination must be new" }
                    check(output.mkdirs())
                    check(File(scratch, "app").copyRecursively(File(output, "app")))
                }
            } finally {
                check(scratch.deleteRecursively())
            }
        }
    }
}
