package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionRuntime
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.getKoin

internal fun Application.identityAdoptionApiRoutes() {
    val runtime = getKoin().getOrNull<IdentityAdoptionRuntime>()
    val repository = runtime?.repository ?: getKoin().getOrNull<IdentityAdoptionRepository>() ?: return
    routing {
        route("/api/v1") {
            if (runtime == null) {
                identityAdoptionRecoveryRoutes(
                    getKoin().get<AccountRepository>(),
                    getKoin().get<TokenService>(),
                    repository,
                    getKoin().get<AuthMetricsRegistry>(),
                )
                return@route
            }
            identityAdoptionRoutes(
                getKoin().get<AccountRepository>(),
                getKoin().get<TokenService>(),
                repository,
                getKoin().get<AuthMetricsRegistry>(),
                enabled = true,
                oauth = runtime.oauth,
                confirmation = runtime.confirmation,
                lifecycle = runtime.lifecycle,
                finalization = runtime.finalization,
                sourceAccess = runtime.sourceAccess,
                copyAccess = runtime.copyAccess,
            )
        }
    }
}
