package app.logdate.client.e2e.osintegration

import android.app.HandoffActivityDataRequestInfo
import android.content.Intent
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.assistants.DeviceAssistantAccess
import app.logdate.client.feature.widgets.CaptureWidget
import app.logdate.client.feature.widgets.FixedMemoryWidget
import app.logdate.client.feature.widgets.NewEntryWidget
import app.logdate.client.feature.widgets.OnThisDayWidget
import app.logdate.client.feature.widgets.OnThisDayWidgetState
import app.logdate.client.feature.widgets.shortcuts.DynamicShortcutDescriptor
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.client.sync.SyncForegroundNotification
import app.logdate.feature.core.export.ExportNotificationHelper
import app.logdate.feature.core.restore.RestoreNotificationHelper
import app.logdate.feature.core.restore.RestoreStage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.regex.Pattern
import kotlin.uuid.Uuid
import app.logdate.client.sync.R as SyncR

/**
 * Real system UI captures for the Android OS integration surfaces.
 *
 * Each test drives Android into one state and saves a screenshot through [OsSurfaceCapture]. These
 * are reviewed evidence, not pixel-compared goldens: system UI differs slightly between emulator
 * images. Run with `./run screenshots:os`.
 */
@RunWith(AndroidJUnit4::class)
class OsSurfaceScreenshotsTest {
    @Before fun pinStatusBar() {
        OsSurfaceCapture.enterDemoMode()
    }

    @After fun releaseStatusBar() {
        OsSurfaceCapture.stopRecording()
        OsSurfaceCapture.collapseShade()
        OsSurfaceCapture.exitDemoMode()
        OsSurfaceCapture.device.pressHome()
    }

    @Test fun launcherShortcuts() {
        OsSurfaceCapture.openLauncherShortcuts()
        // Launchers list a shortcut by its long label when there is room.
        OsSurfaceCapture.waitForText("Record a voice entry")
        OsSurfaceCapture.capture(surface = "launcher-shortcuts", state = "long-press")
        OsSurfaceCapture.dumpHierarchy("launcher-long-press")
    }

    /**
     * Assistant actions, driven the way an assistant would through `cmd app_function`. Android 16
     * added the command; the output is saved as text evidence next to the screenshots. It shows that
     * nothing writes an entry, that each action follows what the person shared, and that reading
     * stops while LogDate is locked.
     */
    @Test fun assistantActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.createTextEntry("Walked the long way home along the river.")
        OsSurfaceCapture.setAndroidAssistantAccess(DeviceAssistantAccess())
        OsSurfaceCapture.enableFlags(FeatureFlag.APP_FUNCTIONS)
        OsSurfaceCapture.awaitComponentEnabled("app.logdate.client.appfunctions.LogDateAppFunctionService")
        Thread.sleep(5_000L)

        val pkg = OsSurfaceCapture.packageName
        val functions = "app.logdate.client.appfunctions.LogDateAppFunctions"

        // A settings change re-indexes LogDate's search schema, and the function registry can briefly
        // report a function as missing (code 1003) while that settles, so that answer is retried.
        fun execute(
            function: String,
            parameters: String,
        ): String {
            val command =
                "cmd app_function execute-app-function --package $pkg --function '$functions#$function' --parameters '$parameters'"
            repeat(FUNCTION_LOOKUP_ATTEMPTS - 1) {
                val output = OsSurfaceCapture.shellScript(command)
                if ("(code 1003)" !in output) return output
                Thread.sleep(1_000L)
            }
            return OsSurfaceCapture.shellScript(command)
        }

        // Android indexes the functions some time after the service is enabled.
        fun listing(): String =
            OsSurfaceCapture
                .shellScript("cmd app_function list-app-functions")
                .lines()
                .filter { it.contains(functions) || it.contains(pkg) }
                .joinToString("\n")
        var listing = listing()
        var attempts = 0
        while ("#search" !in listing && attempts++ < FUNCTION_LOOKUP_ATTEMPTS) {
            Thread.sleep(2_000L)
            listing = listing()
        }
        check("#search" in listing && "#startEntry" in listing) { "LogDate's functions are not registered: $listing" }
        check("createTextEntry" !in listing) { "An assistant can still write entries: $listing" }
        val searchRefused = execute("search", """{"query":["river"]}""")
        val journalsRefused = execute("listJournals", "{}")
        val startRefused = execute("startEntry", """{"voice":[true]}""")
        OsSurfaceCapture.setAndroidAssistantAccess(
            DeviceAssistantAccess(
                capabilities = setOf(AssistantCapability.READ_MEMORIES, AssistantCapability.START_CAPTURE),
                contentScope = AssistantContentScope(emptySet(), includeUnfiled = true),
            ),
        )
        // With the app lock on and no unlock in this process, reading stays refused.
        OsSurfaceCapture.setAppLock(true)
        Thread.sleep(1_000L)
        val locked = execute("search", """{"query":["river"]}""")
        OsSurfaceCapture.setAppLock(false)
        Thread.sleep(1_000L)
        val found = execute("search", """{"query":["river"]}""")
        val journals = execute("listJournals", "{}")
        val started = execute("startEntry", """{"voice":[true]}""")
        // The refusal names the settings page where the person can share something.
        check("Android assistants" in searchRefused) { "A search with nothing shared was not refused: $searchRefused" }
        check("locked" in locked) { "A search with the app lock on was not refused: $locked" }
        check("Walked the long way home" in found) { "A shared entry was not found: $found" }
        OsSurfaceCapture.writeEvidence(
            surface = "app-functions",
            state = "assistant-actions",
            text =
                buildString {
                    appendLine("# list-app-functions (LogDate)")
                    appendLine(listing)
                    appendLine()
                    appendLine("# search with nothing shared")
                    appendLine(searchRefused.trim())
                    appendLine()
                    appendLine("# listJournals with nothing shared")
                    appendLine(journalsRefused.trim())
                    appendLine()
                    appendLine("# startEntry with starting entries off")
                    appendLine(startRefused.trim())
                    appendLine()
                    appendLine("# search with entries outside journals shared and the app lock on, never unlocked")
                    appendLine(locked.trim())
                    appendLine()
                    appendLine("# search with entries outside journals shared")
                    appendLine(found.trim())
                    appendLine()
                    appendLine("# listJournals with no journal shared")
                    appendLine(journals.trim())
                    appendLine()
                    appendLine("# startEntry with starting entries on (cmd prints the returned PendingIntent as {})")
                    appendLine(started.trim())
                },
        )
    }

    /** Android's keyboard shortcut helper (Meta+/) listing LogDate's shortcuts. */
    @Test fun keyboardShortcutHelper() {
        // A phone opens the helper as a sheet with every group closed, so LogDate's are not listed.
        if (OsSurfaceCapture.formFactor != "tablet") return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.withoutOnScreenKeyboards {
            HardwareKeyboard().use { keyboard ->
                keyboard.press(HardwareKeyboard.KEY_LEFTMETA, HardwareKeyboard.KEY_SLASH)
                // The helper opens on the focused app's shortcuts, grouped under its name.
                OsSurfaceCapture.waitForText("Record a voice entry")
                Thread.sleep(1_000L)
                OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "shortcut-helper")
            }
        }
    }

    /**
     * A restore in progress as a Live Update with one bar segment per phase, then the finished and
     * failed states, whose titles Android 17 colors green and red.
     */
    @Test fun transferLiveUpdates() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.LIVE_UPDATES)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restore = RestoreNotificationHelper(context, Uuid.random())
        val export = ExportNotificationHelper(context, Uuid.random())
        OsSurfaceCapture.device.pressHome()
        OsSurfaceCapture.postTransferNotification(restore.createForegroundInfo(RestoreStage.RESTORING_NOTES))
        OsSurfaceCapture.capture(surface = "live-updates", state = "transfer-status-chip")
        OsSurfaceCapture.openNotifications()
        OsSurfaceCapture.waitForText("Restoring data")
        OsSurfaceCapture.capture(surface = "live-updates", state = "transfer-progress")
        restore.showCompletionNotification()
        export.showErrorNotification("The folder you chose is no longer available.")
        OsSurfaceCapture.waitForText("Export failed")
        OsSurfaceCapture.capture(surface = "live-updates", state = "transfer-finished")
    }

    /** A backup to LogDate Cloud as a Live Update, with the share of entries uploaded in its chip. */
    @Test fun syncLiveUpdate() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.LIVE_UPDATES)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        OsSurfaceCapture.device.pressHome()
        OsSurfaceCapture.postTransferNotification(
            SyncForegroundNotification.info(
                context,
                context.getString(SyncR.string.sync_notification_progress, 128, 340),
                completed = 128,
                total = 340,
            ),
        )
        OsSurfaceCapture.capture(surface = "live-updates", state = "sync-status-chip")
        OsSurfaceCapture.openNotifications()
        OsSurfaceCapture.waitForText("Backing up your memories")
        OsSurfaceCapture.capture(surface = "live-updates", state = "sync-progress")
    }

    /** The splash screen on a fresh start: Android 12's system splash, or the compat splash before it. */
    @Test fun splashScreen() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.captureSplash(surface = "splash", state = "launch", background = SPLASH_BACKGROUND)
    }

    /** A capture reminder answered from the notification shade with Write. */
    @Test fun notificationQuickReply() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.NOTIFICATION_QUICK_REPLY)
        OsSurfaceCapture.postEveningCaptureReminder()
        OsSurfaceCapture.openNotifications()
        OsSurfaceCapture.waitForText("Write").click()
        val field = OsSurfaceCapture.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        field?.text = "Sunset over the bay, then dinner on the steps."
        Thread.sleep(1_000L)
        OsSurfaceCapture.capture(surface = "notification-quick-reply", state = "reply-field")
        OsSurfaceCapture.device.wait(Until.findObject(By.desc("Send")), 3_000L)?.click()
        OsSurfaceCapture.waitForText("Entry saved")
        Thread.sleep(800L)
        OsSurfaceCapture.capture(surface = "notification-quick-reply", state = "saved")
    }

    /** On a tablet, a long-press on a journal opens its menu, whose "New entry in a new window" opens the editor beside LogDate. */
    @Test fun editorWindowSideBySide() {
        if (OsSurfaceCapture.formFactor != "tablet") return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.createJournal("Summer in Lisbon")
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.waitForText("Journals").click()
        // The sidebar's recent journals name the journal too. Its cover is the rightmost of them.
        OsSurfaceCapture.waitForText("Summer in Lisbon")
        OsSurfaceCapture.longPress(OsSurfaceCapture.device.findObjects(By.text("Summer in Lisbon")).maxBy { it.visibleCenter.x })
        val newWindow = OsSurfaceCapture.waitForText("New entry in a new window")
        Thread.sleep(500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "journal-context-menu")
        newWindow.click()
        Thread.sleep(4_000L)
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "side-by-side")
    }

    /**
     * LogDate in Android desktop windowing: the main window, and a new entry opened from a journal's
     * menu in a window of its own. Desktop windowing needs developer settings and a restart, which a
     * managed device cannot do, so this runs only when `scripts/desktop-windowing-screenshots.sh`
     * passes `logdate.desktopWindowing=true` from an emulator that has desktop windowing turned on.
     */
    @Test fun desktopWindows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("logdate.desktopWindowing") == "true")
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.clearDrafts()
        OsSurfaceCapture.createJournal("Summer in Lisbon")
        // Closes the Files window crossAppDrag leaves open on this reused emulator.
        OsSurfaceCapture.shell("am force-stop $FILES_PACKAGE")
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -n ${OsSurfaceCapture.packageName}/app.logdate.client.MainActivity",
        )
        OsSurfaceCapture.waitForText("Journals").click()
        OsSurfaceCapture.waitForText("Summer in Lisbon")
        Thread.sleep(2_000L)
        // Search and the account button in the window's caption bar, between Android's controls.
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "caption-bar")
        // A mouse resting on the account button names it in a tooltip.
        val account = OsSurfaceCapture.device.findObject(By.desc(ACCOUNT_LABEL)).visibleCenter
        OsSurfaceCapture.hover(account)
        checkNotNull(OsSurfaceCapture.device.wait(Until.findObject(By.text(ACCOUNT_LABEL)), 5_000L)) { "No tooltip on the account button" }
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "account-tooltip")
        OsSurfaceCapture.hover(Point(account.x, OsSurfaceCapture.device.displayHeight - account.y))
        OsSurfaceCapture.longPress(OsSurfaceCapture.waitForText("Summer in Lisbon"))
        OsSurfaceCapture.waitForText("New entry in a new window").click()
        Thread.sleep(4_000L)
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "desktop-windows")
        // The labels Android holds for each window, which the caption bar and Recents may show.
        val labels = OsSurfaceCapture.shell("dumpsys activity activities").lines().filter { "taskDescription: label" in it }
        OsSurfaceCapture.writeEvidence(
            "large-screens-and-desktop-windowing",
            "desktop-window-labels",
            labels.joinToString("\n") { it.trim() },
        )

        // Writing names the new window after the entry's first line. Android 17's Alt+Tab switcher
        // reads each window out by that name, which tells two editor windows apart.
        startWriting()
        OsSurfaceCapture.device.findObject(By.clazz("android.widget.EditText")).text = WINDOW_TITLE_ENTRY
        Thread.sleep(1_500L)
        // The switcher keeps the names it last read and reads them again when the window order
        // changes, so the Journals window comes forward first.
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -n ${OsSurfaceCapture.packageName}/app.logdate.client.MainActivity",
        )
        Thread.sleep(1_500L)
        HardwareKeyboard().use { keyboard ->
            keyboard.hold(HardwareKeyboard.KEY_LEFTALT, HardwareKeyboard.KEY_TAB)
            keyboard.release(HardwareKeyboard.KEY_TAB)
            checkNotNull(
                OsSurfaceCapture.device.wait(Until.findObject(By.desc(SWITCHER_ITEM)), 5_000L),
            ) { "Alt+Tab did not open the switcher" }
            Thread.sleep(1_000L)
            val names = OsSurfaceCapture.device.findObjects(By.desc(SWITCHER_ITEM)).map { it.contentDescription }
            OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "alt-tab-switcher")
            OsSurfaceCapture.writeEvidence("large-screens-and-desktop-windowing", "alt-tab-window-names", names.joinToString("\n"))
            keyboard.release(HardwareKeyboard.KEY_LEFTALT)
            check(names.any { "LogDate $WINDOW_TITLE_ENTRY" in it }) { "The switcher did not name the editor window by its entry: $names" }
        }
    }

    /**
     * A photo dragged from the Files app into a new entry, with the two apps side by side on the
     * Android desktop: once while the photo is over the editor, and once after it is dropped. Runs
     * with [desktopWindows], from `scripts/desktop-windowing-screenshots.sh`.
     */
    @Test fun crossAppDrag() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("logdate.desktopWindowing") == "true")
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.clearDrafts()
        OsSurfaceCapture.createJournal("Summer in Lisbon")
        OsSurfaceCapture.putPhotoInDownloads(DRAGGED_PHOTO)
        // Starts from Home, discarding an entry an earlier run left open.
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.returnHome()
        // One Files window, freshly opened at Downloads.
        OsSurfaceCapture.shell("am force-stop $FILES_PACKAGE")
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -n $FILES_ACTIVITY -d $DOWNLOADS_ROOT",
        )
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -a app.logdate.action.CAPTURE --es mode text " +
                "-f 0x10000000 ${OsSurfaceCapture.packageName}",
        )
        val screen = Rect(0, 0, OsSurfaceCapture.device.displayWidth, OsSurfaceCapture.device.displayHeight)
        val top = screen.height() / 16
        val bottom = screen.height() * 15 / 16
        OsSurfaceCapture.placeWindow("documentsui", Rect(top, top, screen.centerX() - top / 4, bottom))
        OsSurfaceCapture.placeWindow(OsSurfaceCapture.packageName, Rect(screen.centerX() + top / 4, top, screen.width() - top, bottom))

        val label = OsSurfaceCapture.waitForText(DRAGGED_PHOTO.substringBefore('.'), partial = true).visibleBounds
        val thumbnail = OsSurfaceCapture.device.findObject(By.res(FILES_PACKAGE, "icon_thumb"))?.visibleBounds
        val from = thumbnail?.let { Point(it.centerX(), it.centerY()) } ?: Point(label.centerX(), label.top - label.height() * 3)
        val editor = OsSurfaceCapture.waitForText(WRITE).visibleCenter
        // A long press selects the file in Files; dragging the selected file starts the drag.
        OsSurfaceCapture.device.swipe(from.x, from.y, from.x, from.y, LONG_PRESS_STEPS)
        Thread.sleep(1_000L)
        OsSurfaceCapture.drag(from, editor, durationMs = 2_000L) {
            OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "cross-app-drag")
        }
        OsSurfaceCapture.waitForText("Add a caption...")
        Thread.sleep(1_500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "cross-app-drop")
    }

    /**
     * A photo dragged out of LogDate's Library into a new entry in another window, side by side on
     * the Android desktop: once while it is over the entry, and once it is dropped. The drag carries
     * the file itself, as a content URI the receiving window may read, the same as a drag into
     * another app. Runs from `scripts/desktop-windowing-screenshots.sh`.
     */
    @Test fun dragPhotoBetweenWindows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("logdate.desktopWindowing") == "true")
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.clearDrafts()
        OsSurfaceCapture.createPhotoEntry(LIBRARY_PHOTO_CAPTION)
        OsSurfaceCapture.shell("am force-stop $FILES_PACKAGE")
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -n ${OsSurfaceCapture.packageName}/app.logdate.client.MainActivity",
        )
        OsSurfaceCapture.returnHome()
        OsSurfaceCapture.waitForText("Library").click()
        OsSurfaceCapture.shell(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -f 0x18080000 " +
                "-n ${OsSurfaceCapture.packageName}/app.logdate.client.EditorActivity",
        )
        val screen = Rect(0, 0, OsSurfaceCapture.device.displayWidth, OsSurfaceCapture.device.displayHeight)
        val top = screen.height() / 16
        val bottom = screen.height() * 15 / 16
        OsSurfaceCapture.placeWindow(OsSurfaceCapture.packageName, Rect(top, top, screen.centerX() - top / 4, bottom))
        OsSurfaceCapture.placeWindow(EDITOR_TASK_AFFINITY, Rect(screen.centerX() + top / 4, top, screen.width() - top, bottom))

        val photo = OsSurfaceCapture.waitForText("Photo from", partial = true).visibleCenter
        val entry = OsSurfaceCapture.waitForText(WRITE).visibleCenter
        OsSurfaceCapture.drag(photo, entry, durationMs = 2_000L) {
            OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "drag-out")
        }
        OsSurfaceCapture.waitForText("Add a caption...")
        Thread.sleep(1_500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "drag-out-dropped")
    }

    /**
     * Android's share sheet offering LogDate's journals as Direct Share targets, for text shared
     * from another app.
     */
    @Test fun directShareTargets() {
        if (OsSurfaceCapture.formFactor != "phone") return
        OsSurfaceCapture.prepareOnboardedApp()
        val journal = OsSurfaceCapture.createJournal("Summer in Lisbon")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shortcutId = "${DynamicShortcutDescriptor.ShareToJournal.ID_PREFIX}:$journal"
        GlobalContext.get().get<DynamicShortcutScheduler>().enqueueImmediateRefresh()
        val deadline = System.currentTimeMillis() + 15_000L
        while (ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcutId }) {
            check(System.currentTimeMillis() < deadline) { "LogDate did not publish the journal's share target" }
            Thread.sleep(500L)
        }
        val share =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "The first leaves are turning on the walk home.")
        context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        OsSurfaceCapture.waitForText("Summer in Lisbon")
        Thread.sleep(1_500L)
        OsSurfaceCapture.capture(surface = "launcher-shortcuts", state = "share-sheet")
        OsSurfaceCapture.device.pressBack()
    }

    /**
     * On a tablet, a long-press on an entry in a day opens its menu, whose "Open in new window" opens
     * that entry in the editor beside LogDate.
     */
    @Test fun entryWindowSideBySide() {
        if (OsSurfaceCapture.formFactor != "tablet") return
        val entry = "Walked the long way home along the river. The light on the water was gold."
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.createTextEntry(entry)
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.waitForText(entry)
        // A day opens from its heading. Tapping an entry in the timeline opens that entry alone.
        OsSurfaceCapture.waitForText(TODAY).click()
        // Where there is room the day opens beside the timeline, and the entry is on screen twice.
        // On a narrower window the day opens in the timeline's place, and it is there once.
        val deadline = System.currentTimeMillis() + DAY_BESIDE_TIMELINE_MS
        while (OsSurfaceCapture.device.findObjects(By.text(entry)).size < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250L)
        }
        // Lets the day finish opening before the long-press.
        Thread.sleep(1_500L)
        val copies = OsSurfaceCapture.device.findObjects(By.text(entry))
        val inTimeline = copies.minBy { it.visibleCenter.x }.visibleBounds.takeIf { copies.size > 1 }
        OsSurfaceCapture.longPress(copies.maxBy { it.visibleCenter.x })
        val openInNewWindow = OsSurfaceCapture.waitForText("Open in new window")
        // Beside the timeline, the menu belongs to the entry in the day, so it opens to the right of
        // the timeline's copy.
        if (inTimeline != null) {
            check(openInNewWindow.visibleBounds.left > inTimeline.right) {
                "The entry's menu opened at ${openInNewWindow.visibleBounds}, over the timeline at $inTimeline"
            }
        }
        Thread.sleep(500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "entry-context-menu")
        openInNewWindow.click()
        Thread.sleep(4_000L)
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "entry-side-by-side")
    }

    /** A photo copied in another app, pasted into a new entry with Ctrl+V from a USB keyboard. */
    @Test fun pastePhotoIntoEntry() {
        if (OsSurfaceCapture.formFactor != "tablet") return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.copyPhotoToClipboard()
        OsSurfaceCapture.openCapture("text")
        startWriting()
        OsSurfaceCapture.withoutOnScreenKeyboards {
            HardwareKeyboard().use { keyboard ->
                keyboard.press(HardwareKeyboard.KEY_LEFTCTRL, HardwareKeyboard.KEY_V)
                Thread.sleep(4_000L)
                OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "paste-photo")
            }
        }
    }

    /**
     * A photo copied in another app, pasted into a new entry from the text menu that holding a finger
     * on the field opens: once with the menu showing Paste, and once with the photo attached.
     */
    @Test fun pastePhotoFromTextMenu() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.copyPhotoToClipboard()
        OsSurfaceCapture.openCapture("text")
        val hint = startWriting()
        // A press while the keyboard is still opening moves the field under it and drops the menu.
        OsSurfaceCapture.device.wait(Until.hasObject(By.pkg(GBOARD_PACKAGE)), KEYBOARD_SUGGESTION_TIMEOUT_MS)
        Thread.sleep(1_000L)
        OsSurfaceCapture.longPress(hint)
        val paste = OsSurfaceCapture.waitForText("Paste")
        Thread.sleep(500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "paste-menu")
        paste.click()
        Thread.sleep(4_000L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "paste-menu-photo")
    }

    /**
     * A copied photo sent into a new entry by the on-screen keyboard. Gboard suggests a copied image
     * only to a field that accepts images, and sends it as a file, the way it sends GIFs and stickers.
     */
    @Test fun keyboardInsertsImage() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.copyPhotoToClipboard()
        OsSurfaceCapture.openCapture("text")
        startWriting()
        val suggestion =
            checkNotNull(
                OsSurfaceCapture.device.wait(Until.findObject(By.pkg(GBOARD_PACKAGE).text("Image")), KEYBOARD_SUGGESTION_TIMEOUT_MS),
            ) { "The keyboard did not suggest the copied image" }
        Thread.sleep(500L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "keyboard-image-suggestion")
        suggestion.click()
        check(awaitPhotoInEntry(4_000L)) { "The keyboard's image was not added to the entry" }
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "keyboard-image-inserted")
    }

    /** A GIF picked from the on-screen keyboard's GIF panel and sent into a new entry. */
    @Test fun keyboardGif() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.clearClipboard()
        OsSurfaceCapture.openCapture("text")
        startWriting()
        val gifTab =
            checkNotNull(
                OsSurfaceCapture.device.wait(Until.findObject(By.pkg(GBOARD_PACKAGE).desc("GIF Keyboard")), KEYBOARD_SUGGESTION_TIMEOUT_MS),
            ) {
                OsSurfaceCapture.dumpHierarchy("keyboard-gif-tab")
                "The keyboard has no GIF button"
            }
        gifTab.click()
        // Gboard describes each GIF in a sentence; its tabs and categories have short names.
        val firstGif =
            checkNotNull(
                OsSurfaceCapture.device.wait(Until.findObject(By.pkg(GBOARD_PACKAGE).desc(GIF_DESCRIPTION)), GIF_PANEL_TIMEOUT_MS),
            ) {
                OsSurfaceCapture.dumpHierarchy("keyboard-gif-panel")
                "The keyboard's GIF panel did not load any GIFs"
            }
        Thread.sleep(3_000L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "keyboard-gif-panel")
        firstGif.click()
        check(awaitPhotoInEntry(GIF_PANEL_TIMEOUT_MS)) { "The keyboard's GIF was not added to the entry" }
        Thread.sleep(2_000L)
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "keyboard-gif-inserted")
    }

    /**
     * Editor Handoff on one device, both ends: the editor offers its draft to other devices, then
     * opens that draft the way a second device does when Handoff launches it, and explains a draft
     * that has not arrived. Two devices on one account cannot run on a managed emulator, so the
     * receiving launch uses the same intent extras Android passes on.
     */
    @Test fun editorHandoff() {
        if (Build.VERSION.SDK_INT < 37) return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.clearDrafts()
        OsSurfaceCapture.enableFlags(FeatureFlag.EDITOR_HANDOFF)
        OsSurfaceCapture.shell("am start -n ${OsSurfaceCapture.packageName}/$EDITOR_ACTIVITY -f 0x10000000")
        startWriting()
        OsSurfaceCapture.device.findObject(By.clazz("android.widget.EditText")).text = HANDOFF_ENTRY
        val draftId = OsSurfaceCapture.awaitDraftContaining(HANDOFF_ENTRY)

        val offered =
            OsSurfaceCapture.onResumedActivity { activity ->
                check(activity.isHandoffEnabled) { "The editor did not offer itself for Handoff with the flag on" }
                activity.onHandoffActivityDataRequested(HandoffActivityDataRequestInfo(false))
            }
        val offeredDraft = offered?.extras?.getString(EXTRA_HANDOFF_DRAFT_ID)
        check(offeredDraft == draftId.toString()) { "Handoff offered draft $offeredDraft instead of $draftId" }
        OsSurfaceCapture.writeEvidence(
            surface = "large-screens-and-desktop-windowing",
            state = "editor-handoff-data",
            text = "component=${offered?.componentName?.flattenToShortString()}\n$EXTRA_HANDOFF_DRAFT_ID=$offeredDraft\n",
        )
        OsSurfaceCapture.onResumedActivity { it.finish() }

        OsSurfaceCapture.shell(
            "am start -n ${OsSurfaceCapture.packageName}/$EDITOR_ACTIVITY -f 0x10000000 --es $EXTRA_HANDOFF_DRAFT_ID $draftId",
        )
        OsSurfaceCapture.waitForText(HANDOFF_ENTRY)
        Thread.sleep(1_000L)
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "editor-handoff-arrived")
        OsSurfaceCapture.onResumedActivity { it.finish() }

        // Backup is off on the managed device, so a draft that is not here can never arrive.
        OsSurfaceCapture.shell(
            "am start -n ${OsSurfaceCapture.packageName}/$EDITOR_ACTIVITY -f 0x10000000 --es $EXTRA_HANDOFF_DRAFT_ID ${Uuid.random()}",
        )
        OsSurfaceCapture.waitForText("Your entry hasn’t arrived")
        Thread.sleep(500L)
        OsSurfaceCapture.capture(surface = "large-screens-and-desktop-windowing", state = "editor-handoff-missing")
        OsSurfaceCapture.onResumedActivity { it.finish() }
    }

    /**
     * "hello" written with a pen in a new entry: once while the pen is still down, with the ink on
     * screen, and once after the keyboard's handwriting recognition has typed it into the entry.
     */
    @Test fun stylusHandwriting() {
        if (OsSurfaceCapture.formFactor != "tablet") return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.shell("settings put secure stylus_handwriting_enabled 1")
        OsSurfaceCapture.openCapture("text")
        val stylus = Stylus()
        stylus.tap(OsSurfaceCapture.waitForText(WRITE).visibleCenter)
        dismissStylusTutorial()
        OsSurfaceCapture.waitForText(WRITING_HINT)
        val field = OsSurfaceCapture.device.findObject(By.clazz("android.widget.EditText")).visibleBounds
        awaitHandwritingModel(stylus, field)
        dismissStylusTutorial()
        hideOnScreenKeyboard()
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        val baseline = PointF(field.left + HANDWRITING_INSET_DP * density, field.top + HANDWRITING_BASELINE_DP * density)
        stylus.write(Stylus.hello(origin = baseline, unit = HANDWRITING_X_HEIGHT_DP * density)) {
            OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-handwriting")
        }
        awaitTypedHello("stylus-handwriting")
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-handwriting-typed")
    }

    /**
     * Writing with a pen on controls that only open a text field: the entry page's Write choice
     * starts a text entry, and the Home search pill opens search, each with the handwriting typed
     * in.
     */
    @Test fun stylusWritingOnControls() {
        if (OsSurfaceCapture.formFactor != "tablet") return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.shell("settings put secure stylus_handwriting_enabled 1")
        // Something for the handwritten search to find.
        OsSurfaceCapture.createTextEntry("Said hello to the new neighbors on the walk home.")
        val stylus = Stylus()
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density

        // Gboard's handwriting model is fetched in the search field, which leaves the entry page's
        // Write choice untouched for the capture.
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.returnHome()
        OsSurfaceCapture.waitForText("Search your log").click()
        val searchField = OsSurfaceCapture.device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        checkNotNull(searchField) { "Search did not open" }
        awaitHandwritingModel(stylus, searchField.visibleBounds)
        dismissStylusTutorial()
        hideOnScreenKeyboard()
        OsSurfaceCapture.returnHome()

        OsSurfaceCapture.openCapture("text")
        OsSurfaceCapture.waitForText(WRITE)
        val write = OsSurfaceCapture.device.findObject(By.desc(WRITE)).visibleBounds
        // Writing opens as soon as the pen touches the choice. It is as short as the search pill, so
        // the writing is as small and starts on it; strokes may run past its edges.
        val onWrite = PointF(write.left + HANDWRITING_INSET_DP * density, write.bottom - SEARCH_BASELINE_INSET_DP * density)
        stylus.write(Stylus.hello(origin = onWrite, unit = SEARCH_X_HEIGHT_DP * density)) {
            OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-write-choice")
        }
        awaitTypedHello("stylus-write-choice")
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-write-choice-typed")

        OsSurfaceCapture.returnHome()
        val pill = OsSurfaceCapture.device.findObject(By.clickable(true).hasDescendant(By.text("Search your log"))).visibleBounds
        // Smaller writing that starts on the pill; strokes may run past its edges.
        val onPill = PointF(pill.left + HANDWRITING_INSET_DP * density, pill.bottom - SEARCH_BASELINE_INSET_DP * density)
        stylus.write(Stylus.hello(origin = onPill, unit = SEARCH_X_HEIGHT_DP * density)) {
            OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-search")
        }
        awaitTypedHello("stylus-search")
        OsSurfaceCapture.capture(surface = "keyboard-pointer-drag-and-drop", state = "stylus-search-typed")
    }

    /**
     * Waits for a photo to be open in the entry with its caption field in view. The keyboard the
     * photo came from closes when the photo opens, so the caption is not behind it.
     */
    private fun awaitPhotoInEntry(timeoutMs: Long): Boolean =
        OsSurfaceCapture.device.wait(Until.hasObject(By.text(PHOTO_CAPTION_HINT)), timeoutMs)

    /**
     * Opens a piece of writing in a new entry and returns its empty field's hint. A text capture
     * opens the writing by itself where the person's way to start an entry is writing, and choosing
     * Write again there would replace the piece that is open.
     */
    private fun startWriting(): UiObject2 {
        val hint = By.text(WRITING_HINT)
        if (!OsSurfaceCapture.device.wait(Until.hasObject(hint), WRITING_OPENS_MS)) OsSurfaceCapture.waitForText(WRITE).click()
        return OsSurfaceCapture.waitForText(WRITING_HINT)
    }

    /**
     * Waits for "hello" to be typed in a text field. If it never is, saves the screen and Gboard's
     * handwriting state under `failures/` and fails.
     */
    private fun awaitTypedHello(state: String) {
        val typed = OsSurfaceCapture.device.wait(Until.findObject(By.text(Pattern.compile("(?i)hello\\s*"))), 10_000L)
        if (typed != null) {
            Thread.sleep(1_000L)
            return
        }
        OsSurfaceCapture.capture(surface = "failures", state = "$state-not-typed")
        val handwriting = OsSurfaceCapture.shell("dumpsys input_method").lines().filter { "tylus" in it || "andwriting" in it }
        OsSurfaceCapture.writeEvidence(surface = "failures", state = "$state-not-typed", text = handwriting.joinToString("\n"))
        error("The handwriting was not typed ($state)")
    }

    /**
     * Puts the on-screen keyboard away, as a person does before writing with a pen. Back closes the
     * keyboard and leaves the entry focused. Gboard's small handwriting toolbar is left alone.
     */
    private fun hideOnScreenKeyboard() {
        val screenHeight = OsSurfaceCapture.device.displayHeight
        val keyboardShown =
            InstrumentationRegistry.getInstrumentation().uiAutomation.windows.any { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && bounds.height() > screenHeight / 4
            }
        if (!keyboardShown) return
        OsSurfaceCapture.device.pressBack()
        Thread.sleep(1_000L)
    }

    /** Closes the handwriting tutorial Gboard offers over the app the first time it reads a pen. */
    private fun dismissStylusTutorial() {
        OsSurfaceCapture.device.wait(Until.findObject(By.text("Try out your stylus")), 3_000L)?.let {
            OsSurfaceCapture.waitForText("Cancel").click()
        }
    }

    /**
     * Waits until Gboard can read handwriting. A fresh emulator's Gboard downloads its handwriting
     * model the first time a pen writes in a text field, and ignores the pen until the download
     * finishes, so a short stroke in [field] starts the download. Whatever the strokes typed is
     * cleared again.
     */
    private fun awaitHandwritingModel(
        stylus: Stylus,
        field: Rect,
    ) {
        fun ready() = OsSurfaceCapture.shell("dumpsys input_method").lines().any { "handwriting_recognition" in it && "_reco_" in it }
        if (ready()) return
        val y = field.centerY().toFloat()
        stylus.write(listOf(listOf(PointF(field.left + 100f, y), PointF(field.left + 160f, y))))
        val deadline = System.currentTimeMillis() + HANDWRITING_MODEL_TIMEOUT_MS
        while (!ready()) {
            check(System.currentTimeMillis() < deadline) { "Gboard did not finish downloading its handwriting model" }
            Thread.sleep(2_000L)
        }
        // Gboard offers its handwriting tutorial the first time it reads a pen, which can be now or
        // during a later capture, so one more stroke brings it up here, where it is closed.
        stylus.write(listOf(listOf(PointF(field.left + 100f, y), PointF(field.left + 160f, y))))
        dismissStylusTutorial()
        Thread.sleep(2_000L)
        OsSurfaceCapture.device.findObject(By.clazz("android.widget.EditText"))?.clear()
    }

    /**
     * Each widget as a launcher draws it, in light, dark, right-to-left, and 200% text. Rendering
     * does not depend on the screen, so it runs on the phone only.
     */
    @Test fun widgetRenders() {
        if (OsSurfaceCapture.formFactor != "phone") return
        val widgets =
            listOf(
                Triple("capture-widget", CaptureWidget(), 250 to 80),
                Triple("new-entry-widget", NewEntryWidget(), 250 to 180),
                Triple("on-this-day-widget", OnThisDayWidget(), 250 to 180),
                Triple("on-this-day-text-widget", OnThisDayWidget(previewState = TEXT_MEMORY), 250 to 180),
                Triple("pinned-memory-widget", FixedMemoryWidget(), 250 to 180),
            )
        for ((name, widget, size) in widgets) {
            for (look in WidgetRenders.Look.entries) {
                val image = WidgetRenders.render(widget, size.first, size.second, look)
                OsSurfaceCapture.saveImage(surface = "widgets", state = "$name-${look.label}", image = image)
            }
        }
    }

    @Test fun captureWidgetInPicker() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.LOCK_SCREEN_CAPTURE_WIDGET)
        OsSurfaceCapture.awaitComponentEnabled("app.logdate.client.feature.widgets.CaptureWidgetReceiver")
        OsSurfaceCapture.openLauncherShortcuts()
        OsSurfaceCapture.waitForText("Widgets").click()
        OsSurfaceCapture.waitForText("Memories")
        OsSurfaceCapture.scrollUntilText("Capture")
        Thread.sleep(2_000L)
        OsSurfaceCapture.capture(surface = "widgets", state = "capture-widget-picker")
    }

    /**
     * The Capture widget on the hub, the widget space a tablet shows over its lock screen while it
     * charges. Emulator images ship with the hub off, so this runs only on an emulator that
     * `scripts/hub-widget-screenshots.sh` prepared with the overlays that turn it on.
     */
    @Test fun captureWidgetOnHub() {
        assumeTrue(OsSurfaceCapture.shell("cmd overlay list com.android.systemui").contains("[x] $HUB_OVERLAY"))
        // The emulator starts without a lock screen; the hub needs one, and it is removed again after.
        OsSurfaceCapture.shell("locksettings set-disabled true")
        OsSurfaceCapture.shell("wm dismiss-keyguard")
        try {
            addCaptureWidgetToHub()
        } finally {
            OsSurfaceCapture.shell("locksettings set-disabled true")
            OsSurfaceCapture.shell("wm dismiss-keyguard")
        }
    }

    private fun addCaptureWidgetToHub() {
        val device = OsSurfaceCapture.device
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.LOCK_SCREEN_CAPTURE_WIDGET)
        OsSurfaceCapture.awaitComponentEnabled("app.logdate.client.feature.widgets.CaptureWidgetReceiver")

        OsSurfaceCapture.shell("locksettings set-disabled false")
        OsSurfaceCapture.shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1_500L)
        OsSurfaceCapture.shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(2_500L)
        openHub()
        device.wait(Until.findObject(By.textStartsWith("Let")), 3_000L)?.click()

        OsSurfaceCapture.waitForText("Add widgets").click()
        device.wait(Until.findObject(By.text("Got it")), 5_000L)?.click()
        // The picker's search field is labelled by its description; a Google widget is titled "Search".
        val search =
            device.wait(Until.findObject(By.desc("Search")), 5_000L)
                ?: error("The lock screen widget picker did not show its search field")
        search.click()
        device.wait(Until.findObject(By.focused(true)), 5_000L)?.text = "LogDate"
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        // The result row names the LogDate widgets offered for the lock screen. Only Capture, which
        // shows no journal content, may be there.
        val offered =
            device.wait(Until.findObject(By.text(LOGDATE_WIDGET_NAMES)), 5_000L)
                ?: error("The lock screen widget picker did not list LogDate")
        check(offered.text == "Capture") { "The lock screen picker offered journal widgets: ${offered.text}" }
        offered.click()
        Thread.sleep(2_000L)
        OsSurfaceCapture.capture(surface = "widgets", state = "capture-widget-hub-picker")

        // Tapping the preview, the widget image above its title, shows an Add button; adding opens the
        // hub's editor with the widget. The rightmost "Capture" is the preview's title, not the list row.
        val title =
            device.findObjects(By.text("Capture")).maxByOrNull { it.visibleBounds.centerX() }
                ?: error("The picker did not show the Capture widget's preview")
        device.click(title.visibleBounds.centerX(), title.visibleBounds.top - HUB_PREVIEW_ABOVE_TITLE_PX)
        val add =
            device.wait(Until.findObject(By.desc("Add Capture widget")), 5_000L)
                ?: error("The picker did not offer to add the Capture widget")
        add.click()
        // The first widget added shows a one-time note that opening apps from the hub needs an unlock.
        device.wait(Until.findObject(By.text("Got it")), 4_000L)?.click()
        OsSurfaceCapture.waitForText("Done").click()

        val write =
            device.wait(Until.findObject(By.desc("Write entry")), 10_000L)
                ?: error("The hub did not show the Capture widget")
        Thread.sleep(1_500L)
        OsSurfaceCapture.capture(surface = "widgets", state = "capture-widget-hub")

        // Write unlocks the device and opens a new entry.
        write.click()
        val opened = device.wait(Until.hasObject(By.pkg(OsSurfaceCapture.packageName).depth(0)), 15_000L)
        check(opened) { "Write on the hub did not open LogDate" }
        Thread.sleep(2_000L)
        OsSurfaceCapture.capture(surface = "widgets", state = "capture-widget-hub-write")
    }

    /**
     * Opens the hub with a slow swipe in from the right edge, as a person would. A swipe right after
     * the screen wakes can fall short and slide back, so it is repeated until the hub shows. The
     * swipe goes through `input`, because a swipe injected through UiDevice does not open the hub.
     */
    private fun openHub() {
        val device = OsSurfaceCapture.device
        val middle = device.displayHeight / 2
        repeat(HUB_SWIPE_ATTEMPTS) {
            OsSurfaceCapture.shell(
                "input touchscreen swipe ${device.displayWidth - 2} $middle ${device.displayWidth / 12} $middle $HUB_SWIPE_MS",
            )
            if (device.wait(Until.hasObject(HUB_CONTENT), 3_000L)) return
        }
        OsSurfaceCapture.dumpHierarchy("hub-closed")
        error("The hub did not open")
    }

    @Test fun pinJournal() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.openJournal(OsSurfaceCapture.createJournal("Summer in Lisbon"))
        val options =
            OsSurfaceCapture.device.wait(Until.findObject(By.desc("Journal options")), 5_000L)
                ?: OsSurfaceCapture.waitForText("Journal settings")
        options.click()
        OsSurfaceCapture.waitForText("Add to home screen")
        OsSurfaceCapture.capture(surface = "launcher-shortcuts", state = "journal-menu")
        OsSurfaceCapture.waitForText("Add to home screen").click()
        Thread.sleep(2_500L)
        OsSurfaceCapture.capture(surface = "launcher-shortcuts", state = "pin-journal")
    }

    /** Android 17 lets people float any app from its launcher menu; LogDate must work at that size. */
    @Test fun appBubble() {
        if (Build.VERSION.SDK_INT < 37) return
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.openLauncherShortcuts()
        OsSurfaceCapture.waitForText("Bubble").click()
        OsSurfaceCapture.device.wait(Until.findObject(By.desc("Search")), 10_000L)
        Thread.sleep(3_000L)
        OsSurfaceCapture.device.wait(Until.findObject(By.text("Got it")), 2_000L)?.click()
        OsSurfaceCapture.capture(surface = "bubbles", state = "app-bubble")
        OsSurfaceCapture.writeEvidence(surface = "bubbles", state = "app-bubble-window", text = OsSurfaceCapture.bubbleWindowReport())
    }

    @Test fun quickSettingsTilesAndLiveUpdate() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.QUICK_SETTINGS_TILES, FeatureFlag.LIVE_UPDATES)
        OsSurfaceCapture.addQuickSettingsTile("app.logdate.client.tiles.NewEntryTileService")
        OsSurfaceCapture.addQuickSettingsTile("app.logdate.client.tiles.RecordVoiceTileService")

        OsSurfaceCapture.openQuickSettings()
        val recordVoiceTile = OsSurfaceCapture.waitForText("Record voice")
        OsSurfaceCapture.capture(surface = "quick-settings-tiles", state = "idle")

        recordVoiceTile.click()
        OsSurfaceCapture.waitForText("Finish")
        OsSurfaceCapture.capture(surface = "quick-settings-tiles", state = "recording-editor")
        OsSurfaceCapture.writeEvidence(
            surface = "live-updates",
            state = "recording-promotion",
            text = OsSurfaceCapture.recordingPromotionReport(),
        )

        // Android shows a Live Update's chip only while its app is not on screen.
        OsSurfaceCapture.device.pressHome()
        Thread.sleep(3_000L)
        OsSurfaceCapture.capture(surface = "live-updates", state = "recording-status-chip")

        OsSurfaceCapture.openNotifications()
        OsSurfaceCapture.capture(surface = "live-updates", state = "recording-notification")
        OsSurfaceCapture.collapseShade()

        OsSurfaceCapture.openQuickSettings()
        OsSurfaceCapture.capture(surface = "quick-settings-tiles", state = "recording")
        OsSurfaceCapture.collapseShade()
    }

    /**
     * A recording started in a separate editor window, with Home in front of it: the active Record
     * voice tile has to bring that editor window forward, not Home.
     */
    @Test fun recordVoiceTileShowsRecordingWindow() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.QUICK_SETTINGS_TILES)
        OsSurfaceCapture.addQuickSettingsTile("app.logdate.client.tiles.RecordVoiceTileService")

        val pkg = OsSurfaceCapture.packageName
        // Home is open before the recording starts. A main window opened while a recording is
        // running opens on the recording, so Home has to be there already to end up in front of it.
        OsSurfaceCapture.openMainActivity()
        OsSurfaceCapture.waitForText("Search your log")
        OsSurfaceCapture.shell("am start -n $pkg/$EDITOR_ACTIVITY -f 0x10000000")
        OsSurfaceCapture.waitForText(VOICE).click()
        // The audio block opens ready to record; recording starts from its record button.
        OsSurfaceCapture.device
            .wait(Until.findObject(By.desc("Start Recording")), 10_000L)
            ?.click()
            ?: error("The audio block's record button did not appear")
        OsSurfaceCapture.waitForText("Finish")
        val recordingIn = topResumedActivity()

        OsSurfaceCapture.shell("am start -n $pkg/app.logdate.client.MainActivity -f 0x10000000")
        OsSurfaceCapture.waitForText("Search your log")
        val beforeTile = topResumedActivity()

        OsSurfaceCapture.openQuickSettings()
        OsSurfaceCapture.waitForText("Record voice").click()
        OsSurfaceCapture.waitForText("Finish")
        Thread.sleep(1_000L)
        val afterTile = topResumedActivity()
        OsSurfaceCapture.writeEvidence(
            surface = "quick-settings-tiles",
            state = "recording-window",
            text =
                buildString {
                    appendLine("# Front window while recording in a separate editor window")
                    appendLine(recordingIn)
                    appendLine()
                    appendLine("# Front window after bringing Home forward")
                    appendLine(beforeTile)
                    appendLine()
                    appendLine("# Front window after tapping the active Record voice tile")
                    appendLine(afterTile)
                },
        )
        check(EDITOR_ACTIVITY in recordingIn) { "The recording did not start in an editor window: $recordingIn" }
        check("MainActivity" in beforeTile) { "Home did not come forward: $beforeTile" }
        check(EDITOR_ACTIVITY in afterTile) { "The tile did not bring the recording's window forward: $afterTile" }
        OsSurfaceCapture.device.findObject(By.text("Finish"))?.click()
    }

    /** The activity Android reports as the top resumed one, with its task. */
    private fun topResumedActivity(): String =
        OsSurfaceCapture
            .shell("dumpsys activity activities")
            .lines()
            .firstOrNull { "topResumedActivity=" in it }
            ?.trim()
            .orEmpty()

    @Test fun recordingBubble() {
        OsSurfaceCapture.prepareOnboardedApp()
        OsSurfaceCapture.enableFlags(FeatureFlag.RECORDING_BUBBLE, FeatureFlag.LIVE_UPDATES)
        OsSurfaceCapture.allowBubbles()

        OsSurfaceCapture.openCapture(mode = "voice")
        Thread.sleep(6_000L)

        OsSurfaceCapture.openNotifications()
        OsSurfaceCapture.waitForText("Float")
        OsSurfaceCapture.capture(surface = "bubbles", state = "float-action")
        OsSurfaceCapture.device.findObject(By.text("Float")).click()
        OsSurfaceCapture.collapseShade()

        // On phones the bubble opens by itself. On tablets it lands in the Bubble Bar; open it there.
        if (OsSurfaceCapture.device.wait(Until.findObject(By.desc("Open LogDate")), 4_000L) == null) {
            OsSurfaceCapture.waitForText("Voice recorder").click()
        }
        OsSurfaceCapture.waitForText("Open LogDate")
        // The first bubble shows Android's one-time "Control bubbles anytime" tip; dismiss it.
        OsSurfaceCapture.device.wait(Until.findObject(By.text("Got it")), 3_000L)?.click()
        Thread.sleep(2_000L)
        OsSurfaceCapture.capture(surface = "bubbles", state = "recorder-expanded")
        OsSurfaceCapture.writeEvidence(surface = "bubbles", state = "recorder-window", text = OsSurfaceCapture.bubbleWindowReport())

        OsSurfaceCapture.device.pressHome()
        Thread.sleep(1_500L)
        OsSurfaceCapture.capture(surface = "bubbles", state = "recorder-collapsed")
    }

    private companion object {
        /** The entry page's choices that start a piece of writing and a recording, named by what they say. */
        const val WRITE = "Write"
        const val VOICE = "Voice"

        /** The heading of today's entries in the timeline. */
        const val TODAY = "Today"

        /** How long the day is given to open beside the timeline before it is taken to have replaced it. */
        const val DAY_BESIDE_TIMELINE_MS = 5_000L

        /** What an empty piece of writing says, and how long a text capture gets to open one by itself. */
        const val WRITING_HINT = "What's on your mind?"
        const val WRITING_OPENS_MS = 3_000L

        /** The hint in an open photo's empty caption. */
        const val PHOTO_CAPTION_HINT = "Add a caption..."

        /** `WindowConfiguration.WINDOWING_MODE_FREEFORM`, which `am start --windowingMode` takes. */
        const val WINDOWING_MODE_FREEFORM = 5

        /** The emulator overlay `scripts/hub-widget-screenshots.sh` installs to turn the hub on. */
        const val HUB_OVERLAY = "studio.hypertext.logdate.emulator.hubsystemui"

        /** The lock screen picker's list of LogDate widget names, such as "Memories, Capture". */
        val LOGDATE_WIDGET_NAMES: Pattern = Pattern.compile("(Memories|Pinned Memory|New Entry|Capture)(, .+)?")

        /** How far above a widget's title in the lock screen picker its preview image sits. */
        const val HUB_PREVIEW_ABOVE_TITLE_PX = 140

        /** About a second of swipe; a quicker swipe from the edge slides back before the hub opens. */
        const val HUB_SWIPE_MS = 900
        const val HUB_SWIPE_ATTEMPTS = 3

        /** The open hub's own label; the plain lock screen is labelled "Lock screen". */
        val HUB_CONTENT: BySelector = By.desc("Widgets on lock screen")

        /** How many times an assistant call is tried while the function registry settles. */
        const val FUNCTION_LOOKUP_ATTEMPTS = 15

        const val EDITOR_ACTIVITY = "app.logdate.client.EditorActivity"
        const val WINDOW_TITLE_ENTRY = "Ferry to Cacilhas at sunset"
        val SWITCHER_ITEM: Pattern = Pattern.compile(".*release key bindings to select.*")
        const val EXTRA_HANDOFF_DRAFT_ID = "app.logdate.extra.HANDOFF_DRAFT_ID"
        const val HANDOFF_ENTRY = "Halfway up the ridge trail and the fog is finally lifting. Finishing this at home."
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        const val KEYBOARD_SUGGESTION_TIMEOUT_MS = 15_000L
        const val GIF_PANEL_TIMEOUT_MS = 30_000L
        val GIF_DESCRIPTION: Pattern = Pattern.compile(".{24,}")

        /** The Files app, opened at Downloads, for the cross-app drag. */
        const val FILES_PACKAGE = "com.google.android.documentsui"
        const val FILES_ACTIVITY = "$FILES_PACKAGE/com.android.documentsui.files.FilesActivity"
        const val DOWNLOADS_ROOT = "content://com.android.providers.downloads.documents/root/downloads"
        const val DRAGGED_PHOTO = "milo-after-the-rain.jpg"

        /** The Library photo dragged into another window, and how editor windows show in Recents. */
        const val LIBRARY_PHOTO_CAPTION = "Milo after the rain"
        const val EDITOR_TASK_AFFINITY = ":.editor"

        /** The account button's name, which its tooltip shows. */
        const val ACCOUNT_LABEL = "Account, backup and settings"

        /** About a second of holding still: `UiDevice.swipe` takes 5 ms a step. */
        const val LONG_PRESS_STEPS = 200

        /** Where the handwriting starts in the entry's text field, and how large it is, in dp. */
        const val HANDWRITING_INSET_DP = 48
        const val HANDWRITING_BASELINE_DP = 160
        const val HANDWRITING_X_HEIGHT_DP = 40
        const val HANDWRITING_MODEL_TIMEOUT_MS = 180_000L

        /** Writing on the search pill: smaller, with the baseline just inside the pill's bottom edge. */
        const val SEARCH_X_HEIGHT_DP = 20
        const val SEARCH_BASELINE_INSET_DP = 10

        /** A memory with no photo, so the widget shows its text, which follows the font size. */
        val TEXT_MEMORY =
            OnThisDayWidgetState.HasMemory(
                dateIso = "2025-10-10",
                dateFormatted = "October 10, 2025",
                summary = "We stayed out until the rain stopped, then walked home the long way.",
                thumbnailUri = null,
            )

        /** `logdate_splash_background`, the launcher icon's brown. */
        const val SPLASH_BACKGROUND = 0xFF3B2929.toInt()
    }
}
