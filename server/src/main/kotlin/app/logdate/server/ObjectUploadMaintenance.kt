package app.logdate.server

import app.logdate.server.logdate.ObjectUploadMaintenance
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped

internal fun Application.installObjectUploadMaintenance(maintenance: ObjectUploadMaintenance) {
    monitor.subscribe(ApplicationStopped) { maintenance.close() }
    maintenance.start()
}
