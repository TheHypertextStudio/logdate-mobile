package app.logdate.client

import android.content.Intent
import android.net.Uri
import app.logdate.feature.core.settings.navigation.SettingsRoute
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ApplicationPreferencesIntentTest {
    @Test
    fun `application preferences opens settings`() {
        assertEquals(
            SettingsRoute(),
            resolveMainActivityNavKey(Intent(Intent.ACTION_APPLICATION_PREFERENCES)),
        )
    }

    @Test
    fun `application preferences with package data opens settings`() {
        assertEquals(
            SettingsRoute(),
            resolveMainActivityNavKey(
                Intent(Intent.ACTION_APPLICATION_PREFERENCES, Uri.parse("package:studio.hypertext.logdate")),
            ),
        )
    }

    @Test
    fun `launcher intent keeps the default destination`() {
        assertNull(
            resolveMainActivityNavKey(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)),
        )
    }

    @Test
    fun `missing intent keeps the default destination`() {
        assertNull(resolveMainActivityNavKey(null))
    }
}
