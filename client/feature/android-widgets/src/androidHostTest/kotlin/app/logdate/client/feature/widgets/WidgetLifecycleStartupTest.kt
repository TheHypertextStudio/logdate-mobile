package app.logdate.client.feature.widgets

import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class WidgetLifecycleStartupTest {
    @Test
    fun `startup returns while durable inbox access is blocked off the caller thread`() {
        val root = Files.createTempDirectory("widget-startup-dispatch").toFile()
        val event = widgetRefreshEvent(Uuid.random().toString())
        val inbox = WidgetLifecycleInbox(File(root, "widget-lifecycle"))
        inbox.append(event)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val diskThread = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getNoBackupFilesDir(): File {
                    diskThread.set(Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "The test did not release the inbox" }
                    return root
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val job = resumeWidgetLifecycle(context, scope)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(job.isCompleted, "Startup must return before the durable read finishes")
            assertNotEquals(caller, diskThread.get(), "Inbox IO must not run on the startup caller")
            release.countDown()
            runBlocking { withTimeout(5_000) { job.join() } }
            assertEquals(event, inbox.pending().single().event, "Scheduling must retain the original event until worker admission")
            assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isNotEmpty())
        } finally {
            release.countDown()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed startup replay retains the event for the next application retry`() {
        val root = Files.createTempDirectory("widget-startup-retry").toFile()
        val event = widgetRefreshEvent(Uuid.random().toString())
        val inbox = WidgetLifecycleInbox(File(root, "widget-lifecycle"))
        inbox.append(event)
        val base = RuntimeEnvironment.getApplication()
        val unavailable =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = throw IOException("Storage temporarily unavailable")
            }
        val restored =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = root
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            runBlocking { withTimeout(5_000) { resumeWidgetLifecycle(unavailable, scope).join() } }
            assertEquals(event, WidgetLifecycleInbox(File(root, "widget-lifecycle")).pending().single().event)
            assertTrue(base.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
            runBlocking { withTimeout(5_000) { resumeWidgetLifecycle(restored, scope).join() } }
            assertEquals(event, inbox.pending().single().event)
            assertTrue(base.getSystemService(JobScheduler::class.java).allPendingJobs.isNotEmpty())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
