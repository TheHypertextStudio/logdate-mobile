package app.logdate.client.device.restore

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRestoredPreferenceFilesTest {
    private val directory = createTempDirectory("native-preference-finalization").toFile()
    private val noBackup = File(directory, "private").apply { mkdirs() }
    private val files = File(directory, "files").apply { mkdirs() }
    private val context =
        mockk<Context>().also {
            every { it.noBackupFilesDir } returns noBackup
            every { it.filesDir } returns files
            every { it.applicationInfo } returns ApplicationInfo().apply { dataDir = File(directory, "data").path }
            every { it.getDatabasePath(any()) } answers { File(directory, firstArg<String>()) }
        }
    private val detector = PostRestoreDetector(context)
    private val retained = NativeRestoredPreferenceFiles(context)
    private val names = setOf("logdate_widget_instances")

    @AfterTest
    fun cleanup() {
        unmockkConstructor(AtomicFile::class)
        directory.deleteRecursively()
    }

    @Test
    fun `unrelated restored files do not require optional preference storage`() {
        File(noBackup, "native-restored-preferences").writeText("Obstructed optional preference directory")

        assertFalse(
            retained.restore(
                context.getDatabasePath("logdate"),
                names,
                100,
                { error("Unrelated input belongs to the caller") },
                { error("The caller owns unrelated file delivery") },
                { error("Not a preference duplicate") },
            ),
        )
    }

    @Test
    fun `truncated input stays unconfirmed and a complete retry repairs it`() {
        snapshot("pending")
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
        destination.parentFile?.mkdirs()
        val short = "<map/>".encodeToByteArray()

        assertFailsWith<IOException> {
            retained.restore(destination, names, 100, {
                short
            }, { error("Incomplete input must not reach the platform copier") }, { error("Not accepted") })
        }
        assertFailsWith<IOException> { retained.recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())

        val complete = "<map><string name=\"7.chosenNoteId\">complete-entry</string></map>".encodeToByteArray()
        assertTrue(
            retained.restore(
                destination,
                names,
                complete.size.toLong(),
                { complete },
                { destination.writeBytes(it) },
                { error("Retry") },
            ),
        )
        assertEquals(names, retained.recordFinalDelivery(names))
        assertEquals(complete.size.toLong(), destination.length())
    }

    @Test
    fun `completion without a final manifest resumes the active generation`() {
        detector.beginNativeRestoreSession(retainPreferences = true)
        val generation = assertNotNull(detector.retainedPreferenceSessionId())

        assertTrue(retained.canResumeFinalization(names))
        assertEquals(generation, detector.retainedPreferenceSessionId())
    }

    @Test
    fun `recreated completion retains omission when its optional snapshot is obstructed`() {
        val generation = beginUnobserved()
        val obstruction = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        assertTrue(obstruction.delete())
        assertTrue(obstruction.mkdir())
        val evidence = File(obstruction, "retained-evidence").apply { writeText("Preserve obstructing bytes") }

        val recreated = NativeRestoredPreferenceFiles(context)
        assertEquals(emptySet(), recreated.recordFinalDelivery(names))
        assertFailsWith<IOException> { recreated.finish(names, emptySet()) }
        detector.recordRestoreCompleted()
        assertEquals("Preserve obstructing bytes", evidence.readText())
        assertFailsWith<IOException> { recreated.read(names.single(), generation) }
        assertTrue(evidence.delete())
        assertTrue(obstruction.delete())

        assertNull(NativeRestoredPreferenceFiles(context).read(names.single(), generation))
    }

    @Test
    fun `failed initial snapshot still retains an unobserved delivery decision`() {
        val generation = snapshot("pending")
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        assertTrue(snapshot.delete())
        val obstruction = File(snapshot.path + ".new").apply { mkdir() }

        assertFailsWith<IOException> { retained.begin(names) }

        assertEquals(emptySet(), NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
        assertTrue(obstruction.isDirectory)
    }

    @Test
    fun `failed final seal retries the same omission without reading an obstructed snapshot`() {
        val generation = beginUnobserved()
        val directory = File(noBackup, "native-restored-preferences/$generation")
        val snapshot = File(directory, "logdate_widget_instances.json")
        assertTrue(snapshot.delete())
        assertTrue(snapshot.mkdir())
        val obstruction = File(directory, ".delivery.json.part").apply { mkdir() }

        assertFailsWith<IOException> { retained.recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())
        assertTrue(obstruction.delete())

        assertEquals(emptySet(), NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
        assertTrue(snapshot.isDirectory)
    }

    @Test
    fun `observed prepared input cannot become omission when its snapshot is obstructed`() {
        val generation = beginUnobserved()
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
        destination.parentFile?.mkdirs()
        val incoming = "<map><string name=\"7.chosenNoteId\">retained-entry</string></map>".encodeToByteArray()
        assertFailsWith<IOException> {
            retained.restore(destination, names, incoming.size.toLong(), { incoming }, {
                destination.writeText("Interrupted copy")
                throw IOException("Interrupted framework copy")
            }, { error("Not a duplicate") })
        }
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        val prepared = snapshot.readBytes()
        assertTrue(snapshot.delete())
        assertTrue(snapshot.mkdir())
        val evidence = File(snapshot, "retained-prepared-input").apply { writeBytes(prepared) }

        assertFailsWith<IOException> { NativeRestoredPreferenceFiles(context).recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())
        assertEquals(prepared.decodeToString(), evidence.readText())
        assertEquals("Interrupted copy", destination.readText())
        assertTrue(evidence.delete())
        assertTrue(snapshot.delete())
        snapshot.writeBytes(prepared)
        val replaying = NativeRestoredPreferenceFiles(context) { target, bytes -> target.writeBytes(bytes) }

        assertEquals(names, replaying.recordFinalDelivery(names))
        assertEquals(incoming.decodeToString(), destination.readText())
    }

    @Test
    fun `failed durable observation cannot invoke the platform copy`() {
        val generation = beginUnobserved()
        val obstruction = File(noBackup, "native-restored-preferences/$generation/.delivery.json.part").apply { mkdir() }
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
        destination.parentFile?.mkdirs()
        destination.writeText("Existing destination choice")
        val incoming = "<map/>".encodeToByteArray()

        assertFailsWith<IOException> {
            retained.restore(destination, names, incoming.size.toLong(), { incoming }, {
                destination.writeBytes(it)
            }, { error("Not a duplicate") })
        }

        assertEquals("Existing destination choice", destination.readText())
        assertTrue(obstruction.isDirectory)
        assertTrue(obstruction.delete())
        assertTrue(
            retained.restore(destination, names, incoming.size.toLong(), { incoming }, {
                destination.writeBytes(it)
            }, { error("The failed observation did not accept the first delivery") }),
        )
        var drained = false
        assertTrue(
            NativeRestoredPreferenceFiles(context).restore(destination, names, incoming.size.toLong(), {
                error("Accepted duplicate input must not replace retained bytes")
            }, { error("Accepted duplicate must not invoke the platform copy") }, { drained = true }),
        )
        assertTrue(drained)
        assertEquals(incoming.decodeToString(), destination.readText())
        assertEquals(names, NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
    }

    @Test
    fun `legacy sealed delivery remains authoritative over an unreadable snapshot`() {
        val generation = snapshot("pending")
        val directory = File(noBackup, "native-restored-preferences/$generation")
        File(directory, ".delivery.json").writeText(
            """{"generation":"$generation","names":["logdate_widget_instances"],"delivered":["logdate_widget_instances"]}""",
        )
        val snapshot = File(directory, "logdate_widget_instances.json")
        assertTrue(snapshot.delete())
        assertTrue(snapshot.mkdir())

        assertEquals(names, NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
        assertTrue(snapshot.isDirectory)
    }

    @Test
    fun `missing legacy decision cannot treat an unreadable snapshot as omission`() {
        val generation = snapshot("pending")
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        assertTrue(snapshot.delete())
        assertTrue(snapshot.mkdir())

        assertFailsWith<IOException> { NativeRestoredPreferenceFiles(context).recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())
        assertTrue(snapshot.isDirectory)
    }

    @Test
    fun `repeated begin cannot reset an observed callback into omission`() {
        val generation = beginUnobserved()
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
        assertEquals(names.single(), retained.observe(destination, names))
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        val observed = snapshot.readText()

        NativeRestoredPreferenceFiles(context).begin(names)

        assertEquals(observed, snapshot.readText())
        assertFailsWith<IOException> { retained.recordFinalDelivery(names) }
    }

    @Test
    fun `failed snapshot observation remains pending until the platform redelivers its input`() {
        for (removePendingSnapshot in listOf(false, true)) {
            val generation = beginUnobserved()
            val directory = File(noBackup, "native-restored-preferences/$generation")
            val snapshot = File(directory, "logdate_widget_instances.json")
            val pending = snapshot.readText()
            val obstruction = File(directory, "logdate_widget_instances.json.new").apply { mkdir() }
            val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
            destination.parentFile?.mkdirs()
            destination.writeText("Existing choice")
            val incoming = "<map><string name=\"7.chosenNoteId\">redelivered-entry</string></map>".encodeToByteArray()
            assertFailsWith<IOException> {
                retained.restore(destination, names, incoming.size.toLong(), {
                    error("Failed observation must not consume platform input")
                }, { error("Failed observation must not copy preference bytes") }, { error("Not a duplicate") })
            }
            assertEquals(pending, snapshot.readText())
            if (removePendingSnapshot) assertTrue(snapshot.delete())

            assertFailsWith<IOException> { NativeRestoredPreferenceFiles(context).recordFinalDelivery(names) }
            assertFalse(detector.hasCompletedNativeRestoreDelivery())
            assertEquals("Existing choice", destination.readText())
            assertTrue(obstruction.delete())
            assertTrue(
                retained.restore(destination, names, incoming.size.toLong(), { incoming }, {
                    destination.writeBytes(it)
                }, { error("The first delivery did not accept any bytes") }),
            )
            assertEquals(names, NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
            assertEquals(incoming.decodeToString(), destination.readText())
        }
    }

    @Test
    fun `repeated begin preserves prepared bytes until a recreated completion repairs them`() {
        beginUnobserved()
        val destination = File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
        destination.parentFile?.mkdirs()
        val incoming = "<map><string name=\"7.chosenNoteId\">prepared-entry</string></map>".encodeToByteArray()
        assertFailsWith<IOException> {
            retained.restore(destination, names, incoming.size.toLong(), { incoming }, {
                destination.writeText("Interrupted copy")
                throw IOException("Interrupted framework copy")
            }, { error("Not a duplicate") })
        }

        NativeRestoredPreferenceFiles(context).begin(names)
        val replaying = NativeRestoredPreferenceFiles(context) { target, bytes -> target.writeBytes(bytes) }

        assertEquals(names, replaying.recordFinalDelivery(names))
        assertEquals(incoming.decodeToString(), destination.readText())
    }

    @Test
    fun `repeated begin preserves a sealed legacy snapshot`() {
        val generation = snapshot("captured", "AA==")
        assertEquals(names, retained.recordFinalDelivery(names))
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        val captured = snapshot.readText()

        NativeRestoredPreferenceFiles(context).begin(names)

        assertEquals(captured, snapshot.readText())
        assertEquals(names, retained.recordFinalDelivery(names))
    }

    @Test
    fun `begin preserves legacy snapshot provenance when the delivery journal is missing`() {
        for (state in listOf("observed", "pending", "prepared", "delivered", "captured")) {
            val generation = snapshot(state, if (state in setOf("prepared", "delivered", "captured")) "AA==" else null)
            val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
            val original = snapshot.readText()

            NativeRestoredPreferenceFiles(context).begin(names)

            assertEquals(original, snapshot.readText(), state)
            if (state in setOf("observed", "pending")) {
                assertFailsWith<IOException> { retained.recordFinalDelivery(names) }
            } else {
                val replaying =
                    NativeRestoredPreferenceFiles(context) { target, bytes ->
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    }
                assertEquals(names, replaying.recordFinalDelivery(names), state)
            }
        }
    }

    @Test
    fun `completion recreates delivered decision from a captured snapshot without callback memory`() {
        val generation = snapshot("captured", "AA==")

        assertEquals(names, NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
        assertEquals(generation, detector.retainedPreferenceSessionId())
    }

    @Test
    fun `completion retains a successful copy whose byte snapshot could not persist`() {
        snapshot("delivered")

        assertEquals(names, NativeRestoredPreferenceFiles(context).recordFinalDelivery(names))
    }

    @Test
    fun `completion cannot publish omission for a copy with unknown outcome`() {
        snapshot("observed")

        assertFailsWith<IOException> { retained.recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())
    }

    @Test
    fun `completion replays prepared bytes without another file callback`() {
        val bytes = "<map><string name=\"7.chosenNoteId\">retained-entry</string></map>".encodeToByteArray()
        snapshot(
            "prepared",
            java.util.Base64
                .getEncoder()
                .encodeToString(bytes),
        )
        val replaying =
            NativeRestoredPreferenceFiles(context) { destination, incoming ->
                destination.parentFile?.mkdirs()
                destination.writeBytes(incoming)
            }

        assertEquals(names, replaying.recordFinalDelivery(names))
        assertEquals(bytes.decodeToString(), File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml").readText())
    }

    @Test
    fun `framework rejection retains prepared bytes and cannot complete delivery`() {
        snapshot("prepared", "AA==")
        val replaying = NativeRestoredPreferenceFiles(context) { _, _ -> }

        assertFailsWith<IOException> { replaying.recordFinalDelivery(names) }
        assertFalse(detector.hasCompletedNativeRestoreDelivery())
    }

    @Test
    fun `completed delivery cannot resume an abandoned preference generation`() {
        detector.beginNativeRestoreSession(retainPreferences = true)
        detector.recordRestoreCompleted()
        detector.markDeviceInitialized()

        assertFalse(retained.canResumeFinalization(names))
    }

    private fun beginUnobserved(): String {
        val generation = snapshot("pending")
        assertTrue(File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json").delete())
        retained.begin(names)
        return generation
    }

    private fun snapshot(
        state: String,
        source: String? = null,
    ): String {
        detector.beginNativeRestoreSession(retainPreferences = true)
        val generation = assertNotNull(detector.retainedPreferenceSessionId())
        val snapshot = File(noBackup, "native-restored-preferences/$generation/logdate_widget_instances.json")
        snapshot.parentFile?.mkdirs()
        snapshot.writeText(
            """{"generation":"$generation","state":"$state","source":${source?.let { "\"$it\"" } ?: "null"}}""",
        )
        // Host Android stubs have no AtomicFile implementation. Keep filesystem bytes and
        // production generation/decision logic real; native acceptance covers AtomicFile itself.
        mockkConstructor(AtomicFile::class)
        every { anyConstructed<AtomicFile>().baseFile } returns snapshot
        every { anyConstructed<AtomicFile>().openRead() } answers { snapshot.inputStream() }
        val temporary = File(snapshot.path + ".new")
        every { anyConstructed<AtomicFile>().startWrite() } answers { temporary.outputStream() }
        every { anyConstructed<AtomicFile>().finishWrite(any()) } answers {
            firstArg<FileOutputStream>().close()
            Files.move(temporary.toPath(), snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        every { anyConstructed<AtomicFile>().failWrite(any()) } answers {
            firstArg<FileOutputStream>().close()
            temporary.delete()
        }
        return generation
    }
}
