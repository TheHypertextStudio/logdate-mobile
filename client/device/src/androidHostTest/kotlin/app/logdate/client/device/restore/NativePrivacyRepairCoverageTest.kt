package app.logdate.client.device.restore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePrivacyRepairCoverageTest {
    @Test
    fun `exact protected inputs and authorized preference bytes are covered`() =
        fixture { f ->
            assertTrue(f.covered())
        }

    @Test
    fun `older complete WAL prefix is represented by the exact accepted WAL`() =
        fixture { f ->
            f.acceptWal("header-frame1-frame2")
            f.add(WAL, "header-frame1")
            assertTrue(f.covered())
        }

    @Test
    fun `different nonprefix and longer WAL revisions remain uncovered`() {
        for (other in listOf("different", "header-frame1-frame2-unaccepted")) {
            fixture { f ->
                f.acceptWal("header-frame1-frame2")
                f.add(WAL, other)
                assertFalse(f.covered())
            }
        }
    }

    @Test
    fun `missing or wrong accepted WAL anchor cannot cover a prefix`() =
        fixture { f ->
            f.add(WAL, "header-frame1")
            f.protected[WAL] = sha("header-frame1-frame2")
            assertFalse(f.covered())
            f.protected[WAL] = sha("wrong anchor")
            assertFalse(f.covered())
            f.payloads.removeAll { it.path == WAL }
            assertFalse(f.covered())
        }

    @Test
    fun `corrupt database key or draft bytes cannot be covered`() {
        for (path in listOf(DB, KEY, DRAFT)) {
            fixture { f ->
                f.payloads
                    .single { it.path == path }
                    .file
                    .apply { writeText("X" + readText().drop(1)) }
                assertFalse(f.covered())
            }
        }
    }

    @Test
    fun `partial payload and expected size mismatch refuse coverage`() =
        fixture { f ->
            val item = f.payloads.first()
            f.payloads[0] = item.copy(complete = false)
            assertFalse(f.covered())
            f.payloads[0] = item.copy(expectedSize = item.expectedSize + 1)
            assertFalse(f.covered())
        }

    @Test
    fun `different preference revision is not represented by the authorized policy`() =
        fixture { f ->
            f.add(PREFERENCES, "other known policy")
            assertFalse(f.covered())
        }

    @Test
    fun `repeated identical preference bytes are one exact revision`() =
        fixture { f ->
            f.add(PREFERENCES, "authorized typed preferences")
            assertTrue(f.covered())
        }

    @Test
    fun `preference anchor cannot be absent wrong or malformed`() =
        fixture { f ->
            assertFalse(f.covered(sha("different preferences")))
            assertFalse(f.covered("not-a-sha"))
            f.payloads.removeAll { it.path == PREFERENCES }
            assertFalse(f.covered())
        }

    @Test
    fun `only exact session SDK metadata and default app heartbeat names are covered`() =
        fixture { f ->
            for (name in listOf("sessionDataStore", "sessionConfigsDataStore")) {
                for (suffix in listOf(".data", ".data.lock", ".data.version")) {
                    f.add("files/datastore/firebaseSessions/$name$suffix", "SDK runtime metadata")
                }
            }
            f.add("files/datastore/FirebaseHeartBeatW0RFRkFVTFRd.preferences_pb", "legacy heartbeat")
            f.add("files/datastore/FirebaseHeartBeatW0RFRkFVTFRd+YXBwbGljYXRpb24tX2lk.preferences_pb", "heartbeat")
            assertTrue(f.covered())
        }

    @Test
    fun `unknown original draft SDK neighbor and heartbeat suffix remain uncovered`() {
        for (path in listOf(
            "files/audio_notes/original.m4a",
            "files/media/objects/original.jpg",
            "files/datastore/other_drafts.preferences_pb",
            "files/datastore/firebaseSessions/user.preferences_pb",
            "files/datastore/firebaseSessions/sessionDataStore.data.extra",
            "files/datastore/firebaseSessions/sessionDataStore.data.tmp",
            "files/datastore/FirebaseHeartBeatUnknown.preferences_pb",
            "files/datastore/FirebaseHeartBeatW0RFRkFVTFRd+.preferences_pb",
            "files/datastore/FirebaseHeartBeatW0RFRkFVTFRd+YWJj=.preferences_pb",
            "files/datastore/FirebaseHeartBeatW0RFRkFVTFRd+YWJj.preferences_pb.extra",
            "database/logdate-shm",
        )) {
            fixture { f ->
                f.add(path, "unproven bytes")
                assertFalse(f.covered(), path)
            }
        }
    }

    @Test
    fun `directory symlink and missing file payloads are refused`() {
        for (kind in listOf("directory", "symlink", "missing")) {
            fixture { f ->
                val item = f.payloads.first()
                item.file.delete()
                when (kind) {
                    "directory" -> {
                        item.file.mkdir()
                    }

                    "symlink" -> {
                        Files.createSymbolicLink(
                            item.file.toPath(),
                            f.payloads
                                .last()
                                .file
                                .toPath(),
                        )
                    }
                }
                assertFalse(f.covered(), kind)
            }
        }
    }

    @Test
    fun `empty input and unknown protected hash cannot claim coverage`() =
        fixture { f ->
            assertFalse(nativePrivacyRepairCohortCovered(emptyList(), f.protected, f.preferencesHash))
            f.protected["files/audio_notes/original.m4a"] = sha("unproven bytes")
            f.add("files/audio_notes/original.m4a", "unproven bytes")
            assertFalse(f.covered())
        }

    @Test
    fun `WAL prefix comparison checks every buffer including the final partial buffer`() =
        fixture { f ->
            val full = "a".repeat(8192) + "b".repeat(8192) + "accepted end"
            f.acceptWal(full)
            f.add(WAL, full.dropLast(4))
            assertTrue(f.covered())
            val old = f.payloads.last().file
            val contents = old.readText()
            old.writeText(contents.take(8192) + "X" + contents.drop(8193))
            assertFalse(f.covered())
        }

    @Test
    fun `missing protected input and unrecognized database file remain uncovered`() =
        fixture { f ->
            val missing = f.payloads.removeAt(0)
            assertFalse(f.covered())
            f.payloads.add(0, missing)
            f.add("database/unknown", "unknown database")
            f.protected["database/unknown"] = sha("unknown database")
            assertFalse(f.covered())
        }

    private class Fixture(
        val root: File,
    ) {
        val payloads = mutableListOf<NativeIncomingJournal.Payload>()
        val protected = mutableMapOf<String, String>()
        val preferencesHash = sha("authorized typed preferences")

        init {
            for ((path, contents) in listOf(DB to "accepted database", KEY to "portable key", DRAFT to "unfinished draft")) {
                add(path, contents)
                protected[path] = sha(contents)
            }
            add(PREFERENCES, "authorized typed preferences")
        }

        fun add(
            path: String,
            contents: String,
        ) {
            val file = File(root, "payload-${payloads.size}").apply { writeText(contents) }
            payloads += NativeIncomingJournal.Payload(path, file, true, file.length(), "same-cohort")
        }

        fun acceptWal(contents: String) {
            add(WAL, contents)
            protected[WAL] = sha(contents)
        }

        fun covered(preferencesSha256: String = preferencesHash): Boolean {
            val before = regularBytes()
            return nativePrivacyRepairCohortCovered(payloads, protected, preferencesSha256).also {
                assertEquals(before, regularBytes(), "Coverage must never change source bytes")
            }
        }

        private fun regularBytes() =
            root
                .listFiles()
                .orEmpty()
                .filter { Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .associate { it.name to sha(it.readBytes()) }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("privacy-coverage").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val DB = "database/logdate"
        const val WAL = "database/logdate-wal"
        const val KEY = "files/db_recovery/passphrase_backup"
        const val DRAFT = "files/datastore/entry_drafts.preferences_pb"
        const val PREFERENCES = "files/logdate.preferences_pb"

        fun sha(value: String) = sha(value.encodeToByteArray())

        fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    }
}
