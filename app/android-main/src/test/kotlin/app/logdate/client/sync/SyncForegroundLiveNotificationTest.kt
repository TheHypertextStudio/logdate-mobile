package app.logdate.client.sync

import android.app.Application
import android.app.Notification
import android.content.ContextWrapper
import androidx.core.app.NotificationCompat
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.currentLiveUpdatesSwitch
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SyncForegroundLiveNotificationTest {
    private val liveUpdates =
        object : LiveUpdatesSwitch {
            override var enabled = true
        }

    @Before
    fun bindLiveUpdates() {
        mockkStatic(::currentLiveUpdatesSwitch)
        every { currentLiveUpdatesSwitch() } returns liveUpdates
    }

    @After
    fun releaseBindings() {
        unmockkStatic(::currentLiveUpdatesSwitch)
    }

    @Test
    fun `an unknown upload count keeps a check ordinary even with live updates enabled`() {
        assertOrdinaryCheck(notification(total = null))
    }

    @Test
    fun `zero uploads keep a check ordinary even with live updates enabled`() {
        assertOrdinaryCheck(notification(total = 0))
    }

    @Test
    fun `real upload progress still requests a live update`() {
        val notification = notification(total = 4, completed = 1)

        assertTrue(notification.extras.getBoolean(requestPromotionKey))
        assertEquals(progressStyle, notification.extras.getString(NotificationCompat.EXTRA_COMPAT_TEMPLATE))
        assertEquals("Backing up your memories", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `disabling live updates keeps real upload progress ordinary`() {
        liveUpdates.enabled = false

        val notification = notification(total = 4, completed = 1)

        assertFalse(notification.extras.getBoolean(requestPromotionKey))
        assertNotEquals(progressStyle, notification.extras.getString(NotificationCompat.EXTRA_COMPAT_TEMPLATE))
        assertEquals(4, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(1, notification.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    private fun assertOrdinaryCheck(notification: Notification) {
        assertFalse(notification.extras.getBoolean(requestPromotionKey), "A routine check is not a live backup")
        assertNotEquals(progressStyle, notification.extras.getString(NotificationCompat.EXTRA_COMPAT_TEMPLATE))
        assertEquals("Checking for changes", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0, "Long checks retain foreground protection")
        assertEquals(NotificationCompat.PRIORITY_LOW, notification.priority)
    }

    private fun notification(
        total: Int?,
        completed: Int? = null,
    ): Notification {
        val context = spyk(ContextWrapper(RuntimeEnvironment.getApplication()))
        // The app's host tests omit merged resources; only resource lookup is substituted.
        every { context.getString(R.string.sync_notification_starting) } returns "Checking for changes"
        every { context.getString(R.string.sync_notification_title) } returns "Backing up your memories"

        return SyncForegroundNotification.info(context, "Synthetic sync check", completed, total).notification
    }

    private val progressStyle = "androidx.core.app.NotificationCompat\$ProgressStyle"

    // AndroidX reads this request metadata even where the platform getter is unavailable.
    private val requestPromotionKey = "android.requestPromotedOngoing"
}
