package app.logdate.client.e2e.osintegration

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.work.ForegroundInfo
import app.logdate.client.ambient.AmbientPromptNotificationCoordinator
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.domain.assistants.DeviceAssistantAccessStore
import app.logdate.client.domain.notes.AddNoteUseCase
import app.logdate.client.domain.recommendation.AmbientCaptureNudgeStyle
import app.logdate.client.domain.recommendation.AmbientPromptCandidate
import app.logdate.client.domain.recommendation.AmbientPromptFamily
import app.logdate.client.domain.recommendation.AmbientPromptPayload
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.media.AndroidManagedMediaDiscarder
import app.logdate.client.media.AndroidManagedMediaImportSource
import app.logdate.client.media.ManagedMediaImporter
import app.logdate.client.media.MediaManager
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.regex.Pattern
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Captures real Android system UI (launcher, shade, notifications, bubbles) for the OS integration
 * screenshot lane.
 *
 * Compose screenshot tests cannot see these surfaces because Android draws them, so they are
 * captured on a Gradle Managed Device instead. SystemUI demo mode pins the clock, battery, and signal
 * so captures of the same state look the same from run to run.
 *
 * Images are written to the managed device's additional test output as
 * `os-integration/<surface>/<state>--<form factor>-api<level>.png`; `./run screenshots:os` copies
 * them into `docs/screenshots/android-os-integration/`.
 */
object OsSurfaceCapture {
    const val APP_LABEL = "LogDate"
    private const val TIMEOUT_MS = 10_000L
    private const val RECORDING_NOTIFICATION_ID = 1001
    private const val DEMO_MODE_ATTEMPTS = 10
    private const val RETURN_HOME_STEPS = 6
    private const val LONG_PRESS_HOLD_MS = 1_000L
    private const val TOUCH_FRAME_MS = 16L
    private const val DROP_SETTLE_MS = 600L
    private const val HOVER_STEPS = 10
    private const val HOVER_STEP_PX = 8
    private const val HOVER_FRAME_MS = 16L
    private const val SPLASH_WAIT_MS = 5_000L

    /** `FLAG_ACTIVITY_NEW_TASK` with `FLAG_ACTIVITY_CLEAR_TASK`, so the activity starts over. */
    private const val FLAGS_NEW_CLEARED_TASK = "0x10008000"

    val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /**
     * `phone` below a 600dp smallest width, `tablet` at or above it. Read from the device's display
     * rather than the app's window, which is smaller than the screen in desktop windowing.
     */
    val formFactor: String
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val display = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val smallestWidthDp = minOf(display.width(), display.height()) / context.resources.displayMetrics.density
            return if (smallestWidthDp >= 600) "tablet" else "phone"
        }

    private val outputRoot: File
        get() {
            val path =
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: error("OS surface captures run on a Gradle Managed Device, which supplies additionalTestOutputDir")
            return File(path, "os-integration")
        }

    /** Runs [command] through the instrumentation's shell and returns its output. */
    fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    /**
     * Runs [script] in a device shell, passed on standard input, so arguments with spaces or JSON
     * survive intact. [shell] splits its command on whitespace and cannot do that.
     */
    fun shellScript(script: String): String {
        val (output, input) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).bufferedWriter().use { it.write(script + "\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    /** Pins the status bar to 9:41, full battery, and full signal, with no notification icons. */
    fun enterDemoMode() {
        shell("settings put global sysui_demo_allowed 1")
        // SystemUI reads that setting asynchronously and ignores demo commands until it has, which a
        // freshly booted device hits, so enter is repeated until SystemUI reports demo mode on.
        for (attempt in 1..DEMO_MODE_ATTEMPTS) {
            demo("enter")
            if (shell("settings get global sysui_tuner_demo_on").trim() == "1") break
            Thread.sleep(500L)
        }
        demo("clock -e hhmm 0941")
        demo("battery -e level 100 -e plugged false")
        demo("network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4")
        demo("notifications -e visible false")
    }

    fun exitDemoMode() {
        demo("exit")
    }

    private fun demo(command: String) {
        val parts = command.split(' ', limit = 2)
        val extras = parts.getOrNull(1).orEmpty()
        shell("am broadcast -a com.android.systemui.demo -e command ${parts[0]} $extras")
    }

    /** Saves the whole screen as the capture for [surface] in [state] and returns the file. */
    fun capture(
        surface: String,
        state: String,
    ): File {
        device.waitForIdle()
        val file = File(outputRoot, "$surface/$state--$formFactor-api${Build.VERSION.SDK_INT}.png")
        file.parentFile?.mkdirs()
        check(device.takeScreenshot(file)) { "Screenshot failed for $surface/$state" }
        return file
    }

    /** Saves [image] as [surface] in [state], named like the screen captures. */
    fun saveImage(
        surface: String,
        state: String,
        image: Bitmap,
    ): File {
        val file = File(outputRoot, "$surface/$state--$formFactor-api${Build.VERSION.SDK_INT}.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * Starts LogDate's main screen in a new task and saves the first frame that shows the splash
     * screen, recognized by its [background] color. The splash is up for a fraction of a second, so
     * frames are grabbed in a tight loop instead of after waiting for the screen to settle.
     */
    fun captureSplash(
        surface: String,
        state: String,
        background: Int,
    ): File {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        shell("am start -n $packageName/app.logdate.client.MainActivity -f $FLAGS_NEW_CLEARED_TASK")
        val deadline = SystemClock.uptimeMillis() + SPLASH_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = automation.takeScreenshot() ?: continue
            if (frame.getPixel(frame.width / 2, frame.height / 5) != background) continue
            return saveImage(surface, state, frame)
        }
        error("The splash screen did not appear within ${SPLASH_WAIT_MS}ms")
    }

    /** Swipes up through a list until [text] is fully on screen, then centers it a little. */
    fun scrollUntilText(
        text: String,
        maxSwipes: Int = 8,
    ) {
        val width = device.displayWidth
        val height = device.displayHeight
        repeat(maxSwipes) {
            val found = device.findObject(By.text(text))
            if (found != null && found.visibleBounds.bottom < height * 3 / 4) return
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 20)
            device.waitForIdle()
        }
        waitForText(text)
    }

    /** Saves the current view hierarchy under `failures/` for debugging; it is never published. */
    fun dumpHierarchy(name: String) {
        runCatching {
            val file = File(outputRoot, "failures/$name--$formFactor-api${Build.VERSION.SDK_INT}.xml")
            file.parentFile?.mkdirs()
            file.outputStream().use { device.dumpWindowHierarchy(it) }
        }
    }

    /** Saves [text] as evidence for [surface] in [state], next to the screenshots. */
    fun writeEvidence(
        surface: String,
        state: String,
        text: String,
    ): File {
        val file = File(outputRoot, "$surface/$state--$formFactor-api${Build.VERSION.SDK_INT}.txt")
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /**
     * What Android decided about the recording notification's Live Update: whether the app may
     * promote, whether the notification qualifies, and whether it was promoted. Android 16 added
     * these checks; older versions report only the SDK level.
     */
    fun recordingPromotionReport(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        val recording = notifications.activeNotifications.firstOrNull { it.id == RECORDING_NOTIFICATION_ID }?.notification
        return buildString {
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("recordingNotificationPosted=${recording != null}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && recording != null) {
                appendLine("canPostPromotedNotifications=${notifications.canPostPromotedNotifications()}")
                appendLine("hasPromotableCharacteristics=${recording.hasPromotableCharacteristics()}")
                appendLine("promotedOngoing=${recording.flags and Notification.FLAG_PROMOTED_ONGOING != 0}")
            }
        }
    }

    /**
     * Opens the launcher's app list and returns LogDate's icon there. Its description is exactly the
     * app name; the hotseat's "Predicted app: LogDate" copy is skipped, because long-pressing a
     * prediction offers to pin it instead of listing the app's shortcuts.
     */
    fun findAppIconInLauncher(): UiObject2 {
        device.pressHome()
        device.waitForIdle()
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 4 / 5, width / 2, height / 5, 12)
        return device.wait(Until.findObject(By.text(APP_LABEL).desc(APP_LABEL)), TIMEOUT_MS)
            ?: error("LogDate is not in the launcher's app list")
    }

    /** Long-presses LogDate's launcher icon so the launcher shows its shortcuts. */
    fun openLauncherShortcuts() {
        findAppIconInLauncher().longClick()
        device.waitForIdle()
    }

    /**
     * Waits for [text] on screen. If it never appears, saves what the screen showed instead (a
     * screenshot and the view hierarchy, under `failures/`) before failing, so the cause is visible.
     */
    fun waitForText(
        text: String,
        timeoutMs: Long = TIMEOUT_MS,
        partial: Boolean = false,
    ): UiObject2 {
        // Android 17 can show Quick Settings tiles as icons only, naming them in the description.
        val visible = if (partial) By.textStartsWith(text) else By.text(text)
        val described = By.desc(Pattern.compile(".*\\Q$text\\E.*"))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            (device.findObject(visible) ?: device.findObject(described))?.let { return it }
            Thread.sleep(250L)
        }
        val name = text.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        runCatching { capture(surface = "failures", state = "missing-$name") }
        runCatching {
            File(outputRoot, "failures/missing-$name.xml").outputStream().use { device.dumpWindowHierarchy(it) }
        }
        error("'$text' did not appear")
    }

    val packageName: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    /**
     * Puts the app in the state a returning person sees: onboarded, unlocked, and allowed to record
     * and post notifications, so system surfaces open straight into the editor.
     */
    fun prepareOnboardedApp() {
        runBlocking {
            val users = GlobalContext.get().get<UserStateRepository>()
            users.setBirthday(Instant.parse("1994-06-14T00:00:00Z"))
            users.setBiometricEnabled(false)
            users.setIsOnboardingComplete(true)
        }
        shell("pm grant $packageName android.permission.RECORD_AUDIO")
        shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    /**
     * Turns on [flags] and waits for flag-gated components to follow. Components switch only once
     * LogDate's startup has run, so this starts it the way an activity would.
     */
    fun enableFlags(vararg flags: FeatureFlag) {
        ensureLogDateApplicationStarted(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        runBlocking {
            val store = GlobalContext.get().get<FeatureFlagStore>()
            flags.forEach { store.setEnabled(it, true) }
        }
        device.waitForIdle()
        Thread.sleep(1_000L)
    }

    /**
     * Waits until the flag-gated component [className] is enabled, then gives the launcher a moment
     * to notice the package change. Components switch asynchronously after a flag turns on.
     */
    fun awaitComponentEnabled(className: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, className)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = context.packageManager.getComponentEnabledSetting(component)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                Thread.sleep(3_000L)
                return
            }
            Thread.sleep(250L)
        }
        error("$className was not enabled")
    }

    /** Adds the tile served by [serviceClass] to Quick Settings, as if the person placed it. */
    fun addQuickSettingsTile(serviceClass: String) {
        shell("cmd statusbar add-tile $packageName/$serviceClass")
    }

    /** Opens the fully expanded Quick Settings panel. */
    fun openQuickSettings() {
        device.openQuickSettings()
        device.waitForIdle()
        Thread.sleep(800L)
    }

    /** Opens the notification shade. */
    fun openNotifications() {
        device.openNotification()
        device.waitForIdle()
        Thread.sleep(800L)
    }

    /**
     * Posts [info]'s notification the way a running export or restore worker would, without running
     * the worker, so each stage of a transfer can be shown on demand.
     */
    fun postTransferNotification(info: ForegroundInfo) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSystemService(NotificationManager::class.java).notify(info.notificationId, info.notification)
        Thread.sleep(1_000L)
    }

    /** Turns LogDate's app lock (biometric unlock) on or off. */
    fun setAppLock(enabled: Boolean) {
        runBlocking { GlobalContext.get().get<LogdatePreferencesDataSource>().setShouldShowBiometric(enabled) }
    }

    /** Sets what assistants on this phone may see and do, as the person would in Settings. */
    fun setAndroidAssistantAccess(access: DeviceAssistantAccess) {
        runBlocking { GlobalContext.get().get<DeviceAssistantAccessStore>().set(access) }
    }

    /**
     * Holds a finger on [target] for about a second. [UiObject2.longClick] is short enough that
     * Compose can read it as a tap.
     */
    fun longPress(target: UiObject2) {
        val center = target.visibleCenter
        device.swipe(center.x, center.y, center.x, center.y, 200)
        device.waitForIdle()
    }

    /**
     * Goes back to Home from an editor, discarding anything typed there. The editor may be a screen
     * of the main activity or a window of its own; leaving the latter can land on the launcher, which
     * reopens the main screen.
     */
    fun returnHome() {
        repeat(RETURN_HOME_STEPS) {
            if (device.hasObject(By.text("Search your log"))) return
            if (device.currentPackageName != packageName) {
                openMainActivity()
                return@repeat
            }
            device.findObject(By.text("Discard"))?.click() ?: device.pressBack()
            device.waitForIdle()
            Thread.sleep(1_000L)
        }
        waitForText("Search your log")
    }

    /** Opens LogDate's main screen. */
    fun openMainActivity() {
        shell("am start -n $packageName/app.logdate.client.MainActivity -f 0x10000000")
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT_MS)
        Thread.sleep(3_000L)
    }

    /** Posts the evening capture reminder now, as the scheduler would at the end of the day. */
    fun postEveningCaptureReminder() {
        GlobalContext.get().get<AmbientPromptNotificationCoordinator>().post(
            AmbientPromptCandidate(
                family = AmbientPromptFamily.CAPTURE_NUDGES,
                score = 1,
                dedupeKey = "os-surface-evening",
                payload = AmbientPromptPayload.CaptureNudge(style = AmbientCaptureNudgeStyle.EVENING),
            ),
        )
        Thread.sleep(1_000L)
    }

    /** Saves a text entry with [text] now, the way the editor does, without capturing location. */
    fun createTextEntry(text: String) {
        val now = Clock.System.now()
        val note =
            JournalNote.Text(
                creationTimestamp = now,
                lastUpdated = now,
                content = text,
                timeZoneId = TimeZone.currentSystemDefault().id,
            )
        runBlocking { GlobalContext.get().get<AddNoteUseCase>()(notes = listOf(note), captureCurrentLocation = false) }
    }

    /**
     * Saves an entry with the debug build's sample photo and [caption], importing the photo into
     * LogDate's media store as the editor does, unless an earlier run already saved it, so the
     * Library shows the photo once.
     */
    fun createPhotoEntry(caption: String) {
        val notes = GlobalContext.get().get<JournalNotesRepository>()
        val existing = runBlocking { notes.allNotesObserved.first() }
        if (existing.any { it is JournalNote.Image && it.caption == caption }) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "library-photo.jpg")
        context.assets.open("sample_note_photo.jpg").use { input -> photo.outputStream().use(input::copyTo) }
        val uri = FileProvider.getUriForFile(context, "$packageName.provider", photo)
        val media = GlobalContext.get().get<MediaManager>()
        val importer =
            ManagedMediaImporter(
                mediaManager = media,
                source = AndroidManagedMediaImportSource(context),
                discardManagedMedia = AndroidManagedMediaDiscarder(context, media)::discard,
            )
        val now = Clock.System.now()
        runBlocking {
            val note =
                JournalNote.Image(
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = importer.import(uri.toString()),
                    caption = caption,
                    timeZoneId = TimeZone.currentSystemDefault().id,
                )
            GlobalContext.get().get<AddNoteUseCase>()(notes = listOf(note), captureCurrentLocation = false)
        }
    }

    /**
     * Puts a photo on the clipboard, the way copying an image in another app does: a content URI
     * from LogDate's file provider for a copy of the debug build's sample photo.
     */
    fun copyPhotoToClipboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "clipboard-photo.jpg")
        context.assets.open("sample_note_photo.jpg").use { input -> photo.outputStream().use(input::copyTo) }
        val uri = FileProvider.getUriForFile(context, "$packageName.provider", photo)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(context.contentResolver, "Photo", uri))
        }
    }

    /** Waits for autosave to keep a draft whose text includes [text], and returns its id. */
    fun awaitDraftContaining(
        text: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): Uuid {
        val drafts = GlobalContext.get().get<EntryDraftRepository>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val draft =
                runBlocking { drafts.getDrafts().first() }.firstOrNull { draft ->
                    draft.notes.any { note -> (note as? JournalNote.Text)?.content?.contains(text) == true }
                }
            if (draft != null) return draft.id
            Thread.sleep(250L)
        }
        error("No draft containing \"$text\" was saved")
    }

    /** Runs [block] on the main thread with the activity in front, and returns its result. */
    fun <T> onResumedActivity(block: (Activity) -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .single()
            result = runCatching { block(activity) }
        }
        return checkNotNull(result).getOrThrow()
    }

    /**
     * What each of LogDate's resumed activities reports about its window: whether Android says it
     * was launched as a bubble, whether it is in multi-window, and its size in dp. Bubble layouts
     * decide from these, so the captures record them next to the screenshots.
     */
    fun bubbleWindowReport(): String {
        var report = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            report =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .joinToString("\n") { activity ->
                        val configuration = activity.resources.configuration
                        "${activity.javaClass.simpleName}: isLaunchedFromBubble=${activity.isLaunchedFromBubble}, " +
                            "isInMultiWindowMode=${activity.isInMultiWindowMode}, " +
                            "window=${configuration.screenWidthDp}x${configuration.screenHeightDp}dp"
                    }
        }
        return report
    }

    /** Empties the clipboard, so the keyboard shows its tools instead of suggesting a copied item. */
    fun clearClipboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
    }

    /**
     * Saves the debug build's sample photo to Downloads as [name], replacing a copy an earlier run
     * left, so the Files app lists it once.
     */
    fun putPhotoInDownloads(name: String) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        // MediaStore renames a second copy "name (1).jpg", so copies are matched by the name's stem.
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("${name.substringBeforeLast('.')}%"),
        )
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Could not add $name to Downloads" }
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        resolver.openOutputStream(uri).use { output -> assets.open("sample_note_photo.jpg").use { it.copyTo(checkNotNull(output)) } }
    }

    /**
     * Moves the most recent window of the app with [packageName] to [bounds] on the desktop, through
     * `am task resize`.
     */
    fun placeWindow(
        packageName: String,
        bounds: Rect,
    ) {
        val task =
            shell("dumpsys activity recents")
                .lines()
                .firstOrNull { "Recent #" in it && packageName in it }
                ?.let { Regex("#(\\d+) type=").find(it)?.groupValues?.get(1) }
                ?: error("$packageName has no window")
        shell("am task resize $task ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}")
        Thread.sleep(1_500L)
    }

    /**
     * Rests a mouse pointer on [point], so hover feedback such as a tooltip shows. The pointer moves
     * in from a little to the left, as a hand on a mouse would bring it.
     */
    fun hover(point: Point) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (step in HOVER_STEPS downTo 0) {
            val properties =
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            val coords =
                MotionEvent.PointerCoords().apply {
                    x = (point.x - step * HOVER_STEP_PX).toFloat()
                    y = point.y.toFloat()
                }
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_HOVER_MOVE,
                    1,
                    arrayOf(properties),
                    arrayOf(coords),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_MOUSE,
                    0,
                )
            automation.injectInputEvent(event, true)
            event.recycle()
            Thread.sleep(HOVER_FRAME_MS)
        }
    }

    /**
     * Drags with a finger from [from] to [to]: a press held still past the long-press timeout, which
     * picks the item up, then a steady move over [durationMs]. [overTarget] runs while the item is
     * held over [to], before the finger lifts and drops it.
     */
    fun drag(
        from: Point,
        to: Point,
        durationMs: Long,
        overTarget: () -> Unit,
    ) {
        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, from, downTime)
        Thread.sleep(LONG_PRESS_HOLD_MS)
        val steps = (durationMs / TOUCH_FRAME_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val point = Point(from.x + ((to.x - from.x) * fraction).toInt(), from.y + ((to.y - from.y) * fraction).toInt())
            touch(MotionEvent.ACTION_MOVE, point, downTime)
            Thread.sleep(TOUCH_FRAME_MS)
        }
        Thread.sleep(DROP_SETTLE_MS)
        overTarget()
        touch(MotionEvent.ACTION_UP, to, downTime)
        device.waitForIdle()
    }

    private fun touch(
        action: Int,
        point: Point,
        downTime: Long,
    ) {
        val touchscreen =
            InputDevice.getDeviceIds().firstOrNull { id ->
                InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
            } ?: 0
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coords =
            MotionEvent.PointerCoords().apply {
                x = point.x.toFloat()
                y = point.y.toFloat()
                pressure = 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                1,
                arrayOf(properties),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                touchscreen,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /**
     * Runs [block] with the on-screen keyboards switched off, then switches them back on. The
     * emulator's Gboard stays up next to a physical keyboard, which most people turn off, and would
     * cover half of a capture.
     */
    fun <T> withoutOnScreenKeyboards(block: () -> T): T {
        val keyboards = shell("ime list -s").lines().filter { it.isNotBlank() }
        keyboards.forEach { shell("ime disable $it") }
        try {
            return block()
        } finally {
            keyboards.forEach { shell("ime enable $it") }
        }
    }

    /**
     * Deletes every unfinished entry. The desktop windowing emulator keeps its data between runs, and
     * each run's editors would otherwise pile up as drafts, counted on the editor's drafts button.
     */
    fun clearDrafts() {
        runBlocking { GlobalContext.get().get<EntryDraftRepository>().deleteAllDrafts() }
    }

    /**
     * Returns the id of the one journal named [title], creating it if needed. The desktop windowing
     * emulator keeps its data between runs, so copies earlier runs left are removed; a capture shows
     * the journal once.
     */
    fun createJournal(title: String): String =
        runBlocking {
            val journals = GlobalContext.get().get<JournalRepository>()
            val existing = journals.allJournalsObserved.first().filter { it.title == title }
            existing.drop(1).forEach { journals.delete(it.id) }
            (existing.firstOrNull()?.id ?: journals.create(Journal(title = title))).toString()
        }

    /** Opens the journal [journalId] through its deep link. */
    fun openJournal(journalId: String) {
        shell("am start -a android.intent.action.VIEW -d logdate://journal/$journalId -f 0x10000000 $packageName")
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    /** Lets every LogDate conversation float as a bubble, as if the person chose "All conversations". */
    fun allowBubbles() {
        shell("cmd notification set_bubbles $packageName 1")
    }

    /** Opens the editor through the public capture action, as a shortcut or tile would. */
    fun openCapture(mode: String) {
        shell("am start -a app.logdate.action.CAPTURE --es mode $mode -f 0x10000000 $packageName")
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), TIMEOUT_MS)
        device.waitForIdle()
    }

    /** Stops any recording a test left running, so the next test starts from an idle recorder. */
    fun stopRecording() {
        runCatching { GlobalContext.get().get<AudioRecordingManager>().requestStopRecording() }
        Thread.sleep(1_000L)
    }

    fun collapseShade() {
        shell("cmd statusbar collapse")
        device.waitForIdle()
    }
}
