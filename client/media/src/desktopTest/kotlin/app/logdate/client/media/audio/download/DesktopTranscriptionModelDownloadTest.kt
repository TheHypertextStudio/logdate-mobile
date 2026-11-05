package app.logdate.client.media.audio.download

import app.logdate.client.media.audio.sherpa.DesktopSherpaModelManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTranscriptionModelDownloadTest {
    @Test
    fun cleanInstallationAcquiresWhisperAndSpeechDetectionBeforeReportingReady() {
        withServer(whisperArchive()) { root, manager, _ ->
            assertFalse(manager.isWhisperModelReady())
            val statuses =
                runBlocking {
                    manager
                        .downloadWhisperModel()
                        .onEach {
                            if (it == ModelDownloadStatus.Completed) assertTrue(manager.isWhisperModelReady())
                        }.toList()
                }
            assertEquals(ModelDownloadStatus.Completed, statuses.last())
            assertEquals(1, statuses.count { it == ModelDownloadStatus.Completed })
            assertTrue(manager.isWhisperModelReady())
            assertTrue(root.resolve("silero-vad/silero_vad.onnx").isFile)
            assertFalse(root.resolve("${DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME}.installing").exists())
        }
    }

    @Test
    fun truncatedArchiveNeverBecomesAnInstalledModel() {
        val archive = whisperArchive()
        withServer(archive.copyOf(archive.size / 2)) { _, manager, _ ->
            val statuses = runBlocking { manager.downloadWhisperModel().toList() }
            assertEquals(ModelDownloadStatus.ArchiveCorrupt, statuses.last())
            assertFalse(statuses.contains(ModelDownloadStatus.Completed))
            assertFalse(manager.isWhisperModelReady())
        }
    }

    @Test
    fun failedDownloadDoesNotRemoveTheExistingDestinationOrLinkedFiles() {
        withServer(byteArrayOf(1, 2, 3)) { root, manager, _ ->
            val external = root.resolve("external").apply { mkdir() }
            val sentinel = external.resolve("keep.txt").apply { writeText("keep this file") }
            val destination = root.resolve(DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME)
            Files.createSymbolicLink(destination.toPath(), external.toPath())
            val statuses = runBlocking { manager.downloadWhisperModel().toList() }
            assertEquals(ModelDownloadStatus.ArchiveCorrupt, statuses.last())
            assertTrue(sentinel.exists())
            assertTrue(Files.isSymbolicLink(destination.toPath()))
        }
    }

    @Test
    fun aShortHttpBodyIsATemporaryConnectionFailure() {
        withServer(whisperArchive(), announcedExtraBytes = 1_000) { _, manager, _ ->
            val statuses = runBlocking { manager.downloadWhisperModel().toList() }
            assertEquals(ModelDownloadStatus.NoNetwork, statuses.last())
        }
    }

    @Test
    fun connectionRefusalRemainsATemporaryConnectionFailure() {
        val root = Files.createTempDirectory("logdate-refused-download").toFile()
        try {
            val port = ServerSocket(0).use { it.localPort }
            val manager = DesktopSherpaModelManager(root, "http://127.0.0.1:$port/whisper")
            assertEquals(ModelDownloadStatus.NoNetwork, runBlocking { manager.downloadWhisperModel().toList() }.last())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun waitingForUnpackingSpaceReusesTheCompleteArchiveEvenAfterRestart() {
        var freeBytes = 1_024L
        val transfers = AtomicInteger()
        withServer(whisperArchive(contentSize = 100_000), { freeBytes }, { transfers.incrementAndGet() }) { root, manager, origin ->
            root.resolve("silero-vad").mkdirs()
            root.resolve("silero-vad/silero_vad.onnx").writeText("existing speech detection")
            assertEquals(ModelDownloadStatus.OutOfStorage, runBlocking { manager.downloadWhisperModel().toList() }.last())
            assertEquals(1, transfers.get())
            assertEquals(ModelDownloadStatus.OutOfStorage, runBlocking { manager.downloadWhisperModel().toList() }.last())
            assertEquals(1, transfers.get())
            val restarted = DesktopSherpaModelManager(root, "$origin/whisper", "$origin/vad", { freeBytes })
            assertEquals(ModelDownloadStatus.OutOfStorage, runBlocking { restarted.downloadWhisperModel().toList() }.last())
            assertEquals(1, transfers.get())
            freeBytes = 1_000_000
            assertEquals(ModelDownloadStatus.Completed, runBlocking { restarted.downloadWhisperModel().toList() }.last())
            assertEquals(1, transfers.get())
            assertTrue(restarted.isWhisperModelReady())
            assertFalse(root.resolve("${DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME}.downloaded").exists())
        }
    }

    @Test
    fun startupReconcilesCachedPackagesLeftAfterAnInstalledDirectoryWasPublished() {
        withServer(whisperArchive()) { root, manager, _ ->
            assertEquals(ModelDownloadStatus.Completed, runBlocking { manager.downloadWhisperModel().toList() }.last())
            for (name in listOf(DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME, "silero-vad")) {
                root.resolve("$name.downloaded").writeText("leftover archive")
                root.resolve("$name.transfer-state").writeText("leftover state")
            }
            assertTrue(manager.checkWhisperModelReady())
            for (name in listOf(DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME, "silero-vad")) {
                assertFalse(root.resolve("$name.downloaded").exists())
                assertFalse(root.resolve("$name.transfer-state").exists())
            }
            assertTrue(manager.checkWhisperModelReady())
        }
    }

    private fun whisperArchive(contentSize: Int = 0): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(output)).use { tar ->
            for (name in listOf(
                DesktopSherpaModelManager.WHISPER_ENCODER_NAME,
                DesktopSherpaModelManager.WHISPER_DECODER_NAME,
                DesktopSherpaModelManager.WHISPER_TOKENS_NAME,
            )) {
                val contents = if (contentSize > 0) ByteArray(contentSize) { 1 } else "test model contents for $name".toByteArray()
                val entry = TarArchiveEntry("whisper/$name").apply { size = contents.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(contents)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun withServer(
        archive: ByteArray,
        availableBytes: (File) -> Long = { it.usableSpace },
        onWhisperRequest: () -> Unit = {},
        announcedExtraBytes: Long = 0,
        block: (File, DesktopSherpaModelManager, String) -> Unit,
    ) {
        val directory = Files.createTempDirectory("logdate-transcription-install").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val whisper = exchange.requestURI.path == "/whisper"
            if (whisper) onWhisperRequest()
            val bytes = if (whisper) archive else "speech detection model".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong() + if (whisper) announcedExtraBytes else 0)
            try {
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                exchange.close()
            }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            block(directory, DesktopSherpaModelManager(directory, "$origin/whisper", "$origin/vad", availableBytes), origin)
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
