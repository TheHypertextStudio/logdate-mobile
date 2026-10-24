package app.logdate.tools.restoredriver

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GMD owns the device; only this nested instrumentation receives the hidden-API test exemption. */
@RunWith(AndroidJUnit4::class)
class NativeModernRestoreCallbackTest {
    @Test
    fun executesEveryModernCallbackCaseWithFrameworkFixtureAccess() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue(Build.VERSION.SDK_INT >= 36)
        assertEquals(3600001, Build.VERSION.SDK_INT_FULL)
        requireAttestedApks()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val classnames =
            setOf(
                "app.logdate.client.e2e.NativeBackgroundCredentialCallbackTest",
                "app.logdate.client.e2e.NativeModernRestoreFormatTest",
            )
        val contract = JSONObject(String(Base64.decode(checkNotNull(arguments.getString("nativeCallbackContract")), Base64.NO_WRAP)))
        assertEquals(classnames, contract.keys().asSequence().toSet())
        val cases =
            classnames.flatMap { classname ->
                contract.getJSONArray(classname).let { list -> (0 until list.length()).map { classname to list.getString(it) } }
            }
        assertEquals(18, cases.size)
        assertEquals(cases.size, cases.toSet().size)
        val command =
            "am instrument -w -r --no-hidden-api-checks -e class ${classnames.joinToString(",")} " +
                "-e nativeRestoreRequiredSdkFull 3600001 " +
                "studio.hypertext.logdate.debug.test/androidx.test.runner.AndroidJUnitRunner"
        val output = File(checkNotNull(arguments.getString("additionalTestOutputDir")))
        check(output.isDirectory || output.mkdirs())
        val user = instrumentation.targetContext.getSystemService(UserManager::class.java)
        val platform = JSONObject().put("sdkFull", Build.VERSION.SDK_INT_FULL).put("userUnlockedBeforeSuite", user.isUserUnlocked)
        val platformFile = File(output, "modern-callback-platform.json")
        platformFile.writeText(platform.toString())
        val raw =
            try {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { input ->
                    input.bufferedReader().readText()
                }
            } finally {
                platform.put("userUnlockedAfterSuite", user.isUserUnlocked)
                platformFile.writeText(platform.toString())
            }
        File(output, "modern-callback-instrumentation.log").writeText(raw)
        assertTrue(Regex("(?m)^OK \\(${cases.size} tests\\)\\r?$").containsMatchIn(raw), "The callback suite must finish successfully")
        assertFalse(Regex("(?m)^INSTRUMENTATION_STATUS_CODE: *-[0-9]+").containsMatchIn(raw))
        listOf("Process crashed", "FAILURES", "INSTRUMENTATION_FAILED:", "INSTRUMENTATION_RESULT: shortMsg=").forEach {
            assertFalse(raw.contains(it), "Nested instrumentation reported failure")
        }
        val completed = mutableListOf<Pair<String?, String?>>()
        var currentClass: String? = null
        var currentMethod: String? = null
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("INSTRUMENTATION_STATUS: class=") -> {
                    currentClass = line.substringAfter("class=").trimEnd('\r')
                }

                line.startsWith("INSTRUMENTATION_STATUS: test=") -> {
                    currentMethod = line.substringAfter("test=").trimEnd('\r')
                }

                line.startsWith("INSTRUMENTATION_STATUS_CODE:") -> {
                    if (line.substringAfter(':').trim() == "0") completed += currentClass to currentMethod
                    currentClass = null
                    currentMethod = null
                }
            }
        }
        assertEquals(
            cases.sortedBy { "${it.first}#${it.second}" },
            completed.sortedBy { "${it.first}#${it.second}" },
        )
    }
}
