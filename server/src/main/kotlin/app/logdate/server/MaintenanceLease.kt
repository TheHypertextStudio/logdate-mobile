package app.logdate.server

/**
 * Lets one server instance at a time run a maintenance pass. Every instance starts the same
 * maintenance loop, so without a lease they would all purge the same rows at the same moment.
 */
fun interface MaintenanceLease {
    /** Runs [pass] unless another instance is running it now, and returns whether it ran. */
    suspend fun runExclusively(pass: suspend () -> Unit): Boolean

    companion object {
        /** For a server without a shared database, where no other instance can run the pass. */
        val Local =
            MaintenanceLease { pass ->
                pass()
                true
            }
    }
}
