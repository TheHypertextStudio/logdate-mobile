package app.logdate.client.data.restore

import app.logdate.client.device.crypto.AndroidCryptoManager
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.ExistingCanonicalOwner
import app.logdate.client.device.restore.PrivateOsCustodyRecord
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.domain.export.archive.StagingDirectoryArchiveContainer
import app.logdate.client.domain.restore.archive3.NativeArchive3Bundle
import app.logdate.client.domain.restore.archive3.NativeArchive3Original
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotInput
import app.logdate.client.domain.restore.archive3.NativeArchive3SnapshotWriter
import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Journal
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Source-produced original and existing-root custody read by the native Apple host test. */
class NativeIosTransferMediaInteropFixtureTest {
    @Test
    fun `android media original and bound private custody form Apple input tree`() {
        runBlocking {
            val scratch = Files.createTempDirectory("native-ios-media-").toFile()
            try {
                val root = File(scratch, "app/files/logdate-os-transfer")
                val archive = File(root, "archive")
                val journalId = Uuid.parse("11111111-1111-1111-1111-111111111111")
                val noteId = Uuid.parse("99999999-9999-9999-9999-999999999999")
                val ownerId = "55555555-5555-5555-5555-555555555555"
                val timestamp = Instant.parse("2026-10-23T00:00:00Z")
                val reference = "file:///private/autumn-photo.png"
                val original = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lV8AAAAASUVORK5CYII=")
                NativeArchive3SnapshotWriter.write(
                    NativeArchive3SnapshotInput(
                        exportedAt = timestamp.toString(),
                        journals = listOf(Journal(id = journalId, title = "October", created = timestamp, lastUpdated = timestamp)),
                        notes =
                            listOf(
                                JournalNote.Image(
                                    uid = noteId,
                                    creationTimestamp = timestamp,
                                    lastUpdated = timestamp,
                                    mediaRef = reference,
                                    caption = "Autumn photo",
                                ),
                            ),
                        journalIdsByNote = mapOf(noteId to listOf(journalId)),
                        drafts = emptyList(),
                        profile = LogDateProfile(),
                        places = emptyList(),
                        locationHistory = emptyList(),
                        originals =
                            listOf(
                                NativeArchive3Original(reference, original.size.toLong(), original.toByteString().sha256().hex()) {
                                    Buffer().write(original)
                                },
                            ),
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
                assertEquals(1, bundle.notes.size)
                assertEquals(1, names.count { it.startsWith("media/") })
                val storage =
                    object : SecureStorage {
                        val values =
                            mutableMapOf(
                                "identity_account_id_v1" to ownerId,
                                "identity_key_v1" to Base64.encode(ByteArray(32) { 20 }),
                                "media_payload_key_v1" to Base64.encode(ByteArray(32) { 21 }),
                            )

                        override suspend fun getString(key: String): String? = values[key]

                        override suspend fun putString(
                            key: String,
                            value: String,
                        ) {
                            values[key] = value
                        }

                        override suspend fun remove(key: String) {
                            values.remove(key)
                        }

                        override suspend fun clear() {
                            values.clear()
                        }

                        override fun observeString(key: String): Flow<String?> = error("Read-only fixture")

                        override fun observeAll(): Flow<Map<String, String>> = error("Read-only fixture")

                        override suspend fun encrypt(data: ByteArray): ByteArray = error("Read-only fixture")

                        override suspend fun decrypt(data: ByteArray): ByteArray? = error("Read-only fixture")
                    }
                val identity = IdentityKeyManager(storage, AndroidCryptoManager())
                val custody = File(root, ".logdate-os-private/custody-v1.json")
                assertTrue(custody.parentFile!!.mkdirs())
                val record =
                    assertNotNull(
                        PrivateOsCustodyRecord.prepare(
                            transportFlags = 9,
                            transferId = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                            sourceGeneration = ByteArray(32) { 7 },
                            owner = ExistingCanonicalOwner(ownerId, true),
                            checksumsFileSha256 = bundle.checksumsSha256,
                            manifestSha256 = bundle.manifestSha256,
                        ) { identity.snapshotExistingKeys(ownerId) { ByteArray(32) { 21 } } },
                    )
                record.withEncodedBytes { custody.writeBytes(it) }
                val emitted = mutableListOf<String>()
                emitNativeIosStagedFiles(root) { emitted += it.relativeTo(scratch).invariantSeparatorsPath }
                assertEquals(
                    names.sorted().map { "app/files/logdate-os-transfer/archive/$it" } +
                        "app/files/logdate-os-transfer/.logdate-os-private/custody-v1.json",
                    emitted,
                )
                System.getenv("LOGDATE_IOS_MEDIA_INTEROP_FIXTURE_OUTPUT")?.let { destination ->
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
