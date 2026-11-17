@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.diagnostics.DiagnosticReportBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Shares from app-private temporary storage and deletes the file when sharing ends. */
class IosDiagnosticArchiveExporter(
    private val rootViewController: () -> UIViewController,
) : DiagnosticArchiveExporter {
    private val directory = "${NSTemporaryDirectory().trimEnd('/')}/sync-diagnostic-reports".toPath()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The initializer registers the activation observer that prunes expired reports; this holds its token
    @Suppress("unused")
    private val activationObserver =
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { maintenance.launch(Dispatchers.Default) { pruneExpired() } },
        )

    init {
        maintenance.launch(Dispatchers.Default) { pruneExpired() }
    }

    override suspend fun export(bundle: DiagnosticReportBundle): Boolean {
        val output = directory / "report-${Uuid.random()}.zip"
        try {
            withContext(Dispatchers.Default) {
                FileSystem.SYSTEM.createDirectories(directory)
                pruneExpired()
                DiagnosticBundleArchive.write(FileSystem.SYSTEM, output, bundle)
            }
            // The independent timer survives cancellation of the share request. Activation also
            // sweeps reports whose timer could not run while iOS suspended the app.
            maintenance.launch {
                delay(DIAGNOSTIC_ARCHIVE_EXPIRY_MILLIS)
                runCatching { FileSystem.SYSTEM.delete(output, mustExist = false) }
            }
            return withContext(Dispatchers.Main) {
                val host = rootViewController()
                val controller =
                    UIActivityViewController(
                        activityItems = listOf(NSURL.fileURLWithPath(output.toString())),
                        applicationActivities = null,
                    )
                controller.popoverPresentationController?.sourceView = host.view
                controller.popoverPresentationController?.sourceRect = host.view.bounds
                controller.completionWithItemsHandler = { _, _, _, _ ->
                    runCatching { FileSystem.SYSTEM.delete(output, mustExist = false) }
                }
                host.presentViewController(controller, animated = true, completion = null)
                true
            }
        } catch (cancelled: CancellationException) {
            runCatching { FileSystem.SYSTEM.delete(output, mustExist = false) }
            throw cancelled
        } catch (_: Exception) {
            runCatching { FileSystem.SYSTEM.delete(output, mustExist = false) }
            return false
        }
    }

    private fun pruneExpired() {
        runCatching {
            FileSystem.SYSTEM.createDirectories(directory)
            val files = FileSystem.SYSTEM.list(directory)
            val names =
                expiredDiagnosticArchives(
                    Clock.System.now().toEpochMilliseconds(),
                    files.mapNotNull { path ->
                        FileSystem.SYSTEM
                            .metadataOrNull(path)
                            ?.lastModifiedAtMillis
                            ?.let { PrivateDiagnosticArchive(path.name, it) }
                    },
                )
            names.forEach { name ->
                runCatching { FileSystem.SYSTEM.delete(directory / name, mustExist = false) }
            }
        }
    }
}
