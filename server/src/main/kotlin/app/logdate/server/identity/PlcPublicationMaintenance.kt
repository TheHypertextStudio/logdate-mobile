package app.logdate.server.identity

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

/** Install only alongside the explicitly enabled publication routes and an approved directory. */
internal fun Application.installPlcPublicationMaintenance(
    consent: PlcPublicationConsent,
    worker: PlcPublicationWorker,
) {
    val job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                try {
                    consent.advanceDueReviews(worker)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Napier.w("PLC_PUBLICATION_CHECK_FAILED")
                }
                delay(5_000L)
            }
        }
    monitor.subscribe(ApplicationStopped) { job.cancel() }
}
