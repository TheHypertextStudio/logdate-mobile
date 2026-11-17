package app.logdate.client.data.restore

import app.logdate.client.device.restore.NativeIncomingJournal
import app.logdate.client.device.restore.NativeJournalMediaInventory
import app.logdate.client.media.storage.UnavailableMediaReferences
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLegacyLateMediaCandidatesTest {
    private val packageName = "studio.hypertext.logdate.debug"

    @Test
    fun `pending recording staging is admitted only with its authenticated inventory entry`() =
        fixture { root ->
            val pending = payload(root, "files/audio_notes/staging/pending.m4a", "recording")
            val reference = "file:///data/user/0/$packageName/files/audio_notes/staging/pending.m4a"

            assertEquals(
                listOf(pending.path),
                selectNativeLegacyLateMediaCandidates(listOf(pending), setOf(reference), packageName, inventory(pending))
                    .map { it.logicalPath },
            )
            assertTrue(
                selectNativeLegacyLateMediaCandidates(listOf(pending), setOf(reference), packageName, inventory())
                    .isEmpty(),
            )
        }

    @Test
    fun `draft library staging is admitted only with its authenticated inventory entry`() =
        fixture { root ->
            val pending = payload(root, "files/media/staging/draft.jpg", "image")
            val reference = "logdate-media://library/staging/draft.jpg"

            assertEquals(
                listOf(pending.path),
                selectNativeLegacyLateMediaCandidates(listOf(pending), setOf(reference), packageName, inventory(pending))
                    .map { it.logicalPath },
            )
            assertTrue(
                selectNativeLegacyLateMediaCandidates(listOf(pending), setOf(reference), packageName, inventory())
                    .isEmpty(),
            )
        }

    @Test
    fun `selects only complete legacy bytes named by current references`() =
        fixture { root ->
            val image = payload(root, "files/user_media/older image.png", "image")
            val audio = payload(root, "files/audio_notes/voice.m4a", "audio")
            val orphan = payload(root, "files/user_media/orphan.png", "orphan")
            val selected =
                selectNativeLegacyLateMediaCandidates(
                    listOf(image, audio, orphan),
                    setOf(
                        "file:///data/user/0/studio.hypertext.logdate/files/user_media/older%20image.png",
                        UnavailableMediaReferences.nativeRestore("logdate-media://recordings/voice.m4a"),
                    ),
                    packageName,
                    inventory(image, audio, orphan),
                )
            assertEquals(setOf(image.path, audio.path), selected.mapTo(mutableSetOf()) { it.logicalPath })
            assertEquals(setOf("image", "audio"), selected.map { it.file.readText() }.toSet())
            assertTrue(selected.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        }

    @Test
    fun `old and current package paths work but foreign or unsafe paths do not`() =
        fixture { root ->
            val media = payload(root, "files/user_media/photo.jpg", "image")
            val paths =
                setOf(
                    "/data/data/co.reasonabletech.logdate/files/user_media/photo.jpg",
                    "file:/data/user_de/10/$packageName/files/user_media/photo.jpg",
                )
            assertEquals(
                1,
                selectNativeLegacyLateMediaCandidates(listOf(media), paths, packageName, inventory(media)).size,
            )
            for (
            reference in
            listOf(
                "file:///data/user/0/other.app/files/user_media/photo.jpg",
                "file:///data/user/0/$packageName/files/user_media/../photo.jpg",
                "content://provider/user_media/photo.jpg",
                "file:///tmp/$packageName/files/user_media/photo.jpg",
                "photo.jpg",
                "file:///data/user/0/$packageName/files/audio_notes/staging/photo.jpg",
                "file:///data/user/0/$packageName/files/user_media/photo.jpg.part",
            )
            ) {
                assertTrue(
                    selectNativeLegacyLateMediaCandidates(listOf(media), setOf(reference), packageName, inventory(media)).isEmpty(),
                )
            }
        }

    @Test
    fun `ambiguous revisions partial files and symlinks remain in custody`() =
        fixture { root ->
            val path = "files/audio_notes/voice.m4a"
            val reference = setOf("logdate-media://recordings/voice.m4a")
            val first = payload(root, path, "first")
            val other = payload(root, path, "other")
            val source = inventory(first)
            assertEquals(
                first.file,
                selectNativeLegacyLateMediaCandidates(
                    listOf(other, first.copy(complete = false), first),
                    reference,
                    packageName,
                    source,
                ).single().file,
            )
            assertTrue(
                selectNativeLegacyLateMediaCandidates(
                    listOf(other),
                    reference,
                    packageName,
                    source,
                ).isEmpty(),
            )
            assertTrue(
                selectNativeLegacyLateMediaCandidates(
                    listOf(first.copy(expectedSize = first.expectedSize + 1)),
                    reference,
                    packageName,
                    source,
                ).isEmpty(),
            )
            val link = File(root, "link").toPath()
            Files.createSymbolicLink(link, first.file.toPath())
            assertTrue(
                selectNativeLegacyLateMediaCandidates(
                    listOf(first.copy(file = link.toFile())),
                    reference,
                    packageName,
                    source,
                ).isEmpty(),
            )
        }

    @Test
    fun `percent encoded local path selects the source authenticated alternate filename`() =
        fixture { root ->
            val literal = payload(root, "files/user_media/a%20b.jpg", "literal")
            val decoded = payload(root, "files/user_media/a b.jpg", "decoded")
            val reference = "file:///data/user/0/$packageName/files/user_media/a%20b.jpg"
            val selected =
                selectNativeLegacyLateMediaCandidates(
                    listOf(decoded, literal),
                    setOf(reference),
                    packageName,
                    inventory(literal),
                )
            assertEquals(listOf(literal.path), selected.map { it.logicalPath })
        }

    @Test
    fun `zero byte referenced source is authenticated rather than silently dropped`() =
        fixture { root ->
            val empty = payload(root, "files/user_media/empty.jpg", "")
            val reference = "file:///data/user/0/$packageName/files/user_media/empty.jpg"
            val selected =
                selectNativeLegacyLateMediaCandidates(listOf(empty), setOf(reference), packageName, inventory(empty))
            assertEquals(0L, selected.single().file.length())
            assertEquals(sha256(byteArrayOf()), selected.single().sha256)
            assertTrue(
                selectNativeLegacyLateMediaCandidates(
                    listOf(empty.copy(expectedSize = -1)),
                    setOf(reference),
                    packageName,
                    inventory(empty),
                ).isEmpty(),
            )
        }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("legacy-late-media-").toFile()
        try {
            block(root)
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun payload(
        root: File,
        path: String,
        value: String,
    ): NativeIncomingJournal.Payload {
        val file = File(root, "payload-${root.listFiles().orEmpty().size}").apply { writeText(value) }
        return NativeIncomingJournal.Payload(path, file, complete = true, expectedSize = file.length())
    }

    private fun inventory(vararg payloads: NativeIncomingJournal.Payload): NativeJournalMediaInventory =
        NativeJournalMediaInventory(
            1,
            payloads
                .map { payload ->
                    NativeJournalMediaInventory.Entry(payload.path, payload.expectedSize, sha256(payload.file.readBytes()))
                }.sortedBy(NativeJournalMediaInventory.Entry::path),
        )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
