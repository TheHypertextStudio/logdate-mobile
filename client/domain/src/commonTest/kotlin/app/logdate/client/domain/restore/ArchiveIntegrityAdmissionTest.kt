package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.archive.ArchiveContent
import app.logdate.client.domain.export.archive.ArchiveJson
import app.logdate.client.domain.export.archive.ArchiveManifest
import app.logdate.client.domain.export.archive.ArchiveMediaFile
import app.logdate.client.domain.export.archive.ArchiveMediaInventoryEntry
import app.logdate.client.domain.export.archive.ArchivePath
import app.logdate.client.domain.export.archive.ArchiveRole
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArchiveIntegrityAdmissionTest {
    @Test
    fun `both released v2 versions admit exact bytes at root and under one wrapper`() {
        for (version in listOf(ExportSchemaVersion.V2_0, ExportSchemaVersion.V2_1)) {
            for (prefix in listOf("", "Wrapped/")) {
                val files = archiveIntegrityFixture(version).mapKeys { prefix + it.key }
                val archive = assertIs<RestoreArchiveBundle.V2>(RestoreArchiveReader.readBytes(files.keys, files::get))
                assertEquals(prefix, archive.root)
                assertEquals("{\"journals\":[]}", archive.bundle.journalsJson)
            }
        }
    }

    @Test
    fun `parseable changed metadata is refused before the caller can publish it`() {
        for (path in listOf("manifest.json", "data/journals.json", "data/media.json")) {
            val files = archiveIntegrityFixture()
            files[path] =
                files
                    .getValue(path)
                    .decodeToString()
                    .replace("LogDate", "Changed")
                    .let {
                        if (it == files.getValue(path).decodeToString()) "$it " else it
                    }.encodeToByteArray()
            var admitted = false
            assertFailsWith<IllegalArgumentException> {
                RestoreArchiveReader.readBytes(files.keys, files::get)
                admitted = true
            }
            assertEquals(false, admitted)
        }
    }

    @Test
    fun `missing malformed duplicate and uncovered checksum records are refused`() {
        for (defect in listOf("missing", "malformed", "duplicate", "uncovered")) {
            val files = archiveIntegrityFixture()
            when (defect) {
                "missing" -> {
                    files.remove("SHA256SUMS")
                }

                "malformed" -> {
                    files["SHA256SUMS"] = "not-a-hash  manifest.json\n".encodeToByteArray()
                }

                "duplicate" -> {
                    files["SHA256SUMS"] = (files.getValue("SHA256SUMS").decodeToString().repeat(2)).encodeToByteArray()
                }

                "uncovered" -> {
                    files["SHA256SUMS"] =
                        files
                            .getValue("SHA256SUMS")
                            .decodeToString()
                            .lineSequence()
                            .filterNot { it.endsWith("  data/journals.json") }
                            .joinToString("\n")
                            .encodeToByteArray()
                }
            }
            assertFailsWith<RuntimeException> { RestoreArchiveReader.readBytes(files.keys, files::get) }
        }
    }

    @Test
    fun `inventory digest must agree with checksum manifest and inventory paths must be unique`() {
        for (duplicate in listOf(false, true)) {
            val files = archiveIntegrityFixture()
            val entry =
                ArchiveMediaInventoryEntry(
                    ArchivePath.of("media/original.bin"),
                    "application/octet-stream",
                    3,
                    if (duplicate) "abc".encodeToByteArray().toByteString().sha256().hex() else "0".repeat(64),
                )
            files["data/media.json"] =
                ArchiveJson.document
                    .encodeToString(
                        ArchiveMediaFile.serializer(),
                        ArchiveMediaFile(if (duplicate) listOf(entry, entry) else listOf(entry)),
                    ).encodeToByteArray()
            sealArchiveIntegrityFixture(files)
            assertFailsWith<IllegalArgumentException> { RestoreArchiveReader.readBytes(files.keys, files::get) }
        }
    }

    @Test
    fun `duplicate archive names are refused instead of selecting an ambiguous entry`() {
        val files = archiveIntegrityFixture()
        assertFailsWith<IllegalArgumentException> {
            RestoreArchiveReader.readBytes(files.keys.toList() + "data/journals.json", files::get)
        }
    }

    @Test
    fun `invalid UTF8 metadata and checksum text are permanent archive errors`() {
        for (path in listOf("manifest.json", "data/journals.json", "SHA256SUMS")) {
            val files = archiveIntegrityFixture()
            files[path] = byteArrayOf(0xff.toByte())
            if (path != "SHA256SUMS") sealArchiveIntegrityFixture(files)
            assertFailsWith<IllegalArgumentException> { RestoreArchiveReader.readBytes(files.keys, files::get) }
        }
    }

    @Test
    fun `an absent original keeps verified metadata admissible for partial recovery`() {
        val files = archiveIntegrityFixture()
        files.remove("media/original.bin")
        assertIs<RestoreArchiveBundle.V2>(RestoreArchiveReader.readBytes(files.keys, files::get))
    }

    @Test
    fun `text compatibility entrypoint still verifies every supplied metadata document`() {
        val files = archiveIntegrityFixture().mapValues { it.value.decodeToString() }.toMutableMap()
        files["data/journals.json"] = "{\"journals\":[]} "
        assertFailsWith<IllegalArgumentException> { RestoreArchiveReader.read(files.keys, files::get) }
    }

    @Test
    fun `manifest cannot omit checksum role or repeat a role or path`() {
        for (defect in listOf("missing-role", "duplicate-role", "duplicate-path")) {
            val files = archiveIntegrityFixture()
            val manifest =
                ArchiveJson.document.decodeFromString(
                    ArchiveManifest.serializer(),
                    files.getValue("manifest.json").decodeToString(),
                )
            val changed =
                when (defect) {
                    "missing-role" -> manifest.contents.filterNot { it.role == ArchiveRole.CHECKSUMS }
                    "duplicate-role" -> manifest.contents + manifest.contents.first()
                    else -> manifest.contents + ArchiveContent(ArchiveRole.NOTES, manifest.contents.first().path, "application/json")
                }
            files["manifest.json"] =
                ArchiveJson.document
                    .encodeToString(ArchiveManifest.serializer(), manifest.copy(contents = changed))
                    .encodeToByteArray()
            sealArchiveIntegrityFixture(files)
            assertFailsWith<IllegalArgumentException> { RestoreArchiveReader.readBytes(files.keys, files::get) }
        }
    }

    @Test
    fun `legacy v1 remains readable without inventing integrity evidence`() {
        val files =
            mapOf(
                "metadata.json" to "metadata",
                "journals.json" to "journals",
                "notes.json" to "notes",
                "journal_notes.json" to "links",
                "drafts.json" to "drafts",
            ).mapValues { it.value.encodeToByteArray() }
        assertIs<RestoreArchiveBundle.V1>(RestoreArchiveReader.readBytes(files.keys, files::get))
    }
}
