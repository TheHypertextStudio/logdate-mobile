package app.logdate.client.device.restore

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeJournalMediaInventoryTest {
    @Test
    fun `selected pending camera capture travels with its future draft claim`() =
        fixture { root ->
            val files = File(root, "files")
            val capture = "files/camera-captures/LOGDATE_20261024_120000.jpg.00000000-0000-0000-0000-000000000001.tmp"
            val bytes = "captured before draft autosave".encodeToByteArray()
            write(files, capture.removePrefix("files/"), bytes)
            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)

            writeNativeJournalMediaInventory(inventoryFile, files, setOf(capture))

            val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))
            assertEquals(sha256(bytes), inventory.entry(capture)?.sha256)
        }

    @Test
    fun `selected transfer ignores inaccessible orphan directories`() =
        fixture { root ->
            val files = File(root, "files")
            val selected = "files/media/kept.jpg"
            write(files, "media/kept.jpg", "kept".encodeToByteArray())
            val orphan =
                File(files, "media/old-orphans").apply {
                    mkdirs()
                    File(this, "private.jpg").writeText("deleted")
                }
            val original = Files.getPosixFilePermissions(orphan.toPath())
            try {
                Files.setPosixFilePermissions(orphan.toPath(), emptySet<PosixFilePermission>())
                val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)
                writeNativeJournalMediaInventory(inventoryFile, files, setOf(selected))
                val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))
                assertEquals(listOf(selected), inventory.files.map { it.path })
            } finally {
                Files.setPosixFilePermissions(orphan.toPath(), original)
            }
        }

    @Test
    fun `selected transfer never follows a linked media directory`() =
        fixture { root ->
            val files = File(root, "files")
            val outside = File(root, "outside").apply { mkdirs() }
            File(outside, "private.jpg").writeText("other app's bytes")
            val media = File(files, "media").apply { mkdirs() }
            Files.createSymbolicLink(File(media, "linked").toPath(), outside.toPath())
            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)

            writeNativeJournalMediaInventory(inventoryFile, files, setOf("files/media/linked/private.jpg"))

            val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))
            assertTrue(inventory.files.isEmpty())
            assertTrue(File(outside, "private.jpg").exists())
        }

    @Test
    fun `transfer inventory contains referenced canonical originals but omits orphaned originals`() =
        fixture { root ->
            val files = File(root, "files")
            val keptBytes = "referenced".encodeToByteArray()
            val orphanBytes = "deleted".encodeToByteArray()
            val keptHash = sha256(keptBytes)
            val orphanHash = sha256(orphanBytes)
            val kept = "files/media/objects/sha256/${keptHash.take(2)}/$keptHash.jpg"
            val orphan = "files/media/objects/sha256/${orphanHash.take(2)}/$orphanHash.jpg"
            val keptLegacy = "files/user_media/kept.jpg"
            val orphanLegacy = "files/user_media/deleted.jpg"
            val keptRecording = "files/audio_notes/kept.m4a"
            val orphanRecording = "files/audio_notes/deleted.m4a"
            val pendingRecording = "files/audio_notes/staging/pending.m4a"
            val orphanPending = "files/audio_notes/staging/orphan.m4a"
            write(files, kept.removePrefix("files/"), keptBytes)
            write(files, orphan.removePrefix("files/"), orphanBytes)
            write(files, keptLegacy.removePrefix("files/"), keptBytes)
            write(files, orphanLegacy.removePrefix("files/"), orphanBytes)
            write(files, keptRecording.removePrefix("files/"), keptBytes)
            write(files, orphanRecording.removePrefix("files/"), orphanBytes)
            write(files, pendingRecording.removePrefix("files/"), keptBytes)
            write(files, orphanPending.removePrefix("files/"), orphanBytes)
            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)

            writeNativeJournalMediaInventory(inventoryFile, files, setOf(kept, keptLegacy, keptRecording, pendingRecording))

            val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))
            assertEquals(listOf(keptRecording, pendingRecording, kept, keptLegacy).sorted(), inventory.files.map { it.path })
            assertNull(inventory.entry(orphan))
            assertNull(inventory.entry(orphanLegacy))
            assertNull(inventory.entry(orphanRecording))
            assertNull(inventory.entry(orphanPending))
            val expectedHash = sha256(inventoryFile.readBytes())
            inventoryFile.writeText(inventoryFile.readText().replace("staging/pending.m4a", "staging/orphan.m4a"))
            assertFailsWith<IllegalStateException> { verifyNativeJournalMediaInventory(inventoryFile, expectedHash) }
        }

    @Test
    fun `inventory authenticates canonical and legacy originals without staging or symlinks`() =
        fixture { root ->
            val files = File(root, "files")
            val bytes = "source image".encodeToByteArray()
            val hash = sha256(bytes)
            val canonical = "files/media/objects/sha256/${hash.take(2)}/$hash.png"
            write(files, canonical.removePrefix("files/"), bytes)
            write(files, "user_media/older photo.jpg", bytes)
            write(files, "audio_notes/voice.m4a", "voice".encodeToByteArray())
            write(files, "audio_notes/staging/unfinished.m4a", bytes)
            write(files, "user_media/.odd%20name.part", bytes)
            write(files, "user_media/empty-original.jpg", byteArrayOf())
            val link = File(files, "user_media/link.png")
            Files.createSymbolicLink(link.toPath(), File(files, "user_media/older photo.jpg").toPath())

            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventoryFile, files)
            val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))
            assertEquals(5, inventory.files.size)
            assertEquals(hash, inventory.entry(canonical)?.sha256)
            assertEquals(hash, inventory.entry("files/user_media/older photo.jpg")?.sha256)
            assertEquals(
                "voice".encodeToByteArray().size.toLong(),
                inventory.entry("files/audio_notes/voice.m4a")?.size,
            )
            assertNull(inventory.entry("files/audio_notes/staging/unfinished.m4a"))
            assertEquals(hash, inventory.entry("files/user_media/.odd%20name.part")?.sha256)
            assertEquals(0L, inventory.entry("files/user_media/empty-original.jpg")?.size)
            assertEquals(sha256(byteArrayOf()), inventory.entry("files/user_media/empty-original.jpg")?.sha256)
            assertNull(inventory.entry("files/user_media/link.png"))
            assertTrue(inventoryFile.readBytes().size < 8192)
        }

    @Test
    fun `tampered or missing inventory cannot authorize media`() =
        fixture { root ->
            val files = File(root, "files")
            write(files, "user_media/photo.jpg", "original".encodeToByteArray())
            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventoryFile, files)
            val expected = sha256(inventoryFile.readBytes())
            inventoryFile.writeText(inventoryFile.readText().replace("photo.jpg", "other.jpg"))
            assertFailsWith<IllegalStateException> { verifyNativeJournalMediaInventory(inventoryFile, expected) }
            check(inventoryFile.delete())
            assertFailsWith<Exception> { verifyNativeJournalMediaInventory(inventoryFile, expected) }
        }

    @Test
    fun `retained collision variant is included in device transfer only with its exact digest`() =
        fixture { root ->
            val files = File(root, "files")
            val bytes = "incoming photo bytes".encodeToByteArray()
            val hash = sha256(bytes)
            val path = "files/media/objects/sha256/${hash.take(2)}/$hash.retained-1.jpg"
            write(files, path.removePrefix("files/"), bytes)
            write(files, "media/objects/sha256/${hash.take(2)}/$hash.retained-2.jpg", "other bytes".encodeToByteArray())
            val inventoryFile = File(root, NATIVE_MEDIA_INVENTORY_PATH)

            writeNativeJournalMediaInventory(inventoryFile, files)
            val inventory = verifyNativeJournalMediaInventory(inventoryFile, sha256(inventoryFile.readBytes()))

            assertEquals(hash, inventory.entry(path)?.sha256)
            assertNull(inventory.entry("files/media/objects/sha256/${hash.take(2)}/$hash.retained-2.jpg"))
        }

    @Test
    fun `v2 receipt retains journal compatibility but v3 receipt pins a separate inventory hash`() =
        fixture { root ->
            val files = File(root, "files")
            write(files, "user_media/photo.jpg", "original".encodeToByteArray())
            val inventory = File(root, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventory, files)
            val preferencesFile = File(root, "preferences").apply { writeText("preferences") }
            val database = File(root, "database").apply { writeText("encrypted journal") }
            val receipt = File(root, "receipt")
            val preferences =
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                )
            writeNativeJournalSourceReceipt(
                receipt,
                preferences,
                preferencesFile,
                mapOf("database/logdate" to database),
            )
            assertEquals(
                2,
                verifyNativeJournalSourceReceipt(receipt, preferencesFile, mapOf("database/logdate" to database)).version,
            )
            writeNativeJournalSourceReceipt(
                receipt,
                preferences,
                preferencesFile,
                mapOf("database/logdate" to database),
                mediaInventory = inventory,
            )
            val v3 = verifyNativeJournalSourceReceipt(receipt, preferencesFile, mapOf("database/logdate" to database))
            assertEquals(3, v3.version)
            assertEquals(sha256(inventory.readBytes()), v3.mediaInventorySha256)
            assertTrue(receipt.readBytes().size < 8192)
        }

    @Test
    fun `the v3 receipt's inventory is admitted with its source`() =
        fixture { root ->
            val files = File(root, "files")
            write(files, "user_media/photo.jpg", "original".encodeToByteArray())
            val inventory = File(root, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventory, files)
            val preferencesFile = File(root, "preferences").apply { writeText("preferences") }
            val database = File(root, "database").apply { writeText("encrypted journal") }
            val receipt = File(root, "receipt")
            writeNativeJournalSourceReceipt(
                receipt,
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                preferencesFile,
                mapOf("database/logdate" to database),
                mediaInventory = inventory,
            )

            fun payload(
                path: String,
                file: File,
            ) = NativeIncomingJournal.Payload(NATIVE_JOURNAL_TRANSFER_PREFIX + path, file, true, file.length())
            val core =
                listOf(
                    payload(NATIVE_SOURCE_RECEIPT_PATH, receipt),
                    payload("files/logdate.preferences_pb", preferencesFile),
                    payload("database/logdate", database),
                )
            val delivered = core + payload(NATIVE_MEDIA_INVENTORY_PATH, inventory)
            assertEquals(inventory, verifiedNativeJournalSources(delivered).values.single()[NATIVE_MEDIA_INVENTORY_PATH])
        }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("native-media-inventory-").toFile()
        try {
            block(root)
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun write(
        root: File,
        path: String,
        bytes: ByteArray,
    ) {
        val destination = File(root, path)
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        destination.writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
