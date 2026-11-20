package app.logdate.client.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** One unedited, bounded recording on the owned managed emulator; only its own PID is signalled. */
internal class ManagedEditorRecording(
    private val device: UiDevice,
    private val output: File,
    fileName: String = "home-pull-editor-real-time.mp4",
) : AutoCloseable {
    private val token = Uuid.random().toString()
    private val remoteVideo = "/sdcard/logdate-entry-switch-$token.mp4"
    private val remotePid = "/sdcard/logdate-entry-switch-$token.pid"
    private val executor = Executors.newSingleThreadExecutor()
    private val reader: java.util.concurrent.Future<*>
    val file = File(output, fileName)

    init {
        val script = File(output, "record-$token.sh")
        script.writeText(
            "#!/system/bin/sh\necho \$\$ > $remotePid\n" +
                "exec screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 60 $remoteVideo\n",
        )
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh ${script.absolutePath}")
        reader =
            executor.submit {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    File(output, "screenrecord.log").outputStream().use { stream -> input.copyTo(stream) }
                }
            }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (device.executeShellCommand("cat $remotePid").trim().toIntOrNull() == null) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Owned screen recorder did not start" }
            android.os.SystemClock.sleep(40)
        }
    }

    fun assertStillRecording() = check(!reader.isDone) { "Normal-speed journey exceeded the bounded recording" }

    override fun close() {
        try {
            device
                .executeShellCommand("cat $remotePid")
                .trim()
                .toIntOrNull()
                ?.let { pid ->
                    if (!reader.isDone && device.executeShellCommand("cat /proc/$pid/cmdline").contains(remoteVideo)) {
                        device.executeShellCommand("kill -2 $pid")
                    }
                }
            reader.get(35, TimeUnit.SECONDS)
            device.executeShellCommand("cp $remoteVideo ${file.absolutePath}")
            check(file.isFile && file.length() > 10_000) { "Original video was not retained" }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            device.executeShellCommand("rm -f $remoteVideo $remotePid")
        }
    }
}
