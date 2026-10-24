package app.logdate.client.sync.peer.devices

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DeviceLinkApiClient
import app.logdate.client.networking.DeviceLinkApiClientContract
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.cloud.RefreshedSession
import app.logdate.client.sync.cloud.SessionRefreshingAccountRepository
import app.logdate.client.sync.peer.removal.WatchRemovalAccess
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.config.PinnedLogDateConfigRepository
import app.logdate.shared.model.devices.LinkedDevice
import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A device linked to the account with a device session of its own, such as a watch, as the server lists it. */
data class LinkedDeviceSession(
    val id: String,
    val name: String,
    /** The server's platform identifier, such as `wear_os`. */
    val platform: String,
    val linkedAt: Instant,
    val lastSeenAt: Instant,
    /** The watch paired with this phone, which the Devices page already shows as its watch. */
    val pairedWatch: Boolean = false,
)

/** Why the linked devices could not be listed, or one could not be removed. */
sealed interface LinkedDevicesFailure {
    /** The server could not be reached. */
    data object Unreachable : LinkedDevicesFailure

    /** The server no longer accepts this phone's session; signing in again fixes it. */
    data object SignInRequired : LinkedDevicesFailure

    /** The server answered with HTTP [status]. */
    data class Refused(
        val status: Int,
    ) : LinkedDevicesFailure

    /** The server's answer could not be read. */
    data object UnreadableAnswer : LinkedDevicesFailure

    /** The phone moved to another account or server while the request was out. */
    data object AccountChanged : LinkedDevicesFailure

    /** Removing a watch this phone linked stopped at [step] of the watch removal. */
    data class WatchRemovalStopped(
        val step: String,
    ) : LinkedDevicesFailure

    /** A failure none of the others describe; the log has its details. */
    data object Unexpected : LinkedDevicesFailure
}

sealed interface LinkedDevicesLoad {
    /** Device linking is off, the phone is signed out, or the server does not link devices. */
    data object Unavailable : LinkedDevicesLoad

    data class Loaded(
        val devices: List<LinkedDeviceSession>,
    ) : LinkedDevicesLoad

    data class Failed(
        val failure: LinkedDevicesFailure,
    ) : LinkedDevicesLoad
}

sealed interface LinkedDeviceRemoval {
    data object Removed : LinkedDeviceRemoval

    /** The device can no longer sync; it erases its LogDate data the next time it connects to this phone. */
    data object RemovedWillWipeOnNextConnect : LinkedDeviceRemoval

    data class Failed(
        val failure: LinkedDevicesFailure,
    ) : LinkedDeviceRemoval
}

/** The devices linked to the signed-in account, for the Devices page. */
interface LinkedDevicesRepository {
    /** Whether linked devices can be listed at all; nothing is shown while they cannot. */
    suspend fun available(): Boolean

    suspend fun load(): LinkedDevicesLoad

    suspend fun remove(device: LinkedDeviceSession): LinkedDeviceRemoval
}

/** The clients that reach one server with one session. */
data class LinkedDeviceClients(
    val links: DeviceLinkApiClientContract,
    val refresher: SessionRefreshingAccountRepository?,
)

/**
 * Clients pinned to [bound]'s server, so a request cannot follow the app to another server, or null
 * once the app already points at another one.
 */
fun pinnedLinkedDeviceClients(
    http: HttpClient,
    config: LogDateConfigRepository,
    bound: OriginBoundSession,
): LinkedDeviceClients? {
    if (config.getCurrentBackendUrl() != bound.origin) return null
    val pinned = PinnedLogDateConfigRepository(bound.origin, descriptor = null, apiVersion = config.apiVersion.value)
    val cloud = LogDateCloudApiClient(pinned, http)
    return LinkedDeviceClients(
        links = DeviceLinkApiClient(http, pinned),
        refresher =
            object : SessionRefreshingAccountRepository {
                override suspend fun refreshSession(refreshToken: String): Result<RefreshedSession> = cloud.refreshSession(refreshToken)
            },
    )
}

/**
 * [LinkedDevicesRepository] over the server's device sessions (`/auth/devices`), with this phone's own
 * session. An expired access token is refreshed once, and the refreshed session is saved only while it
 * is still the phone's. A watch this phone linked is removed through [watchRemoval], which sends what
 * the watch still has before its session ends and asks it to erase its LogDate data.
 */
class AccountLinkedDevicesRepository(
    private val sessions: SessionStorage,
    private val linkingAvailable: suspend () -> Boolean,
    private val clientsFor: (OriginBoundSession) -> LinkedDeviceClients?,
    private val watchRemoval: WatchRemovalAccess? = null,
) : LinkedDevicesRepository {
    override suspend fun available(): Boolean = sessions.getOriginBoundSession() != null && linkingAvailable()

    override suspend fun load(): LinkedDevicesLoad {
        if (!available()) return LinkedDevicesLoad.Unavailable
        val listed =
            when (val call = withSession { links, token -> links.listDevices(token) }) {
                Call.NoSession -> return LinkedDevicesLoad.Unavailable
                is Call.Failure -> return LinkedDevicesLoad.Failed(call.failure)
                is Call.Success -> call.value
            }
        val paired = pairedWatchSession()
        return LinkedDevicesLoad.Loaded(listed.filterNot { it.current }.map { it.toSession(pairedWatch = it.id == paired) })
    }

    override suspend fun remove(device: LinkedDeviceSession): LinkedDeviceRemoval {
        val watch =
            try {
                watchRemoval?.targetForSession(device.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("The phone's watch links could not be read, so the linked device was not removed", e)
                return LinkedDeviceRemoval.Failed(LinkedDevicesFailure.WatchRemovalStopped(WATCH_LINKS_UNREADABLE))
            }
        if (watch != null) {
            return when (val result = checkNotNull(watchRemoval).remove(watch)) {
                WatchRemovalResult.Removed -> LinkedDeviceRemoval.Removed
                WatchRemovalResult.RemovedWillWipeOnNextConnect -> LinkedDeviceRemoval.RemovedWillWipeOnNextConnect
                is WatchRemovalResult.Failed -> LinkedDeviceRemoval.Failed(LinkedDevicesFailure.WatchRemovalStopped(result.step))
            }
        }
        return revoke(device.id)
    }

    private suspend fun revoke(id: String): LinkedDeviceRemoval =
        when (val call = withSession { links, token -> links.revoke(id, token) }) {
            Call.NoSession -> {
                LinkedDeviceRemoval.Failed(LinkedDevicesFailure.SignInRequired)
            }

            is Call.Success -> {
                LinkedDeviceRemoval.Removed
            }

            is Call.Failure -> {
                if (call.failure == LinkedDevicesFailure.Refused(HTTP_NOT_FOUND)) {
                    confirmGone(id)
                } else {
                    LinkedDeviceRemoval.Failed(call.failure)
                }
            }
        }

    /** A removal the server answered with 404 succeeded if the device is no longer listed. */
    private suspend fun confirmGone(id: String): LinkedDeviceRemoval =
        when (val call = withSession { links, token -> links.listDevices(token) }) {
            Call.NoSession -> {
                LinkedDeviceRemoval.Failed(LinkedDevicesFailure.SignInRequired)
            }

            is Call.Failure -> {
                LinkedDeviceRemoval.Failed(call.failure)
            }

            is Call.Success -> {
                if (call.value.none { it.id == id }) {
                    LinkedDeviceRemoval.Removed
                } else {
                    LinkedDeviceRemoval.Failed(LinkedDevicesFailure.Refused(HTTP_NOT_FOUND))
                }
            }
        }

    /** The paired watch's session, or null when there is none or the watch connection does not answer in time. */
    private suspend fun pairedWatchSession(): String? {
        val removal = watchRemoval ?: return null
        return try {
            withTimeoutOrNull(PAIRED_WATCH_TIMEOUT) { removal.target() }?.sessionId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("The paired watch could not be read; its linked session is listed with the other devices", e)
            null
        }
    }

    /**
     * Runs [request] with the phone's session, retrying an expired access token once. New tokens the
     * rest of the app stored meanwhile are the same account and are used as they are; only a different
     * account or server fails the call.
     */
    private suspend fun <T> withSession(request: suspend (DeviceLinkApiClientContract, String) -> Result<T>): Call<T> {
        val bound = sessions.getOriginBoundSession() ?: return Call.NoSession
        val clients = clientsFor(bound) ?: return Call.Failure(LinkedDevicesFailure.AccountChanged)
        val first = request(clients.links, bound.session.accessToken)
        val latest = sessions.getOriginBoundSession()
        if (latest == null || !latest.isSameAccount(bound)) return Call.Failure(LinkedDevicesFailure.AccountChanged)
        val refused = first.exceptionOrNull() ?: return Call.Success(first.getOrThrow())
        if ((refused as? DeviceLinkApiException)?.status != HTTP_UNAUTHORIZED) return failure(refused)
        val retryToken =
            if (latest.session.accessToken != bound.session.accessToken) {
                latest.session.accessToken
            } else {
                val refresher = clients.refresher ?: return failure(refused)
                when (val refreshed = refresh(refresher, latest)) {
                    is Call.Success -> refreshed.value
                    is Call.Failure -> return refreshed
                    Call.NoSession -> return Call.NoSession
                }
            }
        val second = request(clients.links, retryToken)
        if (sessions.getOriginBoundSession()?.isSameAccount(bound) != true) return Call.Failure(LinkedDevicesFailure.AccountChanged)
        return second.fold(onSuccess = { Call.Success(it) }, onFailure = { failure(it) })
    }

    /**
     * Refreshes [current] and stores the new tokens, returning the access token to retry with. When the
     * app stored newer tokens for the same account first, those are used instead.
     */
    private suspend fun refresh(
        refresher: SessionRefreshingAccountRepository,
        current: OriginBoundSession,
    ): Call<String> {
        val refreshed = refresher.refreshSession(current.session.refreshToken).getOrElse { return failure(it) }
        val updated =
            current.session.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: current.session.refreshToken,
            )
        if (sessions.replaceSessionIfCurrent(current, updated)) return Call.Success(updated.accessToken)
        val stored = sessions.getOriginBoundSession() ?: return Call.NoSession
        if (!stored.isSameAccount(current)) return Call.Failure(LinkedDevicesFailure.AccountChanged)
        return Call.Success(stored.session.accessToken)
    }

    private fun <T> failure(error: Throwable): Call<T> {
        if (error is CancellationException) throw error
        val failure = classify(error)
        if (failure == LinkedDevicesFailure.Unexpected) Napier.e("A linked devices request failed", error)
        return Call.Failure(failure)
    }

    private sealed interface Call<out T> {
        data object NoSession : Call<Nothing>

        data class Success<T>(
            val value: T,
        ) : Call<T>

        data class Failure(
            val failure: LinkedDevicesFailure,
        ) : Call<Nothing>
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val NETWORK_ERROR = "NETWORK_ERROR"
        val PAIRED_WATCH_TIMEOUT = 5.seconds

        /** The removal step reported when the phone's own watch links cannot be read. */
        const val WATCH_LINKS_UNREADABLE = "removal"

        fun classify(error: Throwable): LinkedDevicesFailure =
            when (error) {
                is DeviceLinkApiException -> {
                    if (error.status == HTTP_UNAUTHORIZED) {
                        LinkedDevicesFailure.SignInRequired
                    } else {
                        LinkedDevicesFailure.Refused(error.status)
                    }
                }

                is CloudApiException -> {
                    classifyRefresh(error)
                }

                is SerializationException -> {
                    LinkedDevicesFailure.UnreadableAnswer
                }

                is IOException -> {
                    LinkedDevicesFailure.Unreachable
                }

                else -> {
                    LinkedDevicesFailure.Unexpected
                }
            }

        /** A refresh the server refuses means signing in again; other answers keep their status. */
        fun classifyRefresh(error: CloudApiException): LinkedDevicesFailure {
            val status = error.statusCode
            if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) return LinkedDevicesFailure.SignInRequired
            if (status != null) return LinkedDevicesFailure.Refused(status)
            if (error.cause is IOException || error.errorCode == NETWORK_ERROR) return LinkedDevicesFailure.Unreachable
            return LinkedDevicesFailure.Unexpected
        }
    }
}

private fun LinkedDevice.toSession(pairedWatch: Boolean) =
    LinkedDeviceSession(
        id = id,
        name = deviceName,
        platform = platform,
        linkedAt = Instant.fromEpochMilliseconds(createdAt),
        lastSeenAt = Instant.fromEpochMilliseconds(lastRefreshedAt),
        pairedWatch = pairedWatch,
    )

/** The same account on the same server, whatever its current tokens are. */
private fun OriginBoundSession.isSameAccount(other: OriginBoundSession): Boolean =
    origin == other.origin && session.accountId == other.session.accountId
