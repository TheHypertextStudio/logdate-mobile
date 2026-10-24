package app.logdate.client.sync.peer.delivery

import app.logdate.client.networking.saver.NetworkConnectionType
import app.logdate.client.networking.saver.NetworkSaverState
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.metadata.PendingOperation

/** How the watch can reach its phone right now. */
enum class PhoneReach {
    NEARBY,

    /** Only through Google's cloud relay: fine for records, slow and costly for media. */
    RELAY_ONLY,
    UNREACHABLE,
}

/** The watch's own internet, as far as a direct upload is concerned. */
enum class WatchNetwork {
    UNMETERED,
    METERED,
    BLUETOOTH_PROXY,
    NONE,
}

/** A network the system has not validated counts as none: it may be a captive portal. */
fun watchNetworkOf(state: NetworkSaverState): WatchNetwork =
    when {
        state.connectionType == NetworkConnectionType.NONE || !state.isValidated -> WatchNetwork.NONE
        state.connectionType == NetworkConnectionType.BLUETOOTH_PROXY -> WatchNetwork.BLUETOOTH_PROXY
        state.isMetered -> WatchNetwork.METERED
        else -> WatchNetwork.UNMETERED
    }

/**
 * What the watch knows when it routes a change.
 *
 * @property serverReady The server lists both `deviceLinkingV1` and `conditionalCreateV1`.
 */
data class RouteConditions(
    val phone: PhoneReach,
    val phoneCloud: CloudPresence,
    val linked: Boolean,
    val serverReady: Boolean,
    val network: WatchNetwork,
    val charging: Boolean,
)

data class RouteInput(
    val operation: PendingOperation,
    val hasMedia: Boolean,
    val state: DeliveryState,
    val stateAgeMs: Long,
    val conditions: RouteConditions,
)

sealed interface Route {
    data object SendToPhone : Route

    data object Settle : Route

    data object UploadDirect : Route

    data class Wait(
        val reason: HoldReason,
    ) : Route
}

/**
 * Decides where a change made on the watch goes next. The phone is preferred; the watch uploads only
 * creates and deletions itself, and only when it is linked, its server can create without overwriting,
 * and it has real internet of its own.
 */
class WatchSyncRouter(
    private val backupGraceMs: Long = BACKUP_GRACE_MS,
    private val receiptWaitMs: Long = RECEIPT_WAIT_MS,
) {
    fun route(input: RouteInput): Route =
        when (input.state) {
            DeliveryState.BACKED_UP, DeliveryState.DELETED_ON_PHONE -> {
                Route.Settle
            }

            DeliveryState.REJECTED -> {
                if (directBlocker(input) == null) Route.UploadDirect else Route.Wait(HoldReason.PHONE_CANNOT_READ)
            }

            DeliveryState.ON_PHONE -> {
                onPhone(input)
            }

            DeliveryState.CLOUD_PENDING -> {
                directBlocker(input)?.let { Route.Wait(it) } ?: Route.UploadDirect
            }

            DeliveryState.SENT -> {
                if (input.stateAgeMs < receiptWaitMs) Route.Wait(HoldReason.AWAITING_RECEIPT) else beforeDelivery(input)
            }

            DeliveryState.NEW -> {
                beforeDelivery(input)
            }
        }

    private fun onPhone(input: RouteInput): Route =
        when (input.conditions.phoneCloud) {
            CloudPresence.NO_ACCOUNT -> {
                Route.Settle
            }

            CloudPresence.UNKNOWN -> {
                Route.Wait(HoldReason.AWAITING_RECEIPT)
            }

            CloudPresence.SIGNED_IN -> {
                if (input.stateAgeMs < backupGraceMs || directBlocker(input) != null) {
                    Route.Wait(HoldReason.PHONE_BACKING_UP)
                } else {
                    Route.UploadDirect
                }
            }
        }

    private fun beforeDelivery(input: RouteInput): Route {
        val blocker = directBlocker(input)
        return when (input.conditions.phone) {
            PhoneReach.NEARBY -> {
                Route.SendToPhone
            }

            PhoneReach.RELAY_ONLY -> {
                if (input.hasMedia && blocker == null) Route.UploadDirect else Route.SendToPhone
            }

            PhoneReach.UNREACHABLE -> {
                when (blocker) {
                    null -> Route.UploadDirect
                    HoldReason.EDIT_NEEDS_PHONE -> Route.Wait(HoldReason.EDIT_NEEDS_PHONE)
                    HoldReason.NOT_LINKED -> Route.Wait(HoldReason.PHONE_UNREACHABLE)
                    else -> Route.Wait(blocker)
                }
            }
        }
    }

    private fun directBlocker(input: RouteInput): HoldReason? {
        val conditions = input.conditions
        return when {
            input.operation == PendingOperation.UPDATE -> HoldReason.EDIT_NEEDS_PHONE
            !conditions.linked -> HoldReason.NOT_LINKED
            !conditions.serverReady -> HoldReason.SERVER_TOO_OLD
            conditions.network == WatchNetwork.NONE -> HoldReason.NO_INTERNET
            conditions.network == WatchNetwork.BLUETOOTH_PROXY -> HoldReason.BLUETOOTH_ONLY
            input.hasMedia && conditions.network == WatchNetwork.METERED && !conditions.charging -> HoldReason.AUDIO_WAITS_FOR_WIFI
            else -> null
        }
    }

    companion object {
        const val BACKUP_GRACE_MS = 15 * 60_000L
        const val RECEIPT_WAIT_MS = 30_000L
    }
}
