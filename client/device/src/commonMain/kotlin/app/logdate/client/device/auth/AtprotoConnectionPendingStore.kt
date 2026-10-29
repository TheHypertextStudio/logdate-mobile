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

@Serializable
data class AtprotoConnectionBinding(
    val backendOrigin: String,
    val accountId: String,
    val authenticationGeneration: String,
) {
    override fun toString(): String = "AtprotoConnectionBinding()"

    companion object {
        fun fromGrant(
            backendOrigin: String,
            accountId: String,
            refreshCredential: String,
        ): AtprotoConnectionBinding {
            require(backendOrigin.length <= 2048 && Regex("https://[^/?#@\\s]+").matches(backendOrigin))
            val url = Url(backendOrigin)
            require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.port in 1..65535)
            require(refreshCredential.isNotBlank() && refreshCredential.length <= 16384)
            return AtprotoConnectionBinding(
                backendOrigin,
                Uuid.parse(accountId).toString(),
                BlueskyAppProof.challengeFor(refreshCredential),
            )
        }
    }
}

@Serializable
sealed class AtprotoConnectionPendingAction {
    @Serializable
    class Reconnect(
        val attemptId: String,
        val verifier: String,
    ) : AtprotoConnectionPendingAction()

    @Serializable
    class Disconnect(
        val decisionId: String,
        val encodedProof: String,
    ) : AtprotoConnectionPendingAction()

    final override fun toString(): String = "AtprotoConnectionPendingAction()"
}

@Serializable
class PendingAtprotoConnection(
    val id: String,
    val binding: AtprotoConnectionBinding,
    val action: AtprotoConnectionPendingAction,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String = "PendingAtprotoConnection()"
}

/** Separate from ordinary sign-in; a stored disconnect proof retries the same decision after a lost response. */
class AtprotoConnectionPendingStore(
    private val storage: SecureStorage,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun remember(
        binding: AtprotoConnectionBinding,
        action: AtprotoConnectionPendingAction,
    ): PendingAtprotoConnection =
        mutex.withLock {
            safely {
                check(validBinding(binding) && validAction(action))
                check(currentLocked(binding) == null)
                val now = nowMillis()
                val lifetime = lifetime(action)
                check(now >= 0 && now <= Long.MAX_VALUE - lifetime)
                val record = PendingAtprotoConnection(Uuid.random().toString(), binding, action, now, now + lifetime)
                val encoded = Json.encodeToString(record)
                check(encoded.encodeToByteArray().size <= MAX_RECORD_BYTES)
                storage.putString(KEY, encoded)
                check(storage.getString(KEY) == encoded)
                record
            }
        }

    suspend fun current(binding: AtprotoConnectionBinding): PendingAtprotoConnection? =
        mutex.withLock {
            safely { currentLocked(binding) }
        }

    suspend fun forget(expected: PendingAtprotoConnection): Boolean =
        mutex.withLock {
            safely {
                val raw = storage.getString(KEY) ?: return@safely false
                if (raw != Json.encodeToString(expected)) return@safely false
                remove(raw)
                true
            }
        }

    private suspend fun currentLocked(binding: AtprotoConnectionBinding): PendingAtprotoConnection? {
        val raw = storage.getString(KEY) ?: return null
        val record =
            if (raw.encodeToByteArray().size <= MAX_RECORD_BYTES) {
                runCatching { Json.decodeFromString<PendingAtprotoConnection>(raw) }.getOrNull()
            } else {
                null
            }
        val now = nowMillis()
        if (record != null &&
            record.binding == binding &&
            validBinding(binding) &&
            validAction(record.action) &&
            Uuid.parseOrNull(record.id) != null &&
            record.createdAtMillis >= 0 &&
            record.expiresAtMillis > record.createdAtMillis &&
            record.expiresAtMillis - record.createdAtMillis == lifetime(record.action) &&
            now >= record.createdAtMillis &&
            now < record.expiresAtMillis
        ) {
            return record
        }
        remove(raw)
        return null
    }

    private suspend fun remove(expected: String) {
        check(storage.getString(KEY) == expected)
        storage.remove(KEY)
        check(storage.getString(KEY) == null)
    }

    private suspend inline fun <T> safely(work: () -> T): T =
        try {
            work()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            throw IllegalStateException("Connection request could not be saved")
        }

    private companion object {
        const val KEY = "atproto_pending_account_connection_v1"
        const val MAX_RECORD_BYTES = 131072
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")

        // All in-process instances share the compare/remove boundary, including restored controllers.
        val mutex = Mutex()

        fun validBinding(value: AtprotoConnectionBinding): Boolean {
            if (value.backendOrigin.length > 2048 || !Regex("https://[^/?#@\\s]+").matches(value.backendOrigin)) return false
            val url = Url(value.backendOrigin)
            return url.protocol == URLProtocol.HTTPS &&
                url.host.isNotBlank() &&
                url.port in 1..65535 &&
                Uuid.parseOrNull(value.accountId) != null &&
                IDENTIFIER.matches(value.authenticationGeneration)
        }

        fun validAction(action: AtprotoConnectionPendingAction): Boolean =
            when (action) {
                is AtprotoConnectionPendingAction.Reconnect ->
                    IDENTIFIER.matches(action.attemptId) &&
                        Regex("[A-Za-z0-9_-]{43,128}").matches(action.verifier)
                is AtprotoConnectionPendingAction.Disconnect ->
                    IDENTIFIER.matches(action.decisionId) &&
                        action.encodedProof.isNotBlank() &&
                        action.encodedProof.encodeToByteArray().size <= 65536
            }

        fun lifetime(action: AtprotoConnectionPendingAction): Long =
            when (action) {
                is AtprotoConnectionPendingAction.Reconnect -> 600000L
                is AtprotoConnectionPendingAction.Disconnect -> 86400000L
            }
    }
}
