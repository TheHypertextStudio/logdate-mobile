package app.logdate.client

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import app.logdate.client.data.restore.discardAbandonedNativeIosTransfer
import app.logdate.client.data.restore.resumeNativeAccountRecovery
import app.logdate.client.data.restore.resumeRetainedJournalReconciliation
import app.logdate.client.device.restore.nativeRestoreDeliveryCompletions
import app.logdate.client.feature.widgets.isWidgetIngressProcess
import app.logdate.client.feature.widgets.resumeWidgetLifecycle
import app.logdate.client.image.createLogDateImageLoader
import app.logdate.client.launch.APP_STARTUP_TAG
import app.logdate.client.launch.AndroidApplicationRuntime
import app.logdate.client.launch.ApplicationStartup
import app.logdate.client.launch.ApplicationStartupCoordinator
import app.logdate.client.launch.awaitAndroidUserUnlock
import app.logdate.client.launch.awaitDependencies
import app.logdate.client.launch.initializeLoggingAndKoin
import app.logdate.client.launch.installStrictModeIfDebuggable
import app.logdate.client.launch.observeNativeRestoreDeliveryCompletions
import app.logdate.client.launch.openJournalStorage
import app.logdate.client.launch.processDependenciesStarted
import app.logdate.di.logDateWorkManagerConfiguration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.context.GlobalContext
import kotlin.time.Duration.Companion.minutes

/**
 * Application class for the LogDate Android app.
 *
 * For app UI, see [MainActivity].
 */
class LogDateApplication :
    Application(),
    KoinComponent,
    Configuration.Provider,
    SingletonImageLoader.Factory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dependenciesInitialized = false

    internal val startup =
        ApplicationStartup(startupScope, startDeviceWork = ::startBackgroundWork) {
            awaitAndroidUserUnlock()
            withContext(Dispatchers.Main.immediate) { initializeUnlockedApplication() }
            openJournalStorage()
        }

    /**
     * Lets WorkManager set itself up the moment it is first needed. Android can start this process
     * to run a scheduled job and create the job's service before [onCreate]'s startup has reached
     * Koin, and WorkManager crashes the process if it is not set up by then.
     */
    override val workManagerConfiguration: Configuration
        get() = logDateWorkManagerConfiguration()

    override fun newImageLoader(context: Context): ImageLoader = createLogDateImageLoader(context, getKoin())

    override fun onCreate() {
        super.onCreate()
        if (isWidgetIngressProcess(packageName)) return
        observeNativeRestoreDeliveryCompletions(startupScope, nativeRestoreDeliveryCompletions(), startup) {
            resumeRetainedJournalReconciliation(this@LogDateApplication)
        }
        startupScope.launch(Dispatchers.Main.immediate) {
            awaitAndroidUserUnlock()
            val cleanup =
                runCatching {
                    withContext(Dispatchers.IO) {
                        discardAbandonedNativeIosTransfer(this@LogDateApplication)
                    }
                }
            cleanup.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            initializeUnlockedApplication()
            cleanup.onFailure { Napier.w("Native transfer cleanup remains pending", it) }
        }
    }

    private fun initializeUnlockedApplication() {
        if (dependenciesInitialized) return
        installStrictModeIfDebuggable(applicationInfo)
        initializeLoggingAndKoin()
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

/**
 * Starts the foreground runtime, including in a restore-agent Application instance Android retained,
 * and blocks until Koin has started so the caller can read `GlobalContext` right away. The journal may
 * still be opening when it returns. Call it off the main thread, which starts Koin.
 *
 * The app/android-main androidTest suites start the app with it; tests are not roots.
 */
@Suppress(names = ["unused"])
fun ensureLogDateApplicationStarted(application: Application) {
    val startup = ApplicationStartupCoordinator.start(application)
    if (GlobalContext.getOrNull() != null) return
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "ensureLogDateApplicationStarted waits for the main thread to start Koin; call it from another thread"
    }
    runBlocking { withTimeout(2.minutes) { startup.awaitDependencies(processDependenciesStarted) } }
}
