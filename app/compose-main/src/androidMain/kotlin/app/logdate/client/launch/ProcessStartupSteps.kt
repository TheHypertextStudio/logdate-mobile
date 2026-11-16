package app.logdate.client.launch

import android.app.Application
import android.content.pm.ApplicationInfo
import app.logdate.client.CrashlyticsAntilog
import app.logdate.client.LogcatAntilog
import app.logdate.client.data.notes.NativeMediaQuarantine
import app.logdate.client.data.search.AndroidPlatformSearchIndexManager
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.restore.NativeMediaQuarantineScheduler
import app.logdate.client.restore.awaitNativeMediaQuarantine
import app.logdate.di.initializeKoin
import io.github.aakira.napier.Napier
import org.koin.core.context.GlobalContext

internal const val APP_STARTUP_TAG = "LogDateStartup"

/**
 * Installs logging and starts Koin, once per process.
 *
 * Napier's DebugAntilog derives its tag by walking the stack trace at a fixed depth. R8 inlines frames
 * away, so in a minified build that index runs off the end and every Napier call throws
 * ArrayIndexOutOfBoundsException, crashing the app from inside its own error logging. LogcatAntilog
 * does no stack introspection, so it is safe minified, and it keeps warnings and errors in release
 * builds: without them a failing release install produces no on-device output at all.
 */
internal fun Application.initializeLoggingAndKoin() {
    if (GlobalContext.getOrNull() != null) return
    Napier.base(LogcatAntilog(isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0))
    Napier.base(CrashlyticsAntilog())
    initializeKoin()
}

/**
 * Opens the encrypted journal database, after first moving recordings that finished while the app was
 * stopped into place, then settles media a native restore left in quarantine and starts the Android
 * search index. Needs Koin.
 */
internal suspend fun Application.openJournalStorage() {
    recoverCompletedRecordingFiles(filesDir)
    val koin = GlobalContext.get()
    koin.get<LogDateDatabase>()
    val detector = PostRestoreDetector(this)
    awaitNativeMediaQuarantine(
        detector::pendingMediaQuarantineId,
        koin.get<NativeMediaQuarantine>()::run,
        detector::acknowledgeMediaQuarantine,
        { NativeMediaQuarantineScheduler.enqueue(this, it) },
    )
    koin.get<AndroidPlatformSearchIndexManager>()
    Napier.i("Encrypted storage ready", tag = APP_STARTUP_TAG)
}
