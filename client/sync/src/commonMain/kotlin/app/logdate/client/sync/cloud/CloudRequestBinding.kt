package app.logdate.client.sync.cloud

import app.logdate.client.datastore.UserSession
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A request's destination and credentials captured together before any suspend point. */
data class CloudRequestLocation(
    val origin: String,
    val apiBaseUrl: String,
)

internal class CloudRequestBinding(
    val location: CloudRequestLocation,
    val session: UserSession,
    val authorizedAccessToken: String = session.accessToken,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CloudRequestBinding>

    fun withAccessToken(token: String): CloudRequestBinding = CloudRequestBinding(location, session, token)
}

/** Implemented by repositories that can bind refresh and retry to one server location. */
interface CloudRequestLocationProvider {
    fun captureLocation(): CloudRequestLocation

    fun isCurrentOrigin(origin: String): Boolean
}
