package app.logdate.client.sync.di

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.diagnostics.DiagnosticHistory
import app.logdate.client.sync.diagnostics.DiagnosticReportGateway
import app.logdate.client.sync.diagnostics.DiagnosticReportingController
import app.logdate.client.sync.diagnostics.DiagnosticSourceProvider
import app.logdate.client.sync.diagnostics.SyncDiagnosticRecorder
import app.logdate.client.sync.diagnostics.VerboseDiagnosticMode
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerProtocolFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock

expect val diagnosticsModule: Module

internal val diagnosticCoreModule =
    module {
        single(named("sync-diagnostics-scope")) { CoroutineScope(SupervisorJob() + platformIODispatcher) }
        single { DiagnosticHistory(get(), { Clock.System.now().toEpochMilliseconds() }) }
        single { VerboseDiagnosticMode(get(named("verbose-diagnostic-storage")), { Clock.System.now().toEpochMilliseconds() }) }
        single { DiagnosticSourceProvider(get(), get()) }
        single { DiagnosticReportGateway(get(), get(), get(), get()) }.onClose { it?.close() }
        single {
            val sessions = get<SessionStorage>()
            val config = get<LogDateConfigRepository>()
            val sources = get<DiagnosticSourceProvider>()
            DiagnosticReportingController(
                get(named("reporting-diagnostic-storage")),
                get(),
                get(named("sync-diagnostics-scope")),
                currentScope = { sources.current()?.scope },
                supported = { scope ->
                    config.getCurrentBackendUrl() == scope.serverOrigin &&
                        config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.DIAGNOSTIC_REPORTS_V1) == true
                },
                nowMillis = { Clock.System.now().toEpochMilliseconds() },
                ready = { sessions.hasValidSession() },
                send = { scope, report -> get<DiagnosticReportGateway>().send(scope, report) },
                deleteUploaded = { scope -> get<DiagnosticReportGateway>().deleteAll(scope) },
            )
        }
        single {
            val reporting = get<DiagnosticReportingController>()
            SyncDiagnosticRecorder(
                get(),
                get(named("sync-diagnostics-scope")),
                get(),
                onScopedEvent = reporting::record,
                context = { get<app.logdate.shared.model.diagnostics.DiagnosticContext>() },
            )
        }
    }
