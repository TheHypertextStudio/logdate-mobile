@file:Suppress("ktlint:standard:filename")

package app.logdate.client

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import app.logdate.client.activity.PhoneActivityAwareness
import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.ambient.AmbientPromptSchedulingObserver
import app.logdate.client.bubble.RecordingBubbleNotifier
import app.logdate.client.calendar.CalendarImportScheduler
import app.logdate.client.data.account.RestoreCredentialLifecycle
import app.logdate.client.data.notes.NativeMediaQuarantine
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.data.restore.resumeNativeAccountRecovery
import app.logdate.client.data.restore.resumeRetainedJournalReconciliation
import app.logdate.client.data.search.AndroidPlatformSearchIndexManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.nativeRestoreDeliveryCompletions
import app.logdate.client.domain.recommendation.AmbientPromptTriggerContext
import app.logdate.client.domain.reminders.ReminderSignalObserver
import app.logdate.client.events.EventInferenceScheduler
import app.logdate.client.feature.widgets.isWidgetIngressProcess
import app.logdate.client.feature.widgets.publishWidgetPreviews
import app.logdate.client.feature.widgets.resumeWidgetLifecycle
import app.logdate.client.image.DataSaverImageInterceptor
import app.logdate.client.launch.ApplicationStartup
import app.logdate.client.launch.awaitAndroidUserUnlock
import app.logdate.client.launch.observeNativeRestoreDeliveryCompletions
import app.logdate.client.launch.recoverCompletedRecordingFiles
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.notifications.RecordingBubbleSwitch
import app.logdate.client.platform.PlatformComponentToggler
import app.logdate.client.restore.NativeMediaQuarantineScheduler
import app.logdate.client.restore.awaitNativeMediaQuarantine
import app.logdate.client.rewind.RewindGenerationScheduler
import app.logdate.client.shortcuts.DynamicShortcutRefreshObserver
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.v2.PhoneStatePublisher
import app.logdate.client.sync.v2.link.PhoneLinkInitiator
import app.logdate.client.sync.v2.receipts.WatchReceiptNotifier
import app.logdate.client.sync.v2.window.WatchWindowPublisher
import app.logdate.client.tiles.RecordVoiceTileUpdater
import app.logdate.client.tiles.RecordingWindowTracker
import app.logdate.client.watch.presence.WatchPresenceRegistration
import app.logdate.di.initializeKoin
import app.logdate.feature.core.settings.account.recovery.AutomaticAccountRecovery
import app.logdate.ui.media.addLocalMedia
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext
import java.util.WeakHashMap

/**
 * Application class for the LogDate Android app.
 *
 * For app UI, see [MainActivity].
 */
class LogdateApplication :
    Application(),
    KoinComponent,
    SingletonImageLoader.Factory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dependenciesInitialized = false
    internal val startup =
        ApplicationStartup(startupScope, startDeviceWork = ::startBackgroundWork) {
            try {
                awaitAndroidUserUnlock()
                withContext(Dispatchers.Main.immediate) { initializeUnlockedApplication() }
                recoverCompletedRecordingFiles(filesDir)
                get<LogDateDatabase>()
                val detector = PostRestoreDetector(this@LogdateApplication)
                awaitNativeMediaQuarantine(
                    detector::pendingMediaQuarantineId,
                    get<NativeMediaQuarantine>()::run,
                    detector::acknowledgeMediaQuarantine,
                    { NativeMediaQuarantineScheduler.enqueue(this@LogdateApplication, it) },
                )
                get<AndroidPlatformSearchIndexManager>()
                Napier.i("Encrypted storage ready", tag = APP_STARTUP_TAG)
            } catch (error: Exception) {
                Napier.e("Application storage initialization failed", error, tag = APP_STARTUP_TAG)
                throw error
            }
        }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader
            .Builder(context)
            // Cross-fade every image as it loads. Without this, thumbnails
            // pop in abruptly when the placeholder color is replaced; with
            // it, the gallery and timeline feel smooth on scroll.
            .crossfade(IMAGE_CROSSFADE_MS)
            .components {
                // Stored media strings name files by LogDate reference or by a path that may come
                // from another install; resolve them before Coil's own file handling sees them.
                addLocalMedia(get())
                add(VideoFrameDecoder.Factory())
                add(DataSaverImageInterceptor(get<DataUsagePolicy>()))
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                    .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                    .build()
            }.build()

    override fun onCreate() {
        super.onCreate()
        if (isWidgetIngressProcess(packageName)) return
        observeNativeRestoreDeliveryCompletions(startupScope, nativeRestoreDeliveryCompletions(), startup) {
            resumeRetainedJournalReconciliation(this@LogdateApplication)
        }
        startupScope.launch(Dispatchers.Main.immediate) {
            awaitAndroidUserUnlock()
            initializeUnlockedApplication()
        }
    }

    private fun initializeUnlockedApplication() {
        if (dependenciesInitialized) return
        Napier.i("Application onCreate: initializing logging and DI", tag = APP_STARTUP_TAG)

        installStrictModeIfDebuggable()

        // Napier's DebugAntilog derives its tag by walking the stack trace at a fixed depth. R8
        // inlines frames away, so in a minified build that index runs off the end and every
        // Napier call throws ArrayIndexOutOfBoundsException - crashing the app from inside its
        // own error logging. LogcatAntilog does no stack introspection, so it is safe minified,
        // and it keeps warnings and errors in release builds: without them a failing release
        // install produces no on-device output at all.
        Napier.base(LogcatAntilog(isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0))
        Napier.base(CrashlyticsAntilog())
        initializeKoin()
        dependenciesInitialized = true
        startupScope.launch { resumeNativeAccountRecovery(this@LogdateApplication) }
        startupScope.launch { resumeRetainedJournalReconciliation(this@LogdateApplication) }
        resumeWidgetLifecycle(this, startupScope)
        Napier.i("Application onCreate: Koin initialized", tag = APP_STARTUP_TAG)
    }

    /**
     * Starts the work that runs alongside the app: notification channels, migrations, and the
     * schedulers. Each part is isolated so one failing to start doesn't stop the others.
     */
    private fun startBackgroundWork() = AndroidApplicationRuntime(this, GlobalContext.get()).start()

    /**
     * Surfaces main-thread disk reads / writes, network, slow custom calls, and leaked closables
     * in debug builds. Violations are logged but don't crash — too aggressive for normal dev
     * iteration. The whole block is no-op in release.
     */
    private fun installStrictModeIfDebuggable() {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        runCatching {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }.onFailure { error ->
            Napier.w("Failed to install StrictMode policies", error)
        }
    }
}

/** Starts the app's device-owned work from either the normal process or backup-agent process. */
private class AndroidApplicationRuntime(
    private val application: Application,
    private val koin: org.koin.core.Koin,
) {
    fun start() {
        isolated("Failed to start restore credential cleanup reconciliation") {
            resolve<RestoreCredentialLifecycle>().start(resolve<CoroutineScope>())
        }
        preserveExistingIdentityBackup()
        publishWidgetPreviewsInBackground()
        isolated("Failed to register notification channels on app startup") {
            LogDateNotificationRegistrar(application).registerAllPhoneChannels()
        }
        isolated("Failed to start the media reference migration") {
            resolve<StoredMediaReferenceMigrationLauncher>().start()
        }
        isolated("Failed to initialize ambient prompt scheduling") { startAmbientScheduling() }
        isolated("Failed to observe reminder signals") {
            resolve<ReminderSignalObserver>().start()
        }
        isolated("Failed to start setting up account recovery") {
            resolve<AutomaticAccountRecovery>().start()
        }
        isolated("Failed to start location tracking manager on app startup") {
            resolve<LocationTrackingManager>().startTracking()
        }
        isolated("Failed to initialize rewind generation scheduling") {
            val rewindScheduler = RewindGenerationScheduler(application)
            rewindScheduler.schedulePeriodicGeneration()
            rewindScheduler.disableAnnualGeneration()
            rewindScheduler.enqueueImmediateCheck()
        }
        isolated("Failed to initialize event inference scheduling") {
            val scheduler = EventInferenceScheduler(application)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicInference() else scheduler.disableForLaunch()
        }
        isolated("Failed to initialize calendar import scheduling") {
            val scheduler = CalendarImportScheduler(application)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicImport() else scheduler.disableForLaunch()
        }
        isolated("Failed to initialize dynamic shortcut scheduling") {
            val shortcutScheduler = resolve<DynamicShortcutScheduler>()
            shortcutScheduler.schedulePeriodicRefresh()
            shortcutScheduler.enqueueImmediateRefresh()
            resolve<DynamicShortcutRefreshObserver>().start()
        }
        isolated("Failed to start syncing flag-gated components") {
            resolve<PlatformComponentToggler>().start(resolve<CoroutineScope>())
            resolve<RecordVoiceTileUpdater>().start(resolve<CoroutineScope>())
            resolve<RecordingWindowTracker>().start(application, resolve<CoroutineScope>())
            resolve<RecordingBubbleNotifier>().start(resolve<CoroutineScope>())
            resolve<LiveUpdatesSwitch>()
            resolve<RecordingBubbleSwitch>()
        }
        startPhoneStatePublishing()
        startActivityAwareness()
        startWatchLinking()
        startWatchReceipts()
        runCatching { resolve<WatchWindowPublisher>().start(resolve<CoroutineScope>()) }
            .onFailure { Napier.w("Failed to start publishing watch libraries", it) }
    }

    private fun startAmbientScheduling() {
        resolve<AmbientPromptSchedulingObserver>().start()
        val scheduler = resolve<AmbientPromptScheduler>()
        resolve<CoroutineScope>().launch {
            try {
                scheduler.enqueueImmediateEvaluation(AmbientPromptTriggerContext.PERIODIC)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Ambient prompt evaluation will reconcile on the next startup", error)
            }
        }
    }

    private fun preserveExistingIdentityBackup() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { resolve<IdentityKeyManager>().ensureExistingIdentityBackup() }
                .onFailure { Napier.w("Native encryption recovery material needs attention", it) }
        }
    }

    private fun publishWidgetPreviewsInBackground() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            publishWidgetPreviews(application)
        }
    }

    private inline fun <reified T : Any> resolve(): T = koin.get()

    private inline fun isolated(
        failure: String,
        start: () -> Unit,
    ) {
        runCatching(start).onFailure { error -> Napier.w(failure, error) }
    }

    private fun startWatchReceipts() {
        runCatching {
            resolve<WatchReceiptNotifier>().start(
                resolve<CoroutineScope>(),
                resolve<SyncMetadataService>().observePendingCount(),
            )
        }.onFailure { error ->
            Napier.w("Failed to start telling watches which notes are backed up", error)
        }
    }

    private fun startWatchLinking() {
        runCatching {
            resolve<PhoneLinkInitiator>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start linking this phone's watches", error)
        }
    }

    private fun startActivityAwareness() {
        runCatching {
            resolve<PhoneActivityAwareness>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start following the phone's activity", error)
        }
    }

    private fun startPhoneStatePublishing() {
        runCatching {
            resolve<PhoneStatePublisher>().start(resolve<CoroutineScope>())
            resolve<WatchPresenceRegistration>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start publishing this phone's state to its watches", error)
        }
    }
}

private const val APP_STARTUP_TAG = "LogDateStartup"
private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_MAX_BYTES = 250L * 1024 * 1024 // 250 MB
private const val IMAGE_CROSSFADE_MS = 200

/** Starts the application runtime only when an activity or foreground service requests it. */
internal fun startApplicationRuntime(startup: ApplicationStartup) {
    startup.start()
}

/**
 * Android may launch the activity in the base Application instance retained by a restore-agent
 * process. Keep foreground startup usable in that process instead of crashing on an unsafe cast.
 */
internal object ApplicationStartupCoordinator {
    private val fallbackStartups = WeakHashMap<Application, ApplicationStartup>()

    @OptIn(ExperimentalResourceApi::class)
    fun get(application: Application): ApplicationStartup {
        if (application is LogdateApplication) return application.startup.also(::startApplicationRuntime)
        return synchronized(fallbackStartups) {
            fallbackStartups
                .getOrPut(application) {
                    setResourceReaderAndroidContext(application.applicationContext)
                    val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val startup =
                        ApplicationStartup(
                            startupScope,
                            startDeviceWork = { AndroidApplicationRuntime(application, GlobalContext.get()).start() },
                        ) {
                            try {
                                application.awaitAndroidUserUnlock()
                                withContext(Dispatchers.Main.immediate) {
                                    if (GlobalContext.getOrNull() == null) {
                                        val isDebuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                                        Napier.base(LogcatAntilog(isDebuggable = isDebuggable))
                                        Napier.base(CrashlyticsAntilog())
                                        application.initializeKoin()
                                    }
                                }
                                resumeNativeAccountRecovery(application)
                                resumeRetainedJournalReconciliation(application)
                                recoverCompletedRecordingFiles(application.filesDir)
                                val koin = GlobalContext.get()
                                koin.get<LogDateDatabase>()
                                val detector = PostRestoreDetector(application)
                                awaitNativeMediaQuarantine(
                                    detector::pendingMediaQuarantineId,
                                    koin.get<NativeMediaQuarantine>()::run,
                                    detector::acknowledgeMediaQuarantine,
                                    { NativeMediaQuarantineScheduler.enqueue(application, it) },
                                )
                                koin.get<AndroidPlatformSearchIndexManager>()
                                Napier.i("Encrypted storage ready", tag = APP_STARTUP_TAG)
                            } catch (error: Exception) {
                                Napier.e("Application storage initialization failed", error, tag = APP_STARTUP_TAG)
                                throw error
                            }
                        }
                    observeNativeRestoreDeliveryCompletions(startupScope, nativeRestoreDeliveryCompletions(), startup) {
                        resumeRetainedJournalReconciliation(application)
                    }
                    startup
                }.also(::startApplicationRuntime)
        }
    }
}

/** Initializes the foreground runtime when Android retained a restore-agent Application instance. */
fun ensureLogDateApplicationStarted(application: Application) {
    ApplicationStartupCoordinator.get(application)
}
