package app.logdate

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Tests [SpeechModelDownloader]: a model is bundled only when its archive matches the expected
 * SHA-256, and a failed download never leaves a file at the asset's path.
 */
class SpeechModelDownloaderTest {
    private val root: File = createTempDirectory("speech-models").toFile()
    private val sources = root.resolve("sources").apply { mkdirs() }
    private val output = root.resolve("assets").apply { mkdirs() }
    private val downloader = SpeechModelDownloader(root.resolve("work"), connectTimeoutMillis = 1_000, readTimeoutMillis = 1_000)

    @Test
    fun `a bare model whose checksum matches is wrapped in the asset zip`() {
        val model = sources.resolve("vad.onnx").apply { writeText("weights") }
        val asset = output.resolve("vad.zip")

        downloader.fetch(SpeechModel(model.toURI().toString(), asset.name, sha256(model)), asset)

        ZipFile(asset).use { zip ->
            assertEquals(listOf("vad.onnx"), zip.entries().toList().map { it.name })
            assertEquals("weights", zip.getInputStream(zip.getEntry("vad.onnx")).readBytes().decodeToString())
        }
    }

    @Test
    fun `an archive is repackaged without its top folder or excluded files`() {
        val archive = tarBz2("model.tar.bz2", "pkg/" to null, "pkg/encoder.int8.onnx" to "int8", "pkg/README.md" to "docs")
        val asset = output.resolve("stt.zip")

        downloader.fetch(SpeechModel(archive.toURI().toString(), asset.name, sha256(archive), listOf("README.md")), asset)

        ZipFile(asset).use { zip -> assertEquals(listOf("encoder.int8.onnx"), zip.entries().toList().map { it.name }) }
    }

    @Test
    fun `a checksum mismatch fails and leaves no asset behind`() {
        val model = sources.resolve("vad.onnx").apply { writeText("truncated") }
        val asset = output.resolve("vad.zip")

        val failure =
            assertFailsWith<IOException> {
                downloader.fetch(SpeechModel(model.toURI().toString(), asset.name, "0".repeat(64)), asset)
            }

        assertContains(failure.message.orEmpty(), "SHA-256")
        assertFalse(asset.exists())
        assertEquals(emptyList(), output.listFiles()!!.map { it.name })
    }

    @Test
    fun `a failed download keeps the asset a previous build produced`() {
        val asset = output.resolve("vad.zip").apply { writeText("previous asset") }
        val missing = sources.resolve("missing.onnx")

        assertFailsWith<IOException> {
            downloader.fetch(SpeechModel(missing.toURI().toString(), asset.name, "0".repeat(64)), asset)
        }

        assertEquals("previous asset", asset.readText())
        assertEquals(listOf("vad.zip"), output.listFiles()!!.map { it.name })
    }

    private fun tarBz2(
        name: String,
        vararg entries: Pair<String, String?>,
    ): File {
        val file = sources.resolve(name)
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            for ((path, content) in entries) {
                val bytes = content?.toByteArray() ?: ByteArray(0)
                tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
