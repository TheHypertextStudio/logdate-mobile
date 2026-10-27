@file:Suppress("ktlint:standard:filename")

package app.logdate.client

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.ambient.AmbientPromptSchedulingObserver
import app.logdate.client.calendar.CalendarImportScheduler
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.recommendation.AmbientPromptTriggerContext
import app.logdate.client.events.EventInferenceScheduler
import app.logdate.client.feature.widgets.publishWidgetPreviews
import app.logdate.client.image.DataSaverImageInterceptor
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.rewind.RewindGenerationScheduler
import app.logdate.client.shortcuts.DynamicShortcutRefreshObserver
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.di.initializeKoin
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Application class for the LogDate Android app.
 *
 * For app UI, see [MainActivity].
 */
class LogdateApplication :
    Application(),
    KoinComponent,
    SingletonImageLoader.Factory {
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
        Napier.i("Application onCreate: initializing logging and DI", tag = APP_STARTUP_TAG)

        installStrictModeIfDebuggable()

        initializeLogging()
        initializeKoin()
        Napier.i("Application onCreate: Koin initialized", tag = APP_STARTUP_TAG)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            publishWidgetPreviews(this@LogdateApplication)
        }
        runCatching {
            LogDateNotificationRegistrar(this).registerAllPhoneChannels()
        }.onFailure { error ->
            Napier.w("Failed to register notification channels on app startup", error)
        }
        runCatching {
            get<StoredMediaReferenceMigrationLauncher>().start()
        }.onFailure { error ->
            Napier.w("Failed to start the media reference migration", error)
        }
        runCatching {
            get<AmbientPromptSchedulingObserver>().start()
            get<AmbientPromptScheduler>().enqueueImmediateEvaluation(AmbientPromptTriggerContext.PERIODIC)
        }.onFailure { error ->
            Napier.w("Failed to initialize ambient prompt scheduling", error)
        }
        runCatching {
            get<LocationTrackingManager>().startTracking()
        }.onFailure { error ->
            Napier.w("Failed to start location tracking manager on app startup", error)
        }
        runCatching {
            val rewindScheduler = RewindGenerationScheduler(this)
            rewindScheduler.schedulePeriodicGeneration()
            rewindScheduler.disableAnnualGeneration()
            rewindScheduler.enqueueImmediateCheck()
        }.onFailure { error ->
            Napier.w("Failed to initialize rewind generation scheduling", error)
        }
        runCatching {
            val scheduler = EventInferenceScheduler(this)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicInference() else scheduler.disableForLaunch()
        }.onFailure { error ->
            Napier.w("Failed to initialize event inference scheduling", error)
        }
        runCatching {
            val scheduler = CalendarImportScheduler(this)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicImport() else scheduler.disableForLaunch()
        }.onFailure { error ->
            Napier.w("Failed to initialize calendar import scheduling", error)
        }
        runCatching {
            val shortcutScheduler = get<DynamicShortcutScheduler>()
            shortcutScheduler.schedulePeriodicRefresh()
            shortcutScheduler.enqueueImmediateRefresh()
            get<DynamicShortcutRefreshObserver>().start()
        }.onFailure { error ->
            Napier.w("Failed to initialize dynamic shortcut scheduling", error)
        }
    }

    private fun initializeLogging() {
        // Napier's DebugAntilog derives its tag by walking the stack trace at a fixed depth. R8
        // inlines frames away, so in a minified build that index runs off the end and every
        // Napier call throws ArrayIndexOutOfBoundsException - crashing the app from inside its
        // own error logging. LogcatAntilog does no stack introspection, so it is safe minified,
        // and it keeps warnings and errors in release builds: without them a failing release
        // install produces no on-device output at all.
        Napier.base(LogcatAntilog(isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0))
        Napier.base(CrashlyticsAntilog())
    }

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

private const val APP_STARTUP_TAG = "LogDateStartup"
private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_MAX_BYTES = 250L * 1024 * 1024 // 250 MB
private const val IMAGE_CROSSFADE_MS = 200
