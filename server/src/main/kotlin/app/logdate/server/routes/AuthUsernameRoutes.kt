@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.routes.docs.AuthDocs
import app.logdate.shared.model.UsernameAvailabilityData
import app.logdate.shared.model.UsernameAvailabilityResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private const val METRIC_AUTH_SIGNUP_USERNAME_AVAILABLE = "auth.signup.username.available"

internal fun Route.registerSignupUsernameRoute(
    accountRepository: AccountRepository,
    metrics: AuthMetricsRegistry,
) {
    get("/username/{username}/available", AuthDocs.checkUsernameAvailability) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val username = call.parameters["username"]?.trim().orEmpty()
            val validationError = validateUsername(username)
            if (validationError != null) {
                return@get call.respondApiError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", validationError, metrics)
            }
            val isAvailable = !accountRepository.usernameExists(username)
            call.respond(
                HttpStatusCode.OK,
                UsernameAvailabilityResponse(
                    success = true,
                    data = UsernameAvailabilityData(username = username, available = isAvailable),
                ),
            )
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to check username availability", e)
            call.respondForRequestException(e, "Failed to check username availability", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_SIGNUP_USERNAME_AVAILABLE, System.currentTimeMillis() - start, success)
        }
    }
}
