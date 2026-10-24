package app.logdate.shared.activitypub.core

/**
 * An interface to a LogDate server.
 */
interface LogdateServerBaseClient {
    // TODO: Move this to separate LogDate client library module

    var domain: String

    val baseUrl: String
}

interface DeviceRegistrationClient {
    /**
     * Registers the device with the server.
     */
    suspend fun registerDevice(token: String)

    /**
     * Unregisters the device with the server.
     */
    suspend fun unregisterDevice(token: String)
}

class LogdateServerClient(
    domain: String,
) : LogdateServerBaseClient,
    DeviceRegistrationClient {
    override val baseUrl: String
        get() = "https://$domain/"

    private var _domain = domain

    override var domain: String
        get() = _domain

        /*
         * Sets the domain of the LogDate server.
         *
         * Whenever the domain is changed, the client is reinitialized with the new domain.
         */
        set(value) {
            // TODO: Reinitialize client with new domain
            _domain = value
        }

    override suspend fun registerDevice(token: String) {
        // TODO: Implement device registration
    }

    override suspend fun unregisterDevice(token: String) {
        // TODO: Implement device registration
    }
}
