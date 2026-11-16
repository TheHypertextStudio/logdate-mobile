package app.logdate.server.billing

import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.getKoin
import kotlin.time.Duration.Companion.minutes

private val REPORT_INTERVAL = 15.minutes

/**
 * Reports extra storage to Stripe every [REPORT_INTERVAL] for as long as the server runs. Does
 * nothing on a deployment without Stripe, which has no [StorageMeterReporter].
 */
fun Application.installStorageMetering() {
    val reporter = getKoin().getOrNull<StorageMeterReporter>() ?: return
    val job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val reported = reporter.reportDue()
                    if (reported > 0) Napier.i(BillingNotices.meterReported(reported))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Napier.e(BillingNotices.METER_RUN_FAILED, e)
                }
                delay(REPORT_INTERVAL)
            }
        }
    monitor.subscribe(ApplicationStopped) { job.cancel() }
}
