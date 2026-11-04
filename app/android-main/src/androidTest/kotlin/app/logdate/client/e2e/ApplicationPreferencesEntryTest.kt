package app.logdate.client.e2e

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.MainActivity
import app.logdate.client.resolveMainActivityNavKey
import app.logdate.feature.core.settings.navigation.SettingsRoute
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ApplicationPreferencesEntryTest {
    @Test
    fun applicationPreferencesResolvesToExportedSettingsEntry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(context.packageName)
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

        assertNotNull(resolved)
        assertEquals(MainActivity::class.java.name, resolved.activityInfo.name)
        assertTrue(resolved.activityInfo.exported)
        assertEquals(SettingsRoute(), resolveMainActivityNavKey(intent))
    }
}
