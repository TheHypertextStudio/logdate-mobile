package app.logdate.client.datastore

/**
 * What assistants on this device may see and do, as saved: the permission scope names (such as
 * `memories:read`), the ids of the journals whose entries they may read, and whether entries outside
 * any journal count too.
 */
data class StoredDeviceAssistantAccess(
    val scopes: Set<String> = emptySet(),
    val journalIds: Set<String> = emptySet(),
    val includesUnfiled: Boolean = false,
) {
    override fun toString(): String = "StoredDeviceAssistantAccess([redacted])"
}
