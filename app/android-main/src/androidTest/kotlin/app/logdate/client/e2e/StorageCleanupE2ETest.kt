package app.logdate.client.e2e

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.account.CloudPlanBilling
import app.logdate.client.domain.account.CloudPlanOverview
import app.logdate.client.domain.account.CloudPlanProblem
import app.logdate.client.domain.account.CloudPlanResult
import app.logdate.client.domain.storage.StoredKind
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.account.plan.cleanup.STORAGE_CLEANUP_BAR_TAG
import app.logdate.feature.core.settings.account.plan.cleanup.STORAGE_CLEANUP_CONFIRM_TAG
import app.logdate.feature.core.settings.account.plan.cleanup.STORAGE_CLEANUP_DELETE_TAG
import app.logdate.feature.core.settings.account.plan.cleanup.STORAGE_CLEANUP_RESULT_TAG
import app.logdate.feature.core.settings.account.plan.cleanup.storageCleanupItemTag
import app.logdate.feature.core.settings.navigation.CloudPlanRoute
import app.logdate.feature.core.settings.navigation.StorageCleanupRoute
import app.logdate.feature.core.settings.ui.STORAGE_USAGE_TOGGLE_TAG
import app.logdate.feature.core.settings.ui.storageActionTag
import app.logdate.feature.core.settings.ui.storageCategoryTag
import app.logdate.navigation.LogDateNavDisplay
import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.CloudStorageCategoryUsage
import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The production navigation with the Storage screen and the clean-up page it opens, over real
 * entries and media files on the device. Only LogDate Cloud's answers are supplied here, since a
 * debug build cannot sign in: the plan the account is on and how much of each kind it stores.
 */
@RunWith(AndroidJUnit4::class)
class StorageCleanupE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var pending by mutableStateOf<NavKey?>(null)
    private var current by mutableStateOf<NavKey?>(null)

    private val koin get() = GlobalContext.get()
    private val flags get() = koin.get<FeatureFlagStore>()
    private val notes get() = koin.get<JournalNotesRepository>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val appContext get() = ApplicationProvider.getApplicationContext<Context>()
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private val mediaDirectory by lazy { File(appContext.filesDir, "storage-cleanup-e2e").apply { mkdirs() } }
    private val seeded = mutableListOf<JournalNote>()
    private var plansWereOn = false
    private var cleanUpWasOn = false

    private lateinit var photos: List<JournalNote.Image>
    private lateinit var videos: List<JournalNote.Video>
    private lateinit var recordings: List<JournalNote.Audio>
    private lateinit var entries: List<JournalNote.Text>

    private val cloud =
        module {
            single<CloudPlanBilling> { PlanOnDevice(usedBytes = seeded.sumOf(::bytesOf)) }
            single<CloudQuotaManager> { UsageOnDevice(usage()) }
        }

    @Before
    fun seedALibrary() =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an Android emulator" }
            plansWereOn = flags.isEnabled(FeatureFlag.CLOUD_PLANS)
            cleanUpWasOn = flags.isEnabled(FeatureFlag.STORAGE_CLEANUP)
            flags.setEnabled(FeatureFlag.CLOUD_PLANS, true)

            val now = Clock.System.now()
            photos =
                samplePhotos().mapIndexed {
                    index,
                    file,
                    ->
                    JournalNote.Image(creationTimestamp = now - (index + 1).days, lastUpdated = now, mediaRef = file.absolutePath)
                }
            videos =
                listOf("storage/garden-walk.mp4" to 3_000L, "recovery/proof.mp4" to 1_000L).mapIndexed { index, (asset, length) ->
                    JournalNote.Video(
                        creationTimestamp = now - (index + 10).days,
                        lastUpdated = now,
                        mediaRef = copyAsset(asset).absolutePath,
                        durationMs = length,
                    )
                }
            recordings =
                listOf(
                    Triple("recovery/proof.wav", 9_000L, "Okay so we finally made it up to the lookout"),
                    Triple("parity/completed-recording.wav", 6_000L, "Voice memo for Dad’s birthday, take two"),
                    Triple("recovery/completed-recording.m4a", 3_000L, ""),
                ).mapIndexed { index, (asset, length, said) ->
                    JournalNote.Audio(
                        mediaRef = copyAsset(asset).absolutePath,
                        durationMs = length,
                        creationTimestamp = now - (index + 20).days,
                        lastUpdated = now,
                        transcription = said,
                    )
                }
            entries =
                listOf(
                    "The house was quiet when I got back, and for once I didn’t mind. ".repeat(40),
                    "Notes from the long weekend at the lake. ".repeat(20),
                    "A list of small good things",
                ).mapIndexed {
                    index,
                    words,
                    ->
                    JournalNote.Text(creationTimestamp = now - (index + 30).days, lastUpdated = now, content = words)
                }
            (photos + videos + recordings + entries).forEach { note ->
                notes.create(note)
                seeded += note
            }
            loadKoinModules(cloud)
        }

    @After
    fun removeTheLibrary() =
        runBlocking {
            unloadKoinModules(cloud)
            seeded.forEach { note -> if (notes.getNoteById(note.uid) != null) notes.removeById(note.uid) }
            mediaDirectory.deleteRecursively()
            flags.setEnabled(FeatureFlag.CLOUD_PLANS, plansWereOn)
            flags.setEnabled(FeatureFlag.STORAGE_CLEANUP, cleanUpWasOn)
        }

    @Test
    fun storageOpensTheCleanUpPageAndDeletingRemovesWhatWasChosen() =
        runBlocking {
            flags.setEnabled(FeatureFlag.STORAGE_CLEANUP, true)
            showApp()
            open(CloudPlanRoute)

            // The Storage card: open its list, choose photos, and take the button it offers.
            val photosRow = kindsLargestFirst().indexOf(CloudObjectType.IMAGE_NOTES)
            waitForTag(STORAGE_USAGE_TOGGLE_TAG)
            tap(STORAGE_USAGE_TOGGLE_TAG)
            tap(storageCategoryTag(photosRow))
            waitForTag(storageActionTag(photosRow))
            compose.onNodeWithText("Open Library").assertDoesNotExist()
            compose.onNodeWithText("Clean up photos").assertIsDisplayed()
            capture("storage-photos-chosen")
            tap(storageActionTag(photosRow))
            until { current == StorageCleanupRoute(StoredKind.Photos) }

            // The page lists the photos on this device, with pictures read from their files.
            photos.forEach { waitForTag(storageCleanupItemTag(it.uid)) }
            compose.onNodeWithText("${photos.size} photos, largest first").assertIsDisplayed()
            capture("cleanup-photos", settle = true)

            val chosen = photos.sortedByDescending(::bytesOf).take(2)
            chosen.forEach { tap(storageCleanupItemTag(it.uid)) }
            chosen.forEach { compose.onNodeWithTag(storageCleanupItemTag(it.uid)).assertIsOn() }
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            capture("cleanup-photos-chosen", settle = true)

            tap(STORAGE_CLEANUP_DELETE_TAG)
            compose.onNodeWithText("Delete 2 photos?").assertIsDisplayed()
            capture("cleanup-confirm", settle = true)
            tap(STORAGE_CLEANUP_CONFIRM_TAG)

            // They are gone from the page and the library, and their deletion is queued for the server.
            until { runBlocking { chosen.all { notes.getNoteById(it.uid) == null } } }
            waitForTag(STORAGE_CLEANUP_RESULT_TAG)
            capture("cleanup-freed")
            chosen.forEach { compose.onNodeWithTag(storageCleanupItemTag(it.uid)).assertDoesNotExist() }
            compose.onNodeWithText("${photos.size - 2} photos, largest first").assertIsDisplayed()
            val queued = koin.get<SyncMetadataService>().getPendingUploads(EntityType.NOTE)
            chosen.forEach { photo ->
                assertEquals(
                    PendingOperation.DELETE,
                    queued.lastOrNull { it.entityId == photo.uid.toString() }?.operation,
                    "The deletion of ${photo.uid} is not queued for the server",
                )
            }
            (photos - chosen.toSet()).forEach { kept ->
                assertTrue(notes.getNoteById(kept.uid) != null, "A photo that was not chosen is gone")
            }

            // The other kinds, from the same library.
            open(StorageCleanupRoute(StoredKind.Videos))
            videos.forEach { waitForTag(storageCleanupItemTag(it.uid)) }
            capture("cleanup-videos", settle = true)

            open(StorageCleanupRoute(StoredKind.Recordings))
            recordings.forEach { waitForTag(storageCleanupItemTag(it.uid)) }
            tap(storageCleanupItemTag(recordings.first().uid))
            waitForTag(STORAGE_CLEANUP_BAR_TAG)
            capture("cleanup-recordings")

            open(StorageCleanupRoute(StoredKind.WrittenEntries))
            entries.forEach { waitForTag(storageCleanupItemTag(it.uid)) }
            capture("cleanup-entries")
        }

    @Test
    fun withCleanUpOffStorageOpensTheLibraryAsBefore() =
        runBlocking {
            flags.setEnabled(FeatureFlag.STORAGE_CLEANUP, false)
            showApp()
            open(CloudPlanRoute)

            val photosRow = kindsLargestFirst().indexOf(CloudObjectType.IMAGE_NOTES)
            waitForTag(STORAGE_USAGE_TOGGLE_TAG)
            tap(STORAGE_USAGE_TOGGLE_TAG)
            tap(storageCategoryTag(photosRow))
            waitForTag(storageActionTag(photosRow))

            compose.onNodeWithText("Clean up photos").assertDoesNotExist()
            compose.onNodeWithText("Open Library").assertIsDisplayed()
            assertNull(seeded.firstOrNull { runBlocking { notes.getNoteById(it.uid) } == null }, "Nothing is deleted by looking")
        }

    private fun showApp() {
        // The Storage screen's badge never stops turning, so the test moves time itself.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LogDateTheme(dynamicColor = false) {
                LogDateNavDisplay(
                    appUiState = GlobalAppUiLoadedState(isLoaded = true, isOnboarded = true),
                    onShowUnlockPrompt = {},
                    pendingNavKey = pending,
                    onPendingNavKeyConsumed = { pending = null },
                    onCurrentNavKeyChanged = { current = it },
                )
            }
        }
        until { current == HomeRoute }
    }

    private fun open(route: NavKey) {
        compose.runOnIdle { pending = route }
        until { pending == null && current == route }
    }

    /** Waits for [tag], touches it, and lets what the touch starts play out. */
    private fun tap(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag).performClick()
        frames()
    }

    private fun waitForTag(tag: String) = until { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Moves time forward a little at a time until [condition] holds. */
    private fun until(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MILLIS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Still waiting after $WAIT_MILLIS ms on $current" }
            frames()
            SystemClock.sleep(POLL_MILLIS)
        }
        frames()
    }

    private fun frames(count: Int = SETTLE_FRAMES) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    /** Saves what is on the screen. [settle] gives pictures a moment to load from their files. */
    private fun capture(
        name: String,
        settle: Boolean = false,
    ) {
        if (settle) SystemClock.sleep(PICTURE_SETTLE_MILLIS)
        frames()
        compose.waitForIdle()
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }

    /** Six photographs of different sizes: crops of the sample photo, saved at different qualities. */
    private fun samplePhotos(): List<File> {
        val source = appContext.assets.open("sample_note_photo.jpg").use(BitmapFactory::decodeStream)
        val crops =
            listOf(
                Crop(0.00f, 0.00f, 1.00f, 1.00f, quality = 100),
                Crop(0.30f, 0.02f, 0.40f, 0.55f, quality = 95),
                Crop(0.00f, 0.30f, 0.45f, 0.60f, quality = 92),
                Crop(0.20f, 0.40f, 0.60f, 0.55f, quality = 88),
                Crop(0.60f, 0.20f, 0.40f, 0.60f, quality = 80),
                Crop(0.40f, 0.60f, 0.35f, 0.35f, quality = 70),
            )
        return crops.mapIndexed { index, crop ->
            val picture =
                Bitmap.createBitmap(
                    source,
                    (source.width * crop.left).toInt(),
                    (source.height * crop.top).toInt(),
                    (source.width * crop.width).toInt(),
                    (source.height * crop.height).toInt(),
                )
            File(mediaDirectory, "photo-$index-${Uuid.random()}.jpg").also { file ->
                file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, crop.quality, it) }
            }
        }
    }

    private class Crop(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val quality: Int,
    )

    private fun copyAsset(asset: String): File =
        File(mediaDirectory, "${Uuid.random()}-${asset.substringAfterLast('/')}").also { file ->
            testAssets.open(asset).use { source -> file.outputStream().use(source::copyTo) }
        }

    private fun bytesOf(note: JournalNote): Long =
        when (note) {
            is JournalNote.Image -> {
                File(note.mediaRef).length()
            }

            is JournalNote.Video -> {
                File(note.mediaRef).length()
            }

            is JournalNote.Audio -> {
                File(note.mediaRef).length()
            }

            is JournalNote.Text -> {
                note.content
                    .encodeToByteArray()
                    .size
                    .toLong()
            }

            is JournalNote.Handwriting -> {
                0L
            }
        }

    /** What the account stores of each kind: exactly what was put on this device. */
    private fun usage(): CloudStorageQuota {
        val categories =
            listOf(
                CloudStorageCategoryUsage(CloudObjectType.IMAGE_NOTES, photos.sumOf(::bytesOf), photos.size),
                CloudStorageCategoryUsage(CloudObjectType.VIDEO_NOTES, videos.sumOf(::bytesOf), videos.size),
                CloudStorageCategoryUsage(CloudObjectType.VOICE_NOTES, recordings.sumOf(::bytesOf), recordings.size),
                CloudStorageCategoryUsage(CloudObjectType.TEXT_NOTES, entries.sumOf(::bytesOf), entries.size),
            )
        return CloudStorageQuota(totalBytes = INCLUDED_BYTES, usedBytes = categories.sumOf { it.sizeBytes }, categories = categories)
    }

    /** The order the Storage card lists the kinds in. */
    private fun kindsLargestFirst(): List<CloudObjectType> = usage().categories.sortedByDescending { it.sizeBytes }.map { it.category }

    /** A beta member on the base plan, storing what is on this device. */
    private class PlanOnDevice(
        usedBytes: Long,
    ) : CloudPlanBilling {
        private val plan =
            CloudPlanResult.Done(
                CloudPlanOverview(
                    subscription =
                        CloudSubscription(
                            planId = "cloud",
                            status = EntitlementStatusWire.ACTIVE,
                            includedWithBeta = true,
                            betaMember = true,
                            freePlanId = "cloud",
                            storageUsedBytes = usedBytes,
                            storageIncludedBytes = INCLUDED_BYTES,
                            purchasesAvailable = false,
                            paymentDetailsAvailable = false,
                        ),
                    plans = emptyList(),
                ),
            )
        private val nothingChanges = CloudPlanResult.Failed(CloudPlanProblem.NoBilling)

        override suspend fun overview() = plan

        override suspend fun refresh() = plan

        override suspend fun startCheckout(selection: CloudPlanSelection) = nothingChanges

        override suspend fun changePlan(selection: CloudPlanSelection) = nothingChanges

        override suspend fun cancel() = nothingChanges

        override suspend fun keep() = nothingChanges

        override suspend fun paymentDetails() = nothingChanges
    }

    private class UsageOnDevice(
        private val usage: CloudStorageQuota,
    ) : CloudQuotaManager {
        override suspend fun getCurrentQuota() = usage

        override fun observeQuota(): Flow<CloudStorageQuota> = flowOf(usage)

        override suspend fun recordObjectCreation(
            objectType: CloudObjectType,
            bytes: Long,
        ) = Unit

        override suspend fun recordObjectDeletion(
            objectType: CloudObjectType,
            bytes: Long,
        ) = Unit

        override suspend fun recordObjectUpdate(
            objectType: CloudObjectType,
            oldBytes: Long,
            newBytes: Long,
        ) = Unit

        override suspend fun recalculateQuota() = usage

        override suspend fun setQuotaLimit(totalBytes: Long) = Unit

        override suspend fun syncWithServer() = usage

        override suspend fun getLastServerSyncTime(): Instant? = null
    }

    private companion object {
        const val INCLUDED_BYTES = 50L * 1024 * 1024 * 1024
        const val PICTURE_SETTLE_MILLIS = 1_500L
        const val WAIT_MILLIS = 20_000L
        const val POLL_MILLIS = 50L
        const val SETTLE_FRAMES = 30
    }
}
