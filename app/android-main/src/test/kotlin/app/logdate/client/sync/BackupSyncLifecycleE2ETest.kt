package app.logdate.client.sync

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic production worker/engine/storage/provider graph; never opens a device or socket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupSyncLifecycleE2ETest {
    @Test
    fun `policy deferred originals remain queued without a live zero of72 backup claim`() =
        runBlocking {
            BackupSyncGraphFixture().useGraph { fixture ->
                fixture.addOriginals(72)
                fixture.quotaDelayMillis = 1_700
                val retained = fixture.pendingOperations()
                val result = fixture.runWorker()
                assertTrue(result is androidx.work.ListenableWorker.Result.Success, fixture.diagnostics())
                assertEquals(retained, fixture.pendingOperations(), "Policy deferral cannot acknowledge or replace pending operations")
                assertEquals(72, fixture.notesCount())
                assertEquals(0, fixture.payloadWrites)
                assertTrue(fixture.status().pausedReason == SyncPausedReason.MEDIA_WAITING_FOR_WIFI, fixture.diagnostics())
                assertFalse(
                    fixture.notices.any { it.liveClaim() },
                    "No original is eligible, but production promoted: ${fixture.diagnostics()}",
                )
            }
        }

    @Test
    fun `a slow empty ordinary check does not request an unnecessary foreground notice`() =
        runBlocking {
            BackupSyncGraphFixture().useGraph { fixture ->
                fixture.accountDelayMillis = 11_200
                val result = fixture.runWorker()
                assertTrue(result is androidx.work.ListenableWorker.Result.Success, fixture.diagnostics())
                assertEquals(0, fixture.payloadWrites)
                assertEquals(0, fixture.pendingOperations().size)
                assertFalse(fixture.notices.any { it.liveClaim() })
                assertTrue(fixture.notices.isEmpty(), "A normal non-expedited empty check explicitly promoted: ${fixture.diagnostics()}")
            }
        }

    @Test
    @Config(sdk = [30])
    fun `a platform required foreground check stays ordinary and compliant`() =
        runBlocking {
            BackupSyncGraphFixture(expedited = true).useGraph { fixture ->
                val notice = fixture.platformForegroundInfo().notification
                assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertFalse(notice.liveClaim())
                assertEquals("Checking for changes", notice.extras.getString(Notification.EXTRA_TITLE))
                assertFalse(notice.extras.getBoolean("android.requestPromotedOngoing"))
            }
        }

    @Test
    fun `cached originals allow a metadata only update without new media on cellular`() =
        runBlocking {
            BackupSyncGraphFixture().useGraph { fixture ->
                fixture.addCachedOriginalMetadataChange()
                assertEquals(1, fixture.pendingOperations().size)
                val result = fixture.runWorker()
                assertTrue(result is androidx.work.ListenableWorker.Result.Success, fixture.diagnostics())
                assertEquals(1, fixture.payloadWrites, "An acknowledged original's metadata change must transfer on cellular")
                assertEquals(0, fixture.mediaPayloadRequests)
                assertTrue(fixture.pendingOperations().isEmpty(), "Only the acknowledged metadata operation may settle")
                assertTrue(fixture.cachedOriginalIntact())
            }
        }

    @Test
    fun `a mixed policy queue counts only the eligible transfer and retains every held original`() =
        runBlocking {
            BackupSyncGraphFixture().useGraph { fixture ->
                fixture.addOriginals(2)
                fixture.addText()
                fixture.quotaDelayMillis = 1_700
                val originals = fixture.pendingOperations().filter { it.entityId in fixture.originalIds }
                val result = fixture.runWorker()
                assertTrue(result is androidx.work.ListenableWorker.Result.Success, fixture.diagnostics())
                assertEquals(originals, fixture.pendingOperations())
                assertEquals(1, fixture.payloadWrites)
                val progress = fixture.notices.filter { it.liveClaim() }
                assertTrue(progress.isNotEmpty(), "The actual eligible text transfer must retain foreground protection")
                assertTrue(
                    progress.all {
                        it.extras
                            .getCharSequence(Notification.EXTRA_TEXT)
                            ?.toString()
                            ?.substringAfter(" of ") ==
                            "1 changes backed up"
                    },
                    "Only eligible transfers belong in the run denominator: ${fixture.diagnostics()}",
                )
                assertEquals(3, fixture.notesCount())
            }
        }
}

private fun Notification.liveClaim(): Boolean =
    extras.getBoolean("android.requestPromotedOngoing") ||
        extras.getString(Notification.EXTRA_TITLE) == "Backing up your memories"
