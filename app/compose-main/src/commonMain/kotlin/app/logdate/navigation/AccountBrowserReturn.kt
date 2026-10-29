package app.logdate.navigation

import app.logdate.client.device.auth.BlueskyAppReturn
import kotlinx.coroutines.CancellationException

internal enum class AccountBrowserReturn { NOT_A_RETURN, CONNECTION, ORDINARY_SIGN_IN, UNAVAILABLE }

/** The persisted connection purpose gets first refusal before ordinary sign-in navigation. */
internal suspend fun resolveAccountBrowserReturn(
    url: String?,
    resumeConnection: suspend (String) -> Boolean,
): AccountBrowserReturn {
    if (url == null || BlueskyAppReturn.attemptId(url) == null) return AccountBrowserReturn.NOT_A_RETURN
    return try {
        if (resumeConnection(url)) AccountBrowserReturn.CONNECTION else AccountBrowserReturn.ORDINARY_SIGN_IN
    } catch (canceled: CancellationException) {
        throw canceled
    } catch (_: Exception) {
        AccountBrowserReturn.UNAVAILABLE
    }
}
