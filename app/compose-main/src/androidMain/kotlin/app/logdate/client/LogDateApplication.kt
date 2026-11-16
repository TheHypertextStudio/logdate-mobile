package app.logdate.client

import android.app.Application
import android.content.Context
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
import app.logdate.client.launch.initializeLoggingAndKoin
import app.logdate.client.launch.installStrictModeIfDebuggable
import app.logdate.client.launch.observeNativeRestoreDeliveryCompletions
import app.logdate.client.launch.openJournalStorage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.context.GlobalContext

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
            awaitAndroidUserUnlock()
            withContext(Dispatchers.Main.immediate) { initializeUnlockedApplication() }
            openJournalStorage()
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

/** Initializes the foreground runtime when Android retained a restore-agent Application instance. */
fun ensureLogDateApplicationStarted(application: Application) {
    ApplicationStartupCoordinator.start(application)
}
