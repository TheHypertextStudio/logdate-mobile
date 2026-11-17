package app.logdate.client.e2e

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.EditorActivity
import app.logdate.client.MainActivity
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Instrumented E2E tests for handling incoming Android share intents.
 *
 * This suite verifies that the application correctly responds to `ACTION_SEND` and
 * `ACTION_SEND_MULTIPLE` intents from other apps, pre-filling the journal editor
 * with shared text, single images, or multiple media attachments.
 */
@RunWith(AndroidJUnit4::class)
class IncomingShareE2ETest {
    private val fakeMediaManager = RecordingMediaManager()

    private val testModule =
        module {
            single<MediaManager> { fakeMediaManager }
        }

    private val koinRule = IncomingShareKoinOverrideRule(testModule)

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule)

    @Before
    fun resetState() {
        fakeMediaManager.reset()
    }

    @Test
    fun `action send text launches editor with prefilled text`() {
        val sharedText = "Shared from Android text intent"
        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                },
            )

        assertNotNull(editorIntent)
        assertEquals(sharedText, editorIntent.getStringExtra("initial_text"))
        assertTrue(editorIntent.getStringArrayListExtra("attachments").isNullOrEmpty())
        assertTrue(fakeMediaManager.savedRequests.isEmpty())
    }

    @Test
    fun `action send single image imports attachment and launches editor`() {
        val imageUri = createShareableUri(fileName = "single-share.jpg", contents = byteArrayOf(1, 2, 3))

        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )

        assertNotNull(editorIntent)
        assertNull(editorIntent.getStringExtra("initial_text"))
        assertEquals(1, fakeMediaManager.savedRequests.size)
        val attachments = editorIntent.getStringArrayListExtra("attachments") ?: emptyList()
        assertEquals(
            listOf(fakeMediaManager.savedRequests.single().returnedUri),
            attachments,
        )
        assertEquals("image/jpeg", fakeMediaManager.savedRequests.single().mimeType)
        assertTrue(
            fakeMediaManager.savedRequests
                .single()
                .fileName
                .endsWith(".jpg"),
        )
    }

    @Test
    fun `action send single video imports attachment and launches editor`() {
        val videoUri = createShareableUri(fileName = "single-share.mp4", contents = byteArrayOf(1, 2, 3, 4))

        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )

        assertNotNull(editorIntent)
        assertNull(editorIntent.getStringExtra("initial_text"))
        assertEquals(1, fakeMediaManager.savedRequests.size)
        val attachments = editorIntent.getStringArrayListExtra("attachments") ?: emptyList()
        assertEquals(
            listOf(fakeMediaManager.savedRequests.single().returnedUri),
            attachments,
        )
        assertEquals("video/mp4", fakeMediaManager.savedRequests.single().mimeType)
        assertTrue(
            fakeMediaManager.savedRequests
                .single()
                .fileName
                .endsWith(".mp4"),
        )
    }

    @Test
    fun `action send text and image preserves both in editor launch`() {
        val imageUri = createShareableUri(fileName = "combo-share.png", contents = byteArrayOf(4, 5, 6))
        val sharedText = "Photo plus note from Android share"

        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "image/png"
                    putExtra(Intent.EXTRA_TEXT, sharedText)
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )

        assertNotNull(editorIntent)
        assertEquals(sharedText, editorIntent.getStringExtra("initial_text"))
        assertEquals(1, fakeMediaManager.savedRequests.size)
        val attachments = editorIntent.getStringArrayListExtra("attachments") ?: emptyList()
        assertEquals(
            listOf(fakeMediaManager.savedRequests.single().returnedUri),
            attachments,
        )
    }

    @Test
    fun `action send multiple imports every image into single draft`() {
        val firstImage = createShareableUri(fileName = "multi-one.jpg", contents = byteArrayOf(7, 8, 9))
        val secondImage = createShareableUri(fileName = "multi-two.jpg", contents = byteArrayOf(10, 11, 12))

        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(firstImage, secondImage))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )

        assertNotNull(editorIntent)
        assertEquals(2, fakeMediaManager.savedRequests.size)
        val attachments = editorIntent.getStringArrayListExtra("attachments") ?: emptyList()
        assertEquals(
            fakeMediaManager.savedRequests.map { it.returnedUri },
            attachments,
        )
    }

    @Test
    fun `unsupported share does not launch editor`() {
        val pdfUri = createShareableUri(fileName = "ignored.pdf", contents = byteArrayOf(13, 14, 15))

        val editorIntent =
            launchShareIntentAndCaptureEditorIntent(
                Intent(mainContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, pdfUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                timeoutMillis = 3_000,
            )

        assertNull(editorIntent)
        assertTrue(fakeMediaManager.savedRequests.isEmpty())
    }

    private fun launchShareIntentAndCaptureEditorIntent(
        intent: Intent,
        timeoutMillis: Long = 10_000,
    ): Intent? {
        val application = mainContext as android.app.Application
        val launchLatch = CountDownLatch(1)
        var launchedEditorIntent: Intent? = null
        val lifecycleCallbacks =
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity is EditorActivity) {
                        launchedEditorIntent = Intent(activity.intent)
                        launchLatch.countDown()
                        activity.finish()
                    }
                }

                override fun onActivityStarted(activity: android.app.Activity) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivityStopped(activity: android.app.Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)

        return try {
            ActivityScenario.launch<MainActivity>(intent).use {
                launchLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
                launchedEditorIntent
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        }
    }

    private fun createShareableUri(
        fileName: String,
        contents: ByteArray,
    ): Uri {
        val shareDir = File(mainContext.cacheDir, "androidTestShare").apply { mkdirs() }
        val shareFile = File(shareDir, fileName).apply { writeBytes(contents) }
        val uri = FileProvider.getUriForFile(mainContext, "${mainContext.packageName}.provider", shareFile)
        mainContext.grantUriPermission(mainContext.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    private val mainContext: Context
        get() = ApplicationProvider.getApplicationContext()
}

private data class SavedMediaRequest(
    val sourceFilePath: String,
    val fileName: String,
    val mimeType: String,
    val returnedUri: String,
)

private class RecordingMediaManager : MediaManager {
    private var saveCount = 0
    val savedRequests = mutableListOf<SavedMediaRequest>()

    fun reset() {
        saveCount = 0
        savedRequests.clear()
    }

    override suspend fun getMedia(uri: String): MediaObject = error("Not needed for IncomingShareE2ETest")

    override suspend fun deleteOwnedMedia(uri: String): Boolean = false

    override suspend fun exists(mediaId: String): Boolean = false

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun addToDefaultCollection(uri: String) {
    }

    override suspend fun readMedia(uri: String): MediaPayload = error("Not needed for IncomingShareE2ETest")

    override suspend fun saveMedia(payload: MediaPayload): String = error("Not needed for IncomingShareE2ETest")

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String {
        saveCount += 1
        val returnedUri = "content://androidTest/imported/$saveCount/$fileName"
        savedRequests +=
            SavedMediaRequest(
                sourceFilePath = sourceFilePath,
                fileName = fileName,
                mimeType = mimeType,
                returnedUri = returnedUri,
            )
        return returnedUri
    }
}

private class IncomingShareKoinOverrideRule(
    private val module: Module,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                startKoinIfAppHasNot(context)
                loadKoinModules(module)
                base.evaluate()
            }
        }
}
