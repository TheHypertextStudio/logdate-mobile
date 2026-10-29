package app.logdate.client.device.auth

import app.logdate.client.device.storage.SecureStorage
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Persist before opening the browser. Returned links can only resume the original stored proof. */
class BlueskyAttemptStore(
    private val storage: SecureStorage,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()

    suspend fun remember(
        attemptId: String,
        verifier: String,
        backendOrigin: String,
        ownerId: String,
    ) {
        require(validProof(attemptId, verifier) && validOrigin(backendOrigin) && Uuid.parseOrNull(ownerId) != null) {
            "The sign-in request is invalid"
        }
        mutex.withLock {
            val record =
                PendingBlueskyAttempt(
                    attemptId,
                    verifier,
                    backendOrigin,
                    ownerId,
                    expiresAtEpochMillis =
                        nowMillis() + LIFETIME_MILLIS,
                )
            val encoded = Json.encodeToString(record)
            safely {
                storage.putString(KEY, encoded)
                // Some platform storage implementations report failed writes without throwing.
                check(storage.getString(KEY) == encoded)
            }
        }
    }

    suspend fun current(): PendingBlueskyAttempt? =
        mutex.withLock {
            read()?.takeIf {
                validProof(it.attemptId, it.verifier) &&
                    validOrigin(it.backendOrigin) &&
                    Uuid.parseOrNull(it.ownerId) != null &&
                    it.expiresAtEpochMillis - nowMillis() in 1..LIFETIME_MILLIS
            }
        }

    suspend fun forReturn(url: String): PendingBlueskyAttempt? {
        val id = BlueskyAppReturn.attemptId(url) ?: return null
        return mutex.withLock {
            val record = read() ?: return@withLock null
            record.takeIf {
                it.attemptId == id &&
                    validProof(it.attemptId, it.verifier) &&
                    validOrigin(it.backendOrigin) &&
                    Uuid.parseOrNull(it.ownerId) != null &&
                    it.expiresAtEpochMillis - nowMillis() in 1..LIFETIME_MILLIS
            }
        }
    }

    /** Clearing an old response must never remove a newer sign-in attempt. */
    suspend fun forget(attemptId: String) =
        mutex.withLock {
            if (read()?.attemptId == attemptId) {
                safely {
                    storage.remove(KEY)
                    check(storage.getString(KEY) == null)
                }
            }
        }

    private suspend fun read(): PendingBlueskyAttempt? {
        val text = safely { storage.getString(KEY) } ?: return null
        if (text.length > 4096) return null
        return try {
            Json.decodeFromString<PendingBlueskyAttempt>(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T =
        try {
            block()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            // Platform exceptions can contain the value being stored. Never retain their cause.
            throw IllegalStateException("Sign-in could not be saved")
        }

    private companion object {
        const val KEY = "bluesky_pending_app_attempt_v1"
        const val LIFETIME_MILLIS = 10 * 60 * 1000L
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
        val VERIFIER = Regex("[A-Za-z0-9_-]{43,128}")
        val ORIGIN = Regex("https://[^/?#@\\s]+")

        fun validProof(
            attemptId: String,
            verifier: String,
        ): Boolean = IDENTIFIER.matches(attemptId) && VERIFIER.matches(verifier)

        fun validOrigin(value: String): Boolean {
            if (value.length > 2048 || !ORIGIN.matches(value)) return false
            return try {
                val url = Url(value)
                url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.port in 1..65535
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}

/** Credentials are intentionally excluded from diagnostic string output. */
@Serializable
class PendingBlueskyAttempt internal constructor(
    val attemptId: String,
    val verifier: String,
    val backendOrigin: String,
    val ownerId: String,
    internal val expiresAtEpochMillis: Long,
) {
    override fun toString(): String = "PendingBlueskyAttempt()"
}
