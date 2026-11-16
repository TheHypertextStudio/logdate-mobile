package app.logdate.client.launch

import android.app.Application
import app.logdate.client.LogDateApplication
import app.logdate.client.data.restore.resumeNativeAccountRecovery
import app.logdate.client.data.restore.resumeRetainedJournalReconciliation
import app.logdate.client.device.restore.nativeRestoreDeliveryCompletions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.koin.core.context.GlobalContext
import java.util.WeakHashMap

/**
 * Finds this process's [ApplicationStartup] and starts it.
 *
 * A process normally runs [LogDateApplication], which owns its startup. Android can also open an
 * activity in the plain [Application] that a restore agent's process created. That process gets an
 * equivalent startup here instead of crashing on a cast.
 */
internal object ApplicationStartupCoordinator {
    private val retainedApplicationStartups = WeakHashMap<Application, ApplicationStartup>()

    /** Starts the journal, or retries it after a failure, and returns the startup to observe. */
    fun start(application: Application): ApplicationStartup = startupFor(application).also { it.start() }

    private fun startupFor(application: Application): ApplicationStartup {
        if (application is LogDateApplication) return application.startup
        return synchronized(retainedApplicationStartups) {
            retainedApplicationStartups.getOrPut(application) { retainedApplicationStartup(application) }
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private fun retainedApplicationStartup(application: Application): ApplicationStartup {
        setResourceReaderAndroidContext(application.applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val startup =
            ApplicationStartup(
                scope,
                startDeviceWork = { AndroidApplicationRuntime(application, GlobalContext.get()).start() },
            ) { openRetainedApplication(application) }
        observeNativeRestoreDeliveryCompletions(scope, nativeRestoreDeliveryCompletions(), startup) {
            resumeRetainedJournalReconciliation(application)
        }
        return startup
    }

    /** The steps [LogDateApplication] takes in onCreate and its own startup, for a plain [Application]. */
    private suspend fun openRetainedApplication(application: Application) {
        application.awaitAndroidUserUnlock()
        withContext(Dispatchers.Main.immediate) { application.initializeLoggingAndKoin() }
        resumeNativeAccountRecovery(application)
        resumeRetainedJournalReconciliation(application)
        application.openJournalStorage()
    }
}
