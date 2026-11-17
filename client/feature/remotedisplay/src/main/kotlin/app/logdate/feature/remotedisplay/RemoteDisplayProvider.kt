package app.logdate.feature.remotedisplay

import android.content.Context
import app.logdate.client.media.display.RemoteDisplayManager

/**
 * Entry point for the remote display dynamic feature module.
 *
 * The base app discovers this class via reflection after the module is installed.
 * This avoids a compile-time dependency from the base app to the dynamic module.
 *
 * Client/media OnDemandRemoteDisplayManager loads it by class name via reflection, which the tool cannot see
 */
@Suppress("unused")
object RemoteDisplayProvider {
    /**
     * Fully qualified class name used by the base app to load this provider via reflection.
     */
    const val PROVIDER_CLASS = "app.logdate.feature.remotedisplay.RemoteDisplayProvider"

    /**
     * Creates the platform [RemoteDisplayManager] implementation.
     */
    fun create(context: Context): RemoteDisplayManager = AndroidRemoteDisplayManager(context)
}
