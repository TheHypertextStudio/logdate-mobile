package app.logdate.client

import android.app.Application
import android.content.pm.ApplicationInfo
import app.logdate.client.data.notes.NativeMediaQuarantine
import app.logdate.client.data.restore.resumeNativeAccountRecovery
import app.logdate.client.data.restore.resumeRetainedJournalReconciliation
import app.logdate.client.data.search.AndroidPlatformSearchIndexManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.nativeRestoreDeliveryCompletions
import app.logdate.client.launch.AndroidApplicationRuntime
import app.logdate.client.launch.ApplicationStartup
import app.logdate.client.launch.awaitAndroidUserUnlock
import app.logdate.client.launch.observeNativeRestoreDeliveryCompletions
import app.logdate.client.launch.recoverCompletedRecordingFiles
import app.logdate.client.restore.NativeMediaQuarantineScheduler
import app.logdate.client.restore.awaitNativeMediaQuarantine
import app.logdate.di.initializeKoin
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.koin.core.context.GlobalContext
import java.util.WeakHashMap

private const val APP_STARTUP_TAG = "LogDateStartup"

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
        if (application is LogDateApplication) return application.startup.also(::startApplicationRuntime)
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
                                        val isDebuggable =
                                            (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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
