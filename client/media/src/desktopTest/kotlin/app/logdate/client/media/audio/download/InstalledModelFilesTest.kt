package app.logdate.client.media.audio.download

import app.logdate.client.media.audio.sherpa.DesktopSherpaModelManager
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstalledModelFilesTest {
    @Test
    fun linkedLegacyModelsRemainUsableWithoutAnotherDownload() =
        withDirectory { root ->
            val external = root.resolve("external").apply { mkdir() }
            external.resolve("model.onnx").writeText("complete model")
            val linked = root.resolve("linked")
            Files.createSymbolicLink(linked.toPath(), external.toPath())
            assertTrue(checkedModelFilesReady(linked.path, listOf("model.onnx")))
        }

    @Test
    fun replacingAnIncompleteLinkedModelPreservesExternalFiles() =
        withDirectory { root ->
            val external = root.resolve("external").apply { mkdir() }
            val sentinel = external.resolve("keep.txt").apply { writeText("keep this file") }
            val installed = root.resolve("installed")
            Files.createSymbolicLink(installed.toPath(), external.toPath())
            val staging = root.resolve("staging").apply { mkdir() }
            staging.resolve("model.onnx").writeText("complete model")
            publishModelFiles(staging.path, installed.path, listOf("model.onnx"))
            assertTrue(sentinel.exists())
            assertTrue(checkedModelFilesReady(installed.path, listOf("model.onnx")))
        }

    @Test
    fun onlyReadableRegularNonemptyFilesAreReady() =
        withDirectory { root ->
            val names = listOf("encoder.onnx", "tokens.txt")
            assertFalse(checkedModelFilesReady(root.path, names))
            root.resolve("encoder.onnx").writeText("model")
            root.resolve("tokens.txt").writeText("tokens")
            assertTrue(checkedModelFilesReady(root.path, names))
            root.resolve("encoder.onnx").writeText("")
            assertFalse(checkedModelFilesReady(root.path, names))
            root.resolve("encoder.onnx").delete()
            root.resolve("encoder.onnx").mkdir()
            assertFalse(checkedModelFilesReady(root.path, names))
        }

    @Test
    fun unreadableFilesAreInspectionFailuresInsteadOfMissingModels() =
        withDirectory { root ->
            val file = root.resolve("model.onnx").apply { writeText("model") }
            if (!Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) return@withDirectory
            val original = Files.getPosixFilePermissions(file.toPath())
            try {
                Files.setPosixFilePermissions(file.toPath(), emptySet<PosixFilePermission>())
                assertFailsWith<IOException> { checkedModelFilesReady(root.path, listOf(file.name)) }
            } finally {
                Files.setPosixFilePermissions(file.toPath(), original)
            }
        }

    @Test
    fun stagingFilesAreNotInstalledUntilValidationAndPublication() =
        withDirectory { root ->
            val staging = root.resolve("staging").apply { mkdir() }
            val installed = root.resolve("installed")
            val names = listOf("model.onnx")
            staging.resolve(names.single()).writeText("model")
            assertFalse(checkedModelFilesReady(installed.path, names))
            publishModelFiles(staging.path, installed.path, names)
            assertTrue(checkedModelFilesReady(installed.path, names))
            assertFalse(staging.exists())
        }

    @Test
    fun incompleteStagingCannotReplaceAnInstalledModel() =
        withDirectory { root ->
            val staging = root.resolve("staging").apply { mkdir() }
            val installed = root.resolve("installed").apply { mkdir() }
            installed.resolve("model.onnx").writeText("complete model")
            assertFailsWith<IOException> { publishModelFiles(staging.path, installed.path, listOf("model.onnx")) }
            assertTrue(checkedModelFilesReady(installed.path, listOf("model.onnx")))
        }

    @Test
    fun desktopTranscriptionNeedsBothWhisperAndSpeechDetectionFiles() =
        withDirectory { root ->
            val whisper = root.resolve(DesktopSherpaModelManager.WHISPER_MODEL_DIR_NAME).apply { mkdir() }
            listOf(
                DesktopSherpaModelManager.WHISPER_ENCODER_NAME,
                DesktopSherpaModelManager.WHISPER_DECODER_NAME,
                DesktopSherpaModelManager.WHISPER_TOKENS_NAME,
            ).forEach { whisper.resolve(it).writeText("model content") }
            val manager = DesktopSherpaModelManager(root)
            assertFalse(manager.isWhisperModelReady())
            root.resolve("silero_vad.onnx").writeText("speech detection model")
            assertTrue(manager.isWhisperModelReady())
        }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("logdate-model-files").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
