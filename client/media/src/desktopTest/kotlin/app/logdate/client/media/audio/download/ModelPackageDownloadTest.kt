package app.logdate.client.media.audio.download

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelPackageDownloadTest {
    @Test
    fun unknownLengthTransferWaitsForMoreSpaceWithoutRetransferringTheSameBytes() {
        val root = Files.createTempDirectory("chunked-model").toFile()
        val destination = root.resolve("model")
        val partial = root.resolve("model.tar.bz2.tmp")
        val transfers = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            transfers.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { it.write(ByteArray(65_536) { 1 }) }
            } catch (_: IOException) {
                exchange.close()
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/model"
            var enoughSpace = false
            val initialHeadroom = root.totalSpace * 3 / 4
            var competingWriterFilledDisk = false
            val available: (File) -> Long = {
                if (partial.length() >= 8192) competingWriterFilledDisk = true
                if (enoughSpace) {
                    initialHeadroom
                } else if (competingWriterFilledDisk) {
                    0
                } else {
                    initialHeadroom
                }
            }

            fun attempt() = runBlocking { downloadModelPackage(url, installation(destination), available).toList() }
            assertEquals(ModelDownloadStatus.OutOfStorage, attempt().last())
            assertEquals(1, transfers.get())
            assertEquals(ModelDownloadStatus.OutOfStorage, attempt().last())
            assertEquals(1, transfers.get())
            // Each call recreates its transfer state from disk, including the space requirement.
            assertEquals(ModelDownloadStatus.OutOfStorage, attempt().last())
            assertEquals(1, transfers.get())
            enoughSpace = true
            assertEquals(ModelDownloadStatus.Completed, attempt().last())
            assertEquals(2, transfers.get())
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidRevisionIsNotRetransferredAfterRestartAndAChangedRevisionHealsAutomatically() {
        val root = Files.createTempDirectory("invalid-model-revision").toFile()
        val destination = root.resolve("model")
        val transfers = AtomicInteger()
        var revision = "bad"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = revision.toByteArray()
            exchange.responseHeaders.set("ETag", revision)
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                transfers.incrementAndGet()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/model"

            fun attempt(): List<ModelDownloadStatus> {
                val install =
                    ModelPackageInstallation(
                        destination,
                        listOf("model.bin"),
                        { destination.resolve("model.bin").isFile },
                        { it.length() },
                        { archive, staging ->
                            if (archive.readText() != "valid") throw InvalidModelArchiveException("Bad fixture")
                            staging.mkdirs()
                            archive.copyTo(staging.resolve("model.bin"))
                        },
                    )
                return runBlocking { downloadModelPackage(url, install).toList() }
            }
            assertEquals(ModelDownloadStatus.ArchiveCorrupt, attempt().last())
            assertEquals(ModelDownloadStatus.AwaitingUpdate, attempt().last())
            assertEquals(2, transfers.get())
            val restarted = attempt()
            assertEquals(ModelDownloadStatus.AwaitingUpdate, restarted.last())
            assertFalse(restarted.any { it is ModelDownloadStatus.Downloading })
            assertEquals(2, transfers.get())
            revision = "valid"
            assertEquals(ModelDownloadStatus.Completed, attempt().last())
            assertEquals(3, transfers.get())
            assertTrue(destination.resolve("model.bin").isFile)
            assertFalse(root.resolve("model.transfer-state").exists())
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun correctedPackagesCanBeRetriedAfterCooldownWhenHeadIsUnsupported() {
        val root = Files.createTempDirectory("head-unsupported-model").toFile()
        val destination = root.resolve("model")
        var revision = "bad"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
            } else {
                val bytes = revision.toByteArray()
                exchange.responseHeaders.set("ETag", revision)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/model"

            fun attempt() = runBlocking { downloadModelPackage(url, installation(destination, "valid")).toList() }.last()
            assertEquals(ModelDownloadStatus.ArchiveCorrupt, attempt())
            assertEquals(ModelDownloadStatus.AwaitingUpdate, attempt())
            assertEquals(ModelDownloadStatus.AwaitingUpdate, attempt())
            val metadata = root.resolve("model.transfer-state")
            val values =
                Properties().apply {
                    metadata.inputStream().use { load(it) }
                    setProperty("lastInvalidAt", "0")
                }
            metadata.outputStream().use { values.store(it, null) }
            revision = "valid"
            assertEquals(ModelDownloadStatus.Completed, attempt())
        } finally {
            server.stop(0)
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableTransferLeftoversDoNotHideAReadyPackage() {
        val root = Files.createTempDirectory("ready-model-cleanup").toFile()
        val destination = root.resolve("model").apply { mkdirs() }
        destination.resolve("model.bin").writeText("ready")
        val blocked = root.resolve("model.installing/blocked").apply { mkdirs() }
        blocked.resolve("leftover").writeText("temporary")
        if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) {
            root.deleteRecursively()
            return
        }
        val original = Files.getPosixFilePermissions(blocked.toPath())
        try {
            Files.setPosixFilePermissions(blocked.toPath(), emptySet())
            assertTrue(checkedModelPackageReady(destination.path, listOf("model.bin")))
        } finally {
            if (blocked.exists()) Files.setPosixFilePermissions(blocked.toPath(), original)
        }
        try {
            assertTrue(checkedModelPackageReady(destination.path, listOf("model.bin")))
            assertFalse(root.resolve("model.installing").exists())
            assertTrue(destination.resolve("model.bin").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installation(
        destination: File,
        expectedBody: String? = null,
    ) = ModelPackageInstallation(
        destination,
        listOf("model.bin"),
        { destination.resolve("model.bin").isFile },
        { it.length() },
        { archive, staging ->
            if (expectedBody != null && archive.readText() != expectedBody) throw InvalidModelArchiveException("Bad fixture")
            staging.mkdirs()
            archive.copyTo(staging.resolve("model.bin"))
        },
    )
}
