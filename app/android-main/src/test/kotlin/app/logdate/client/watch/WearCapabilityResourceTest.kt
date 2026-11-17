package app.logdate.client.watch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Google Play services reads `android_wear_capabilities` by name, so nothing in code references it and
 * the release resource shrinker deletes it unless a keep file names it. Without it a watch cannot tell
 * LogDate is on this phone, which build 0.1.0-main.1634 shipped.
 */
class WearCapabilityResourceTest {
    @Test
    fun `the phone advertises LogDate to watches`() {
        val declared = File("src/main/res/values/wear.xml").readText()
        assertTrue("<item>logdate_phone_app</item>" in declared, "wear.xml no longer declares logdate_phone_app")
    }

    @Test
    fun `release builds keep the capability array`() {
        val keep = File("src/main/res/raw/keep.xml").readText()
        assertTrue("@array/android_wear_capabilities" in keep, "keep.xml must list @array/android_wear_capabilities")
    }
}
