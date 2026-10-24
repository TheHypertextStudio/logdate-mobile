package app.logdate.client.sync.space

import app.logdate.shared.model.AccountStorageMode

/** Where this device syncs the signed-in account's data. */
sealed interface SpaceRoute {
    /** The account's sealed personal space. */
    data object Space : SpaceRoute

    /** The per-kind sync endpoints. */
    data object Legacy : SpaceRoute

    /**
     * Neither: syncing anywhere would split the account's data between two stores, so nothing
     * syncs and [reason] is shown as a sync issue.
     */
    data class Blocked(
        val reason: String,
    ) : SpaceRoute
}

/**
 * Decides where this device syncs the signed-in account. The server decides each account's storage
 * mode, and every device of the account must follow it, so the device never guesses:
 * - an account in [AccountStorageMode.PERSONAL_SPACE_V1] syncs through its personal space, when the
 *   server offers personal spaces and this build syncs them;
 * - an account in [AccountStorageMode.LEGACY] syncs through the per-kind endpoints;
 * - anything else blocks sync with a specific issue: an unknown or unrecognized mode, or a
 *   personal-space account this device can't reach.
 */
class SpaceTransportGate(
    private val enabledInThisBuild: suspend () -> Boolean,
    private val serverOffersPersonalSpace: () -> Boolean,
    private val storageMode: suspend () -> AccountStorageMode?,
) {
    suspend fun route(): SpaceRoute =
        when (storageMode()) {
            AccountStorageMode.LEGACY -> {
                SpaceRoute.Legacy
            }

            AccountStorageMode.PERSONAL_SPACE_V1 -> {
                when {
                    !serverOffersPersonalSpace() -> {
                        SpaceRoute.Blocked(
                            "This account's journal is stored in a personal space, and the server doesn't offer personal spaces",
                        )
                    }

                    !enabledInThisBuild() -> {
                        SpaceRoute.Blocked(
                            "This account's journal is stored in a personal space, which this version of LogDate doesn't sync",
                        )
                    }

                    else -> {
                        SpaceRoute.Space
                    }
                }
            }

            AccountStorageMode.UNRECOGNIZED -> {
                SpaceRoute.Blocked("The server stores this account in a way this version of LogDate doesn't know")
            }

            null -> {
                SpaceRoute.Blocked("The server hasn't said how it stores this account")
            }
        }

    companion object {
        /** Every account syncs through the per-kind endpoints. */
        val Legacy: SpaceTransportGate = SpaceTransportGate({ false }, { false }, { AccountStorageMode.LEGACY })
    }
}
