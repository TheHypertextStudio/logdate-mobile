package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.database.toKotlinUuid
import app.logdate.server.enrollment.DeviceEnrollment
import app.logdate.server.enrollment.DeviceEnrollmentRepository
import app.logdate.server.enrollment.EnrollmentStatus
import app.logdate.server.enrollment.SessionIssueResult
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.DeviceEnrollmentDocs
import app.logdate.server.routes.sync.extractUserId
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

private const val ENROLLMENT_LIFETIME_MS = 5 * 60 * 1_000L
private const val MAX_ENVELOPE_LENGTH = 4_096
private val random = SecureRandom()

@Serializable
internal data class CreateEnrollmentRequest(
    val deviceName: String,
    val publicKey: String,
    val claimSecret: String? = null,
    val confirmationCode: String? = null,
)

@Serializable
internal data class ApproveEnrollmentRequest(
    val confirmationCode: String,
    val encryptedEnvelope: String,
)

@Serializable
internal data class EnrollmentView(
    val id: String,
    val deviceName: String,
    val publicKey: String,
    val confirmationCode: String,
    val expiresAt: Long,
    val status: String,
)

@Serializable
internal data class ConsumedEnrollment(
    val encryptedEnvelope: String,
)

@Serializable
internal data class ClaimedEnrollment(
    val id: String,
    val accountId: String,
    val publicKey: String,
    val confirmationCode: String,
    val status: String,
    val encryptedEnvelope: String?,
)

internal fun Route.deviceEnrollmentRoutes(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
    accountRepository: AccountRepository,
) {
    route("/device-enrollments") {
        createEnrollmentRoute(tokenService, repository)
        claimEnrollmentRoute(repository)
        getEnrollmentRoute(tokenService, repository)
        transferEnrollmentRoute(tokenService, repository)
        issueEnrollmentSessionRoute(tokenService, repository, accountRepository)
        approveEnrollmentRoute(tokenService, repository)
        consumeEnrollmentRoute(tokenService, repository)
        cancelEnrollmentRoute(tokenService, repository)
        rejectEnrollmentRoute(tokenService, repository)
    }
}

private fun Route.createEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    post(DeviceEnrollmentDocs.create) {
        val accountId = extractUserId(call, tokenService) ?: return@post
        val request = call.receive<CreateEnrollmentRequest>()
        val name = request.deviceName.trim()
        val publicKey = runCatching { Base64.getUrlDecoder().decode(request.publicKey) }.getOrNull()
        val claimSecret = request.claimSecret?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
        if (
            name.isEmpty() ||
            name.length > 120 ||
            name.any(Char::isISOControl) ||
            publicKey?.size != 32 ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey) != request.publicKey ||
            (
                request.claimSecret != null &&
                    (
                        claimSecret?.size != 32 ||
                            Base64.getUrlEncoder().withoutPadding().encodeToString(claimSecret) != request.claimSecret ||
                            request.confirmationCode?.matches(Regex("[0-9]{6}")) != true
                    )
            ) ||
            (request.claimSecret == null && request.confirmationCode != null)
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid device name or public key"))
        }
        val now = System.currentTimeMillis()
        val enrollment =
            DeviceEnrollment(
                id = UUID.randomUUID(),
                accountId = accountId,
                deviceName = name,
                publicKey = request.publicKey,
                confirmationCode = request.confirmationCode ?: "%06d".format(random.nextInt(1_000_000)),
                expiresAt = now + ENROLLMENT_LIFETIME_MS,
                claimHash = claimSecret?.let(::hashClaimSecret),
            )
        val saved =
            repository.create(enrollment)
                ?: return@post call.respond(HttpStatusCode.Conflict, error("ENROLLMENT_CONFLICT", "Connection code already used"))
        call.response.headers.append("Location", "/api/v1/device-enrollments/${saved.id}")
        call.respond(HttpStatusCode.Created, saved.toView())
    }
}

private fun Route.claimEnrollmentRoute(repository: DeviceEnrollmentRepository) {
    post("/claim", DeviceEnrollmentDocs.claim) {
        val secret = call.request.header("X-LogDate-Claim-Secret")
        val bytes = secret?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
        if (bytes?.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != secret) {
            return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_CLAIM", "Invalid connection claim"))
        }
        val enrollment =
            repository.claim(hashClaimSecret(bytes), System.currentTimeMillis())
                ?: return@post call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Connection unavailable"))
        call.respond(
            ClaimedEnrollment(
                id = enrollment.id.toString(),
                accountId = enrollment.accountId.toString(),
                publicKey = enrollment.publicKey,
                confirmationCode = enrollment.confirmationCode,
                status = enrollment.status.name.lowercase(),
                encryptedEnvelope = enrollment.encryptedEnvelope,
            ),
        )
    }
}

private fun Route.getEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    get("/{id}", DeviceEnrollmentDocs.get) {
        val accountId = extractUserId(call, tokenService) ?: return@get
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        val enrollment =
            repository.get(accountId, id, System.currentTimeMillis())
                ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        call.respond(enrollment.toView())
    }
}

private fun Route.transferEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    get("/{id}/transfer", DeviceEnrollmentDocs.transfer) {
        val accountId = extractUserId(call, tokenService) ?: return@get
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        val enrollment =
            repository.get(accountId, id, System.currentTimeMillis())
                ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        val envelope = enrollment.encryptedEnvelope
        if (enrollment.status != EnrollmentStatus.APPROVED || envelope == null) {
            return@get call.respond(HttpStatusCode.Conflict, error("ENROLLMENT_UNAVAILABLE", "Enrollment unavailable"))
        }
        call.respond(ConsumedEnrollment(envelope))
    }
}

/** Replays one independent session from durable enrollment metadata while approval is pending. */
@OptIn(ExperimentalUuidApi::class)
private fun Route.issueEnrollmentSessionRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
    accountRepository: AccountRepository,
) {
    post("/{id}/session", DeviceEnrollmentDocs.session) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val accountId = extractUserId(call, tokenService) ?: return@post
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        val account =
            accountRepository.findById(accountId.toKotlinUuid())
                ?: return@post call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        val now = System.currentTimeMillis()
        val enrollment =
            repository.get(accountId, id, now)
                ?: return@post call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        when (repository.markSessionIssued(accountId, id, now)) {
            SessionIssueResult.ISSUED, SessionIssueResult.ALREADY_ISSUED -> {
                call.respond(
                    tokenService.generateEnrollmentTokens(
                        account.id.toString(),
                        account.did,
                        id.toString(),
                        Instant.fromEpochMilliseconds(enrollment.expiresAt - ENROLLMENT_LIFETIME_MS),
                    ),
                )
            }

            SessionIssueResult.NOT_PENDING -> {
                call.respond(HttpStatusCode.Conflict, error("ENROLLMENT_UNAVAILABLE", "Enrollment unavailable"))
            }

            SessionIssueResult.NOT_FOUND -> {
                call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
            }
        }
    }
}

private fun Route.approveEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    post("/{id}/approve", DeviceEnrollmentDocs.approve) {
        val accountId = extractUserId(call, tokenService) ?: return@post
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        val request = call.receive<ApproveEnrollmentRequest>()
        if (
            !request.confirmationCode.matches(Regex("[0-9]{6}")) ||
            request.encryptedEnvelope.isEmpty() ||
            request.encryptedEnvelope.length > MAX_ENVELOPE_LENGTH
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid approval"))
        }
        if (!repository.approve(accountId, id, request.confirmationCode, request.encryptedEnvelope, System.currentTimeMillis())) {
            return@post call.respond(HttpStatusCode.Conflict, error("ENROLLMENT_UNAVAILABLE", "Enrollment unavailable"))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.consumeEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    post("/{id}/consume", DeviceEnrollmentDocs.consume) {
        val accountId = extractUserId(call, tokenService) ?: return@post
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        val envelope =
            repository.consume(accountId, id, System.currentTimeMillis())
                ?: return@post call.respond(HttpStatusCode.Conflict, error("ENROLLMENT_UNAVAILABLE", "Enrollment unavailable"))
        call.respond(ConsumedEnrollment(envelope))
    }
}

private fun Route.cancelEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    delete("/{id}", DeviceEnrollmentDocs.cancel) {
        val accountId = extractUserId(call, tokenService) ?: return@delete
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        if (!repository.cancel(accountId, id)) {
            return@delete call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.rejectEnrollmentRoute(
    tokenService: TokenService,
    repository: DeviceEnrollmentRepository,
) {
    post("/{id}/reject", DeviceEnrollmentDocs.reject) {
        val accountId = extractUserId(call, tokenService) ?: return@post
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_ENROLLMENT", "Invalid request ID"))
        if (!repository.reject(accountId, id, System.currentTimeMillis())) {
            return@post call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Enrollment unavailable"))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun DeviceEnrollment.toView() =
    EnrollmentView(id.toString(), deviceName, publicKey, confirmationCode, expiresAt, status.name.lowercase())

private fun hashClaimSecret(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
