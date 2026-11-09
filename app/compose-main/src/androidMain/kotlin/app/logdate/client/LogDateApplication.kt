package app.logdate.client

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import app.logdate.client.data.notes.NativeMediaQuarantine
import app.logdate.client.data.restore.resumeNativeAccountRecovery
import app.logdate.client.data.restore.resumeRetainedJournalReconciliation
import app.logdate.client.data.search.AndroidPlatformSearchIndexManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.nativeRestoreDeliveryCompletions
import app.logdate.client.feature.widgets.isWidgetIngressProcess
import app.logdate.client.feature.widgets.resumeWidgetLifecycle
import app.logdate.client.image.createLogDateImageLoader
import app.logdate.client.launch.AndroidApplicationRuntime
import app.logdate.client.launch.ApplicationStartup
import app.logdate.client.launch.awaitAndroidUserUnlock
import app.logdate.client.launch.installStrictModeIfDebuggable
import app.logdate.client.launch.observeNativeRestoreDeliveryCompletions
import app.logdate.client.launch.recoverCompletedRecordingFiles
import app.logdate.client.restore.NativeMediaQuarantineScheduler
import app.logdate.client.restore.awaitNativeMediaQuarantine
import app.logdate.di.initializeKoin
import coil3.ImageLoader
import coil3.SingletonImageLoader
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext

private const val APP_STARTUP_TAG = "LogDateStartup"

/**
 * Application class for the LogDate Android app.
 *
 * For app UI, see [MainActivity].
 */
class LogDateApplication :
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
                val detector = PostRestoreDetector(this@LogDateApplication)
                awaitNativeMediaQuarantine(
                    detector::pendingMediaQuarantineId,
                    get<NativeMediaQuarantine>()::run,
                    detector::acknowledgeMediaQuarantine,
                    { NativeMediaQuarantineScheduler.enqueue(this@LogDateApplication, it) },
                )
                get<AndroidPlatformSearchIndexManager>()
                Napier.i("Encrypted storage ready", tag = APP_STARTUP_TAG)
            } catch (error: Exception) {
                Napier.e("Application storage initialization failed", error, tag = APP_STARTUP_TAG)
                throw error
            }
        }

    override fun newImageLoader(context: Context): ImageLoader = createLogDateImageLoader(context, getKoin())

    override fun onCreate() {
        super.onCreate()
        if (isWidgetIngressProcess(packageName)) return
        observeNativeRestoreDeliveryCompletions(startupScope, nativeRestoreDeliveryCompletions(), startup) {
            resumeRetainedJournalReconciliation(this@LogDateApplication)
        }
        startupScope.launch(Dispatchers.Main.immediate) {
            awaitAndroidUserUnlock()
            initializeUnlockedApplication()
        }
    }

    private fun initializeUnlockedApplication() {
        if (dependenciesInitialized) return
        Napier.i("Application onCreate: initializing logging and DI", tag = APP_STARTUP_TAG)

        installStrictModeIfDebuggable(applicationInfo)

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
        startupScope.launch { resumeNativeAccountRecovery(this@LogDateApplication) }
        startupScope.launch { resumeRetainedJournalReconciliation(this@LogDateApplication) }
        resumeWidgetLifecycle(this, startupScope)
        Napier.i("Application onCreate: Koin initialized", tag = APP_STARTUP_TAG)
    }

    /**
     * Starts the work that runs alongside the app: notification channels, migrations, and the
     * schedulers. Each part is isolated so one failing to start doesn't stop the others.
     */
    private fun startBackgroundWork() = AndroidApplicationRuntime(this, GlobalContext.get()).start()
}

/**
 * Legacy compatibility alias for [LogDateApplication].
 */
typealias LogdateApplication = LogDateApplication

/** Initializes the foreground runtime when Android retained a restore-agent Application instance. */
fun ensureLogDateApplicationStarted(application: Application) {
    ApplicationStartupCoordinator.get(application)
}
