package app.logdate.client.data.account

import app.logdate.client.device.storage.SecureStorage
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyExchangeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One secure enrollment, advanced only by a response to the state the caller actually read. */
internal class BlueskyEnrollmentStore(
    private val storage: SecureStorage,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    suspend fun begin(
        attemptId: String,
        backendOrigin: String,
        next: BlueskyExchangeResponse,
        ownerId: String,
    ): BlueskyEnrollment =
        mutex.withLock {
            val enrollment =
                BlueskyEnrollment(
                    attemptId,
                    backendOrigin,
                    BlueskyEnrollmentStep.Identity(next.continuationToken, next.nextStep),
                    nowMillis() + LIFETIME_MILLIS,
                    ownerId,
                )
            require(valid(enrollment)) { "The sign-in response is invalid" }
            write(enrollment)
            enrollment
        }

    suspend fun isCurrent(expected: BlueskyEnrollment): Boolean =
        mutex.withLock {
            val current = load() ?: return@withLock false
            fresh(current) && Json.encodeToString(current) == Json.encodeToString(expected)
        }

    suspend fun read(): BlueskyEnrollment? = mutex.withLock { load()?.takeIf(::fresh) }

    suspend fun advance(
        expected: BlueskyEnrollment,
        step: BlueskyEnrollmentStep,
    ): BlueskyEnrollment? =
        mutex.withLock {
            val current = load() ?: return@withLock null
            if (!fresh(current) || Json.encodeToString(current) != Json.encodeToString(expected)) return@withLock null
            if (current.completionStarted) return@withLock null
            val identity = current.step as? BlueskyEnrollmentStep.Identity ?: return@withLock null
            val allowed =
                when (identity.nextStep) {
                    BlueskyAccountNextStep.CREATE_ACCOUNT -> step is BlueskyEnrollmentStep.Registration
                    BlueskyAccountNextStep.CONFIRM_PASSKEY -> step is BlueskyEnrollmentStep.Authentication
                }
            if (!allowed) return@withLock null
            val updated = BlueskyEnrollment(current.attemptId, current.backendOrigin, step, nowMillis() + LIFETIME_MILLIS, current.ownerId)
            require(valid(updated)) { "The sign-in response is invalid" }
            write(updated)
            updated
        }

    suspend fun claimCompletion(expected: BlueskyEnrollment): BlueskyEnrollment? =
        mutex.withLock {
            val current = load() ?: return@withLock null
            if (!fresh(current) ||
                current.completionStarted ||
                current.step is BlueskyEnrollmentStep.Identity ||
                Json.encodeToString(current) != Json.encodeToString(expected)
            ) {
                return@withLock null
            }
            val claimed =
                BlueskyEnrollment(
                    current.attemptId,
                    current.backendOrigin,
                    current.step,
                    current.expiresAtMillis,
                    current.ownerId,
                    completionStarted = true,
                )
            write(claimed)
            claimed
        }

    suspend fun clear(attemptId: String) =
        mutex.withLock {
            if (load()?.attemptId == attemptId) {
                safely {
                    storage.remove(KEY)
                    check(storage.getString(KEY) == null)
                }
            }
        }

    private suspend fun load(): BlueskyEnrollment? {
        val encoded = safely { storage.getString(KEY) } ?: return null
        if (encoded.length > MAX_RECORD_CHARACTERS) return null
        return try {
            Json.decodeFromString<BlueskyEnrollment>(encoded).takeIf(::valid)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun write(enrollment: BlueskyEnrollment) {
        val encoded = Json.encodeToString(enrollment)
        check(encoded.length <= MAX_RECORD_CHARACTERS) { "The sign-in response is invalid" }
        safely {
            storage.putString(KEY, encoded)
            check(storage.getString(KEY) == encoded)
        }
    }

    private fun fresh(enrollment: BlueskyEnrollment): Boolean = enrollment.expiresAtMillis - nowMillis() in 1..LIFETIME_MILLIS

    private suspend fun <T> safely(block: suspend () -> T): T =
        try {
            block()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            throw IllegalStateException("Sign-in could not be saved")
        }

    private companion object {
        const val KEY = "bluesky_pending_enrollment_v1"
        const val LIFETIME_MILLIS = 5 * 60 * 1000L
        const val MAX_RECORD_CHARACTERS = 1024 * 1024
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
        val ORIGIN = Regex("https://[^/?#@\\s]+")

        fun valid(enrollment: BlueskyEnrollment): Boolean {
            if (Uuid.parseOrNull(enrollment.ownerId) == null ||
                !IDENTIFIER.matches(enrollment.attemptId) ||
                enrollment.backendOrigin.length > 2048 ||
                !ORIGIN.matches(enrollment.backendOrigin)
            ) {
                return false
            }
            val step = enrollment.step
            if (enrollment.completionStarted && step is BlueskyEnrollmentStep.Identity) return false
            if (!IDENTIFIER.matches(step.token)) return false
            return when (step) {
                is BlueskyEnrollmentStep.Identity -> true
                is BlueskyEnrollmentStep.Registration ->
                    step.profile.ownerId == enrollment.ownerId &&
                        step.profile.username.isNotBlank() &&
                        step.profile.displayName.isNotBlank() &&
                        step.options.challenge.isNotBlank() &&
                        step.options.rpId.isNotBlank()
                is BlueskyEnrollmentStep.Authentication -> step.options.challenge.isNotBlank() && step.options.rpId.isNotBlank()
            }
        }
    }
}
