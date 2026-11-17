package app.logdate.wear.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Google Play services reads `android_wear_capabilities` by name, so nothing in code references it and a
 * resource shrinker deletes it unless a keep file names it. Without it the phone cannot tell LogDate is
 * on this watch and offers to install it again.
 */
class WearCapabilityResourceTest {
    @Test
    fun `the watch advertises LogDate to the phone`() {
        val declared = File("src/main/res/values/wear.xml").readText()
        assertTrue("<item>logdate_watch_app</item>" in declared, "wear.xml no longer declares logdate_watch_app")
    }

    @Test
    fun `shrunk builds keep the capability array`() {
        val keep = File("src/main/res/raw/keep.xml").readText()
        assertTrue("@array/android_wear_capabilities" in keep, "keep.xml must list @array/android_wear_capabilities")
    }
}
